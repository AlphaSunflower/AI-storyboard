package com.moon.apigateway.security;

import com.moon.apigateway.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtVerifierTest {

    private static final String SECRET = "d02f1779750841f8b4de65e47a913e89";
    private static final String ISSUER = "ai-storyboard-backend";

    private JwtVerifier verifier;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setAccessSecret(SECRET);
        config.setIssuer(ISSUER);
        verifier = new JwtVerifier(config);
        key = new SecretKeySpec(SECRET.getBytes(), "HmacSHA256");
    }

    private String accessToken(String subject, String issuer, String typ) {
        return Jwts.builder()
            .subject(subject)
            .issuer(issuer)
            .claim("typ", typ)
            .claim("role", "member")
            .signWith(key)
            .compact();
    }

    @Test
    void validAccessToken_parsesClaims() {
        Claims claims = verifier.verify(accessToken("u1", ISSUER, "access"));
        assertEquals("u1", claims.getSubject());
        assertEquals("member", claims.get("role", String.class));
    }

    @Test
    void wrongSignature_rejected() {
        SecretKey wrong = new SecretKeySpec("wrong-secret-key-1234567890abcdef".getBytes(), "HmacSHA256");
        String token = Jwts.builder().subject("u1").issuer(ISSUER).claim("typ", "access")
            .signWith(wrong).compact();
        assertThrows(Exception.class, () -> verifier.verify(token));
    }

    @Test
    void wrongTyp_rejected() {
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(accessToken("u1", ISSUER, "refresh")));
    }

    @Test
    void wrongIssuer_rejected() {
        assertThrows(Exception.class, () -> verifier.verify(accessToken("u1", "other-issuer", "access")));
    }

    @Test
    void expiredToken_rejected() {
        String token = Jwts.builder().subject("u1").issuer(ISSUER).claim("typ", "access")
            .expiration(new Date(System.currentTimeMillis() - 60_000)).signWith(key).compact();
        assertThrows(Exception.class, () -> verifier.verify(token));
    }
}
