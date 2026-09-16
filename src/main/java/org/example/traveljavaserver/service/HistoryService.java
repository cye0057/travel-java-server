package org.example.traveljavaserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.traveljavaserver.common.UnauthorizedException;
import org.example.traveljavaserver.vo.HistoryItemVO;
import org.example.traveljavaserver.vo.HistoryMessageVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 用户历史对话：MySQL 是唯一事实源，Redis 只是"登录即见"的读缓存。
 *
 * 双存储的角色分工：
 * - chat_conversation / chat_message：全量持久层，7天过期由定时清理任务执行；
 * - hist:index:{userId}（列表 JSON）、hist:msgs:{sessionId}（消息 JSON）：TTL 7天，
 *   写对话时主动失效（DEL），下次读触发"回源 MySQL + 回填缓存"——
 *   读多写少场景里最稳的缓存一致性套路（Cache-Aside），不用维护增量更新的并发问题。
 *
 * 缓存值用整串 JSON 而非 Redis List：列表/消息都要求"一次全取"，整串读写一次一命令，
 * 省掉 LRANGE 与"半截列表"的边界处理；数据量上限 50 会话 / 200 条消息，单值体积可控。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private static final String INDEX_PREFIX = "hist:index:";
    private static final String MSG_PREFIX = "hist:msgs:";

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${chat.history.ttl-seconds:604800}")
    private long ttlSeconds;

    /**
     * 一轮问答结束后落库：会话 upsert + 双消息 insert。
     * 会话表的 ON DUPLICATE KEY 只更新 updated_at（排序权重）；title 仅首次插入时写。
     */
    public void persist(Long userId, String sessionId, String userContent, String assistantContent) {
        String title = userContent.length() > 24 ? userContent.substring(0, 24) + "…" : userContent;
        jdbcTemplate.update(
                "INSERT INTO chat_conversation (session_id, user_id, title) VALUES (?,?,?) "
                        + "ON DUPLICATE KEY UPDATE updated_at = NOW()",
                sessionId, userId, title);
        jdbcTemplate.update(
                "INSERT INTO chat_message (session_id, user_id, role, content) VALUES (?,?,?,?)",
                sessionId, userId, "user", userContent);
        jdbcTemplate.update(
                "INSERT INTO chat_message (session_id, user_id, role, content) VALUES (?,?,?,?)",
                sessionId, userId, "assistant", assistantContent);
        invalidateCache(userId, sessionId);
    }

    /** 历史会话列表：Redis 命中直接回，未命中回源 MySQL 并回填 */
    public List<HistoryItemVO> listHistory(Long userId) {
        try {
            String cached = redisTemplate.opsForValue().get(INDEX_PREFIX + userId);
            if (cached != null) {
                return Arrays.asList(objectMapper.readValue(cached, HistoryItemVO[].class));
            }
        } catch (Exception e) {
            log.warn("读历史列表缓存失败，回源MySQL: {}", e.getMessage());
        }
        List<HistoryItemVO> items = jdbcTemplate.query(
                "SELECT session_id, title, DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i') "
                        + "FROM chat_conversation WHERE user_id = ? ORDER BY updated_at DESC LIMIT 50",
                (rs, n) -> new HistoryItemVO(rs.getString(1), rs.getString(2), rs.getString(3)),
                userId);
        cachePut(INDEX_PREFIX + userId, items);
        return items;
    }

    /** 打开某段历史：先验归属（拿别人的 sessionId 也查不到），再走缓存→回源 */
    public List<HistoryMessageVO> sessionMessages(Long userId, String sessionId) {
        //queryForObject 无结果会抛 EmptyResultDataAccessException 而不是返回 null，
        //用 queryForList 判空才是"查不到→拒绝"的正确写法
        List<Long> owners = jdbcTemplate.queryForList(
                "SELECT user_id FROM chat_conversation WHERE session_id = ?", Long.class, sessionId);
        if (owners.isEmpty() || !owners.get(0).equals(userId)) {
            throw new UnauthorizedException("会话不存在或无权访问");
        }
        try {
            String cached = redisTemplate.opsForValue().get(MSG_PREFIX + sessionId);
            if (cached != null) {
                return Arrays.asList(objectMapper.readValue(cached, HistoryMessageVO[].class));
            }
        } catch (Exception e) {
            log.warn("读消息缓存失败，回源MySQL: {}", e.getMessage());
        }
        List<HistoryMessageVO> msgs = jdbcTemplate.query(
                "SELECT role, content, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') "
                        + "FROM chat_message WHERE session_id = ? ORDER BY id ASC LIMIT 200",
                (rs, n) -> new HistoryMessageVO(rs.getString(1), rs.getString(2), rs.getString(3)),
                sessionId);
        cachePut(MSG_PREFIX + sessionId, msgs);
        return msgs;
    }

    /**
     * 清理 7 天前的历史：MySQL 是"删真身"，Redis 靠 TTL 自己蒸发——
     * 两边同一个"7天"语义、两种过期机制，各用平台最自然的方式。
     * @Scheduled 入口在 HistoryCleanupJob。
     */
    public int purgeExpired() {
        int msgs = jdbcTemplate.update(
                "DELETE FROM chat_message WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY)", ttlSeconds / 86400);
        int convs = jdbcTemplate.update(
                "DELETE FROM chat_conversation WHERE updated_at < DATE_SUB(NOW(), INTERVAL ? DAY)", ttlSeconds / 86400);
        log.info("历史清理完成: 删除消息{}条、会话{}个", msgs, convs);
        return msgs + convs;
    }

    /** 写后失效：下次读会重建。DEL 失败无所谓（缓存有TTL，最多短时读到旧数据） */
    private void invalidateCache(Long userId, String sessionId) {
        try {
            redisTemplate.delete(List.of(INDEX_PREFIX + userId, MSG_PREFIX + sessionId));
        } catch (Exception e) {
            log.warn("历史缓存失效失败: {}", e.getMessage());
        }
    }

    private void cachePut(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value),
                    Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("历史缓存回填失败: {}", e.getMessage());
        }
    }
}
