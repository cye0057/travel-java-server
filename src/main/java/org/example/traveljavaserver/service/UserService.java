package org.example.traveljavaserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.traveljavaserver.dto.LoginDTO;
import org.example.traveljavaserver.dto.RegisterDTO;
import org.example.traveljavaserver.vo.LoginUserVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户注册/登录/token 管理。
 *
 * 会话凭证用"Redis 不透明 token"而不是 JWT，理由：
 * 1) 项目已为会话记忆部署了 Redis，加 token 存储是零新增基础设施；
 * 2) 不透明 token 可即时吊销（登出/风控拉黑删 key 即失效），JWT 默认做不到（除非再加黑名单，反而更复杂）；
 * 3) 每次请求顺手 EXPIRE 续期，实现"7天不活跃才登出"的滑动过期，JWT 固定 exp 做不到。
 * 代价是每次请求多一跳 Redis GET（亚毫秒级），对个人项目完全不敏感。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String TOKEN_PREFIX = "login:token:";
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    /** 只引入 spring-security-crypto 这一个轻量包，不启用整个 Spring Security 过滤器链 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${user.token-ttl-seconds:604800}")
    private long tokenTtlSeconds;

    /**
     * 注册：唯一性交给数据库 UNIQUE 兜底（先查后插的"查"挡不住并发双击，uk_username 冲突才算可靠）。
     * 注册成功即登录（省一次密码传输），直接返回 token。
     */
    public LoginUserVO register(RegisterDTO dto) {
        String username = dto.getUsername().trim();
        Long exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Long.class, username);
        if (exists != null && exists > 0) {
            throw new IllegalArgumentException("用户名已存在");
        }
        String userCode = generateUserCode();
        String nickname = (dto.getNickname() == null || dto.getNickname().isBlank())
                ? username : dto.getNickname().trim();
        try {
            jdbcTemplate.update(
                    "INSERT INTO users (user_code, username, password_hash, nickname, last_login_at) VALUES (?,?,?,?,NOW())",
                    userCode, username, passwordEncoder.encode(dto.getPassword()), nickname);
        } catch (DuplicateKeyException e) {
            //并发下两请求同时通过 COUNT 检查时，由唯一键裁决
            throw new IllegalArgumentException("用户名已存在");
        }
        log.info("新用户注册: username={}, userCode={}", username, userCode);
        return issueToken(username, userCode, nickname);
    }

    public LoginUserVO login(LoginDTO dto) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, user_code, username, password_hash, nickname FROM users WHERE username = ?",
                dto.getUsername().trim());
        if (rows.isEmpty()) {
            //用户不存在与密码错误回同一句话：不给撞库者枚举用户名的信号
            throw new IllegalArgumentException("用户名或密码错误");
        }
        Map<String, Object> user = rows.get(0);
        if (!passwordEncoder.matches(dto.getPassword(), (String) user.get("password_hash"))) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        jdbcTemplate.update("UPDATE users SET last_login_at = NOW() WHERE id = ?", user.get("id"));
        return issueToken((String) user.get("username"), (String) user.get("user_code"), (String) user.get("nickname"));
    }

    /** 登出 = 删除 Redis 里的 token，瞬时失效；删不删成功都回 OK（幂等） */
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            try {
                redisTemplate.delete(TOKEN_PREFIX + token);
            } catch (Exception e) {
                log.warn("登出删除token失败（可能Redis未启动）: {}", e.getMessage());
            }
        }
    }

    /**
     * token → userId；有效则滑动续期（用户持续使用就一直在线，7天不动才掉线）。
     * 返回 null 表示未登录/已过期，不抛异常——chat 入口"有就带上、没有走游客"要的就是这种软判定。
     */
    public Long resolveUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String uid = redisTemplate.opsForValue().get(TOKEN_PREFIX + token);
            if (uid == null) {
                return null;
            }
            redisTemplate.expire(TOKEN_PREFIX + token, Duration.ofSeconds(tokenTtlSeconds));
            return Long.valueOf(uid);
        } catch (Exception e) {
            log.warn("token校验时Redis异常，按未登录处理: {}", e.getMessage());
            return null;
        }
    }

    /** 从 Authorization: Bearer xxx 提取 token 原文 */
    public static String extractBearer(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        return authHeader.startsWith(prefix) ? authHeader.substring(prefix.length()).trim() : authHeader.trim();
    }

    /** /api/user/me：只回显安全字段，绝不把 password_hash 带出去 */
    public LoginUserVO currentUser(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_code, username, nickname FROM users WHERE id = ?", userId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("用户不存在");
        }
        Map<String, Object> u = rows.get(0);
        return new LoginUserVO(null, (String) u.get("user_code"), (String) u.get("username"), (String) u.get("nickname"));
    }

    private LoginUserVO issueToken(String username, String userCode, String nickname) {
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            redisTemplate.opsForValue().set(TOKEN_PREFIX + token, String.valueOf(userId),
                    Duration.ofSeconds(tokenTtlSeconds));
        } catch (Exception e) {
            //Redis挂了就没法发会话凭证：明确报错，而不是给了个"登录成功但下请求401"的诡异状态
            throw new IllegalStateException("登录服务暂不可用（Redis未启动）", e);
        }
        return new LoginUserVO(token, userCode, username, nickname);
    }

    /**
     * 对外唯一标识：U + 时间戳 + 5位随机大写字母数字（剔除易混字符 I/O/0/1）。
     * 为何不直接暴露自增 id：id 连续可被枚举爬取全站用户；user_code 无规律且注册时一次性分配、永不变更。
     */
    private String generateUserCode() {
        StringBuilder sb = new StringBuilder("U")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmm")));
        for (int i = 0; i < 5; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
