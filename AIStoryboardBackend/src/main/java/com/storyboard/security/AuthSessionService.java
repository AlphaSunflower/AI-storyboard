package com.storyboard.security;

import com.storyboard.common.BusinessException;
import com.storyboard.config.JwtConfig;
import com.storyboard.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Redis-backed login sessions, refresh rotation, revocation, and stream tickets. */
@Service
@RequiredArgsConstructor
public class AuthSessionService {

    private static final String SESSION_PREFIX = "session:";
    private static final String USER_SESSIONS_PREFIX = "user-sessions:";
    private static final String STREAM_TICKET_PREFIX = "stream-ticket:";
    private static final long STREAM_TICKET_TTL_SECONDS = 60;

    private static final DefaultRedisScript<Long> ROTATE_REFRESH_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('HGET', KEYS[1], 'refreshJti')
            if not current then
                return -1
            end
            if current ~= ARGV[1] then
                redis.call('DEL', KEYS[1])
                redis.call('SREM', KEYS[2], ARGV[2])
                return 0
            end
            redis.call('HSET', KEYS[1], 'refreshJti', ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;

    public SessionTokens issue(User user) {
        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtTokenProvider.signAccessToken(
                user.getId(), user.getRole(), user.getStatus(), sessionId);
        String refreshToken = jwtTokenProvider.signRefreshToken(user.getId(), sessionId);
        String refreshJti = jwtTokenProvider.verifyRefreshToken(refreshToken).getId();

        String key = sessionKey(sessionId);
        redisTemplate.opsForHash().putAll(key, Map.of(
                "userId", user.getId(),
                "refreshJti", refreshJti,
                "role", user.getRole(),
                "status", user.getStatus()
        ));
        redisTemplate.expire(key, java.time.Duration.ofSeconds(jwtConfig.getRefreshTokenTtl()));
        redisTemplate.opsForSet().add(userSessionsKey(user.getId()), sessionId);
        return new SessionTokens(accessToken, refreshToken, sessionId);
    }

    public SessionTokens rotate(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(40101, "Refresh Token 缺失");
        }
        var claims = jwtTokenProvider.verifyRefreshToken(refreshToken);
        String sessionId = claims.get("sid", String.class);
        String userId = claims.getSubject();
        String oldJti = claims.getId();
        if (sessionId == null || sessionId.isBlank() || userId == null || userId.isBlank()
                || oldJti == null || oldJti.isBlank()) {
            throw new BusinessException(40101, "Refresh Token 无效");
        }

        Map<Object, Object> session = redisTemplate.opsForHash().entries(sessionKey(sessionId));
        String role = session.get("role") == null ? "member" : String.valueOf(session.get("role"));
        String status = session.get("status") == null ? "enabled" : String.valueOf(session.get("status"));
        String newRefresh = jwtTokenProvider.signRefreshToken(userId, sessionId);
        String newJti = jwtTokenProvider.verifyRefreshToken(newRefresh).getId();
        Long rotated = redisTemplate.execute(
                ROTATE_REFRESH_SCRIPT,
                List.of(sessionKey(sessionId), userSessionsKey(userId)),
                oldJti, sessionId, newJti, String.valueOf(jwtConfig.getRefreshTokenTtl()));
        if (rotated == null || rotated <= 0) {
            throw new BusinessException(40101, "Refresh Token 已失效或被重复使用");
        }
        String accessToken = jwtTokenProvider.signAccessToken(userId, role, status, sessionId);
        return new SessionTokens(accessToken, newRefresh, sessionId);
    }

    public void revokeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        Object userId = redisTemplate.opsForHash().get(sessionKey(sessionId), "userId");
        redisTemplate.delete(sessionKey(sessionId));
        if (userId != null) {
            redisTemplate.opsForSet().remove(userSessionsKey(String.valueOf(userId)), sessionId);
        }
    }

    public void revokeAll(String userId) {
        if (userId == null || userId.isBlank()) return;
        Set<String> sessions = redisTemplate.opsForSet().members(userSessionsKey(userId));
        if (sessions != null) {
            sessions.forEach(sessionId -> redisTemplate.delete(sessionKey(sessionId)));
        }
        redisTemplate.delete(userSessionsKey(userId));
    }

    public StreamTicket issueStreamTicket(String userId, String path) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(40101, "未登录");
        }
        if (path == null || !path.startsWith("/") || !path.endsWith("/events")) {
            throw new BusinessException(40001, "仅允许为 SSE 事件路径签发票据");
        }
        String ticket = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                STREAM_TICKET_PREFIX + ticket,
                userId + "\n" + path,
                java.time.Duration.ofSeconds(STREAM_TICKET_TTL_SECONDS));
        return new StreamTicket(ticket,
                Instant.now().plusSeconds(STREAM_TICKET_TTL_SECONDS).toEpochMilli());
    }

    private static String sessionKey(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    private static String userSessionsKey(String userId) {
        return USER_SESSIONS_PREFIX + userId;
    }

    public record SessionTokens(String accessToken, String refreshToken, String sessionId) {
    }

    public record StreamTicket(String ticket, long expiresAt) {
    }
}
