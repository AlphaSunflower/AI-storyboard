package com.storyboard.security;

import com.storyboard.config.JwtConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthSessionServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private JwtTokenProvider tokenProvider;
    private AuthSessionService sessionService;

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setAccessSecret("access-secret-0123456789abcdef0123456789abcdef");
        config.setRefreshSecret("refresh-secret-0123456789abcdef0123456789abcdef");
        config.setIssuer("ai-storyboard-backend");
        config.setAccessTokenTtl(3600);
        config.setRefreshTokenTtl(2592000);
        tokenProvider = new JwtTokenProvider(
                config.getAccessSecret(), config.getRefreshSecret(), config.getIssuer(),
                config.getAccessTokenTtl(), config.getRefreshTokenTtl());
        sessionService = new AuthSessionService(redisTemplate, tokenProvider, config);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentOldRefreshReplayUsesCurrentRotatedToken() {
        String userId = "u1";
        String sessionId = "s1";
        String oldToken = tokenProvider.signRefreshToken(userId, sessionId, "old-jti");
        String currentToken = tokenProvider.signRefreshToken(userId, sessionId, "new-jti");

        when(hashOperations.entries("session:s1")).thenReturn(
                Map.of("userId", userId, "refreshJti", "old-jti", "role", "member", "status", "enabled"),
                Map.of("userId", userId, "refreshJti", "new-jti", "refreshToken", currentToken,
                        "role", "member", "status", "enabled"));
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(2L);

        AuthSessionService.SessionTokens tokens = sessionService.rotate(oldToken);

        assertThat(tokens.refreshToken()).isEqualTo(currentToken);
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokenProvider.verifyAccessToken(tokens.accessToken()).get("sid", String.class))
                .isEqualTo(sessionId);
    }
}
