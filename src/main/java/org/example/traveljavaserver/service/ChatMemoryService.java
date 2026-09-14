package org.example.traveljavaserver.service;

import lombok.extern.slf4j.Slf4j;
import org.example.traveljavaserver.dto.ChatMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话历史的 Redis 存储。
 *
 * 数据结构选择：List（key = chat:hist:{sessionId}，每个元素是一条 JSON 化的消息）。
 * 用 List 而不是把整个历史序列化成一个大 String 存 String 类型，
 * 是因为 RPUSH/LTRIM/EXPIRE 都是单命令原子操作，避免了“读出-修改-写回”
 * 三步之间被并发请求插队导致的消息丢失。
 */
@Service
@Slf4j
public class ChatMemoryService {

    private static final String KEY_PREFIX = "chat:hist:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 只保留最近 N 条消息，控制 Prompt 的 token 成本 */
    @Value("${chat.memory.max-messages:20}")
    private int maxMessages;

    /** 会话空闲超过该时长自动删除 */
    @Value("${chat.memory.ttl-seconds:1800}")
    private long ttlSeconds;

    public ChatMemoryService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 读取会话全部历史（最旧 → 最新），不存在或 Redis 异常时返回空列表，不阻断对话 */
    public List<ChatMessage> load(String sessionId) {
        List<ChatMessage> messages = new ArrayList<>();
        List<String> raw;
        try {
            raw = redisTemplate.opsForList().range(KEY_PREFIX + sessionId, 0, -1);
        } catch (Exception e) {
            log.warn("读取会话历史失败，按无历史处理: sessionId={}", sessionId, e);
            return messages;
        }
        if (raw == null) {
            return messages;
        }
        for (String json : raw) {
            try {
                messages.add(objectMapper.readValue(json, ChatMessage.class));
            } catch (JacksonException e) {
                //单条脏数据不应让整段对话中断，跳过并告警
                log.warn("会话历史反序列化失败，跳过该条: {}", json, e);
            }
        }

        while (!messages.isEmpty() && !"user".equals(messages.get(0).role())) {
            messages.remove(0);
        }
        return messages;
    }

    /** 追加消息，并维护滑动窗口和过期时间 */
    public void append(String sessionId, ChatMessage... messages) {
        String key = KEY_PREFIX + sessionId;
        try {
            for (ChatMessage m : messages) {
                redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(m));
            }
            //负数下标：[-maxMessages, -1] 即“最后 N 条”，更早的全部裁掉 —— 这就是滑动窗口
            redisTemplate.opsForList().trim(key, -maxMessages, -1);
            //每次写入都重置 TTL：语义是“30 分钟不聊才忘记”，而不是“会话总寿命 30 分钟”
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            //写历史失败只影响下一轮的上下文完整，不应让当前回答失败
            log.warn("写入会话历史失败: sessionId={}", sessionId, e);
        }
    }
}
