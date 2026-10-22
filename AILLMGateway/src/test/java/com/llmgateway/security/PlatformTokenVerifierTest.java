package com.llmgateway.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关令牌校验：与主后端 {@code JwtTokenProvider.signGatewayToken} 的签法对齐。
 *
 * <p>主后端用 {@code SecretKeySpec(secret, "HmacSHA256")} + {@code signWith(accessKey)} 签；
 * 这里用 {@code Keys.hmacShaKeyFor} 验——两者都是 HS256，同一个密钥即可互通，
 * 本测试正是锁这一点：换算法或换密钥会立刻红。
 */
class PlatformTokenVerifierTest {

    private static final String SECRET = "d02f1779750841f8b4de65e47a913e89";

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String secret, String typ, String userId, long ttlSeconds) {
        long now = System.currentTimeMillis() / 1000;
        return Jwts.builder()
                .header().add("alg", "HS256").add("typ", "JWT").and()
                .claim("typ", typ)
                .subject(userId)
                .issuedAt(new Date(now * 1000))
                .expiration(new Date((now + ttlSeconds) * 1000))
                .signWith(key(secret))
                .compact();
    }

    @Test
    void acceptsGatewayTokenSignedByPlatform() {
        PlatformTokenVerifier verifier = new PlatformTokenVerifier(SECRET);
        String token = sign(SECRET, "gateway", "user-42", 3600);

        assertThat(verifier.enabled()).isTrue();
        assertThat(verifier.verify(token)).isEqualTo("user-42");
    }

    @Test
    void rejectsAccessTokenType() {
        PlatformTokenVerifier verifier = new PlatformTokenVerifier(SECRET);
        String token = sign(SECRET, "access", "user-42", 3600);

        assertThat(verifier.verify(token)).isNull();
    }

    @Test
    void rejectsExpiredToken() {
        PlatformTokenVerifier verifier = new PlatformTokenVerifier(SECRET);
        String token = sign(SECRET, "gateway", "user-42", -60);

        assertThat(verifier.verify(token)).isNull();
    }

    @Test
    void rejectsForeignSecret() {
        PlatformTokenVerifier verifier = new PlatformTokenVerifier(SECRET);
        String token = sign("another-secret-another-secret-1234", "gateway", "user-42", 3600);

        assertThat(verifier.verify(token)).isNull();
    }

    @Test
    void disabledWhenSecretMissing() {
        PlatformTokenVerifier verifier = new PlatformTokenVerifier("");

        assertThat(verifier.enabled()).isFalse();
        assertThat(verifier.verify(sign(SECRET, "gateway", "user-42", 3600))).isNull();
    }
}
