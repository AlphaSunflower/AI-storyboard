package com.storyboard.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(
            "test-access-secret-key-at-least-256-bits-long!!",
            "test-refresh-secret-key-at-least-256-bits-long",
            "newworkflow-backend"
        );
    }

    // ==================== Access Token Tests ====================

    @Test
    void shouldSignAndVerifyAccessToken() {
        String token = provider.signAccessToken("user-123", "member", "enabled");
        assertNotNull(token);
        assertFalse(token.isEmpty());

        Claims claims = provider.verifyAccessToken(token);
        assertEquals("user-123", claims.getSubject());
        assertEquals("access", claims.get("typ", String.class));
        assertEquals("member", claims.get("role", String.class));
        assertEquals("enabled", claims.get("status", String.class));
        assertEquals("newworkflow-backend", claims.getIssuer());
        assertNotNull(claims.getId());
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void shouldSignAccessTokenWithAdminRole() {
        String token = provider.signAccessToken("admin-1", "admin", "enabled");
        Claims claims = provider.verifyAccessToken(token);
        assertEquals("admin-1", claims.getSubject());
        assertEquals("admin", claims.get("role", String.class));
    }

    @Test
    void shouldSignAccessTokenWithDisabledStatus() {
        String token = provider.signAccessToken("user-456", "member", "disabled");
        Claims claims = provider.verifyAccessToken(token);
        assertEquals("disabled", claims.get("status", String.class));
    }

    @Test
    void shouldRejectAccessTokenWithInvalidRole() {
        String token = provider.signAccessToken("user-789", "superadmin", "enabled");
        assertThrows(IllegalArgumentException.class, () -> provider.verifyAccessToken(token));
    }

    @Test
    void shouldRejectAccessTokenWithInvalidStatus() {
        String token = provider.signAccessToken("user-789", "member", "suspended");
        assertThrows(IllegalArgumentException.class, () -> provider.verifyAccessToken(token));
    }

    @Test
    void shouldRejectExpiredAccessToken() {
        JwtTokenProvider expiredProvider = new JwtTokenProvider(
            "test-access-secret-key-at-least-256-bits-long!!",
            "test-refresh-secret-key-at-least-256-bits-long",
            "newworkflow-backend",
            -1,  // access TTL = -1s (immediately expired)
            2592000
        );
        String token = expiredProvider.signAccessToken("user-123", "member", "enabled");
        assertThrows(ExpiredJwtException.class, () -> expiredProvider.verifyAccessToken(token));
    }

    @Test
    void shouldRejectAccessTokenWithWrongKey() {
        String token = provider.signAccessToken("user-123", "member", "enabled");
        // 用不同的密钥验证应失败
        JwtTokenProvider otherProvider = new JwtTokenProvider(
            "wrong-access-secret-key-at-least-256-bits!",
            "test-refresh-secret-key-at-least-256-bits-long",
            "newworkflow-backend"
        );
        assertThrows(SignatureException.class, () -> otherProvider.verifyAccessToken(token));
    }

    @Test
    void shouldRejectAccessTokenWithWrongIssuer() {
        JwtTokenProvider wrongIssuerProvider = new JwtTokenProvider(
            "test-access-secret-key-at-least-256-bits-long!!",
            "test-refresh-secret-key-at-least-256-bits-long",
            "wrong-issuer"
        );
        String token = provider.signAccessToken("user-123", "member", "enabled");
        assertThrows(Exception.class, () -> wrongIssuerProvider.verifyAccessToken(token));
    }

    // ==================== Refresh Token Tests ====================

    @Test
    void shouldSignAndVerifyRefreshToken() {
        String token = provider.signRefreshToken("user-123");
        assertNotNull(token);
        assertFalse(token.isEmpty());

        Claims claims = provider.verifyRefreshToken(token);
        assertEquals("user-123", claims.getSubject());
        assertEquals("refresh", claims.get("typ", String.class));
        assertEquals("newworkflow-backend", claims.getIssuer());
        assertNotNull(claims.getId());
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        // refresh token should NOT have role/status
        assertNull(claims.get("role"));
        assertNull(claims.get("status"));
    }

    @Test
    void shouldRejectExpiredRefreshToken() {
        JwtTokenProvider expiredProvider = new JwtTokenProvider(
            "test-access-secret-key-at-least-256-bits-long!!",
            "test-refresh-secret-key-at-least-256-bits-long",
            "newworkflow-backend",
            3600,
            -1  // refresh TTL = -1s (immediately expired)
        );
        String token = expiredProvider.signRefreshToken("user-123");
        assertThrows(ExpiredJwtException.class, () -> expiredProvider.verifyRefreshToken(token));
    }

    @Test
    void shouldRejectRefreshTokenWithWrongKey() {
        String token = provider.signRefreshToken("user-123");
        JwtTokenProvider otherProvider = new JwtTokenProvider(
            "test-access-secret-key-at-least-256-bits-long!!",
            "wrong-refresh-secret-key-at-least-256-bits",
            "newworkflow-backend"
        );
        assertThrows(SignatureException.class, () -> otherProvider.verifyRefreshToken(token));
    }

    @Test
    void shouldRejectRefreshTokenWithWrongIssuer() {
        JwtTokenProvider wrongIssuerProvider = new JwtTokenProvider(
            "test-access-secret-key-at-least-256-bits-long!!",
            "test-refresh-secret-key-at-least-256-bits-long",
            "wrong-issuer"
        );
        String token = provider.signRefreshToken("user-123");
        assertThrows(Exception.class, () -> wrongIssuerProvider.verifyRefreshToken(token));
    }

    // ==================== Cross-Type Rejection Tests ====================

    @Test
    void shouldRejectAccessTokenAsRefreshToken() {
        // Access token is signed with accessKey, refresh verification uses refreshKey
        // → signature mismatch throws SignatureException before typ check
        String accessToken = provider.signAccessToken("user-123", "member", "enabled");
        assertThrows(SignatureException.class, () -> provider.verifyRefreshToken(accessToken));
    }

    @Test
    void shouldRejectRefreshTokenAsAccessToken() {
        // Refresh token is signed with refreshKey, access verification uses accessKey
        // → signature mismatch throws SignatureException before typ check
        String refreshToken = provider.signRefreshToken("user-123");
        assertThrows(SignatureException.class, () -> provider.verifyAccessToken(refreshToken));
    }

    // ==================== Cross-Key Rejection Tests ====================

    @Test
    void shouldRejectAccessTokenSignedWithRefreshKey() {
        // access token should not be verifiable with refresh key through verifyAccessToken
        String accessToken = provider.signAccessToken("user-123", "member", "enabled");
        // But it was signed with access key, so verifyRefreshToken will fail due to wrong key
        assertThrows(SignatureException.class, () -> provider.verifyRefreshToken(accessToken));
    }

    @Test
    void shouldRejectRefreshTokenSignedWithAccessKey() {
        String refreshToken = provider.signRefreshToken("user-123");
        assertThrows(SignatureException.class, () -> provider.verifyAccessToken(refreshToken));
    }

    // ==================== TTL Tests ====================

    @Test
    void shouldHonorAccessTokenTtl() {
        JwtTokenProvider customProvider = new JwtTokenProvider(
            "test-access-secret-key-at-least-256-bits-long!!",
            "test-refresh-secret-key-at-least-256-bits-long",
            "newworkflow-backend",
            60,     // 60s access TTL
            2592000
        );
        String token = customProvider.signAccessToken("user-123", "member", "enabled");
        Claims claims = customProvider.verifyAccessToken(token);
        long ttl = claims.getExpiration().getTime() / 1000 - claims.getIssuedAt().getTime() / 1000;
        assertEquals(60, ttl);
    }

    @Test
    void shouldHonorRefreshTokenTtl() {
        JwtTokenProvider customProvider = new JwtTokenProvider(
            "test-access-secret-key-at-least-256-bits-long!!",
            "test-refresh-secret-key-at-least-256-bits-long",
            "newworkflow-backend",
            3600,
            7200    // 7200s refresh TTL
        );
        String token = customProvider.signRefreshToken("user-123");
        Claims claims = customProvider.verifyRefreshToken(token);
        long ttl = claims.getExpiration().getTime() / 1000 - claims.getIssuedAt().getTime() / 1000;
        assertEquals(7200, ttl);
    }

    // ==================== Token Uniqueness Tests ====================

    @Test
    void shouldGenerateUniqueJtiForEachToken() {
        String token1 = provider.signAccessToken("user-123", "member", "enabled");
        String token2 = provider.signAccessToken("user-123", "member", "enabled");
        String jti1 = provider.verifyAccessToken(token1).getId();
        String jti2 = provider.verifyAccessToken(token2).getId();
        assertNotEquals(jti1, jti2);
    }
}
