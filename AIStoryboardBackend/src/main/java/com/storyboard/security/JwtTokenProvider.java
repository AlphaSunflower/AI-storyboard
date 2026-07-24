package com.storyboard.security;

import com.storyboard.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey accessKey;
    private final SecretKey refreshKey;
    private final String issuer;
    private final long accessTokenTtl;
    private final long refreshTokenTtl;

    @Autowired
    public JwtTokenProvider(JwtConfig config) {

        this.accessKey = new SecretKeySpec(config.getAccessSecret().getBytes(), "HmacSHA256");
        this.refreshKey = new SecretKeySpec(config.getRefreshSecret().getBytes(), "HmacSHA256");
        this.issuer = config.getIssuer();
        this.accessTokenTtl = config.getAccessTokenTtl();
        this.refreshTokenTtl = config.getRefreshTokenTtl();
    }

    /**
     * Test constructor with full TTL control.
     */
    JwtTokenProvider(String accessSecret, String refreshSecret, String issuer,
                     long accessTokenTtl, long refreshTokenTtl) {
        this.accessKey = new SecretKeySpec(accessSecret.getBytes(), "HmacSHA256");
        this.refreshKey = new SecretKeySpec(refreshSecret.getBytes(), "HmacSHA256");
        this.issuer = issuer;
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    /**
     * Test convenience constructor with default TTL values.
     */
    JwtTokenProvider(String accessSecret, String refreshSecret, String issuer) {
        this(accessSecret, refreshSecret, issuer, 3600, 2592000);
    }

    // ==================== Signing ====================

    /**
     * Sign an access token with role and status claims.
     * Compatible with Node.js HS256 JWT format.
     */
    public String signAccessToken(String userId, String role, String status) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        return Jwts.builder()
                .header().add("alg", "HS256").add("typ", "JWT").and()
                .claim("typ", "access")
                .issuer(issuer)
                .subject(userId)
                .claim("role", role)
                .claim("status", status)
                .issuedAt(new Date(nowSeconds * 1000))
                .expiration(new Date((nowSeconds + accessTokenTtl) * 1000))
                .id(UUID.randomUUID().toString())
                .signWith(accessKey)
                .compact();
    }

    /**
     * Sign a refresh token (no role/status claims).
     * Compatible with Node.js HS256 JWT format.
     */
    public String signRefreshToken(String userId) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        return Jwts.builder()
                .header().add("alg", "HS256").add("typ", "JWT").and()
                .claim("typ", "refresh")
                .issuer(issuer)
                .subject(userId)
                .issuedAt(new Date(nowSeconds * 1000))
                .expiration(new Date((nowSeconds + refreshTokenTtl) * 1000))
                .id(UUID.randomUUID().toString())
                .signWith(refreshKey)
                .compact();
    }

    // ==================== Verification ====================

    /**
     * Verify an access token and validate its claims.
     *
     * @return parsed claims
     * @throws io.jsonwebtoken.JwtException if the token is invalid, expired, or malformed
     * @throws IllegalArgumentException    if typ / role / status claims are invalid
     */
    public Claims verifyAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(accessKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        validateAccessClaims(claims);
        return claims;
    }

    /**
     * Verify a refresh token and validate its typ claim.
     *
     * @return parsed claims
     * @throws io.jsonwebtoken.JwtException if the token is invalid, expired, or malformed
     * @throws IllegalArgumentException    if the typ claim is not "refresh"
     */
    public Claims verifyRefreshToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(refreshKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String typ = claims.get("typ", String.class);
        if (!"refresh".equals(typ)) {
            throw new IllegalArgumentException("TOKEN_TYPE_INVALID");
        }
        return claims;
    }

    // ==================== Validation ====================

    private void validateAccessClaims(Claims claims) {
        String typ = claims.get("typ", String.class);
        if (!"access".equals(typ)) {
            throw new IllegalArgumentException("TOKEN_TYPE_INVALID");
        }
        String role = claims.get("role", String.class);
        if (!"member".equals(role) && !"admin".equals(role)) {
            throw new IllegalArgumentException("TOKEN_ROLE_INVALID");
        }
        String status = claims.get("status", String.class);
        if (!"enabled".equals(status) && !"disabled".equals(status)) {
            throw new IllegalArgumentException("TOKEN_STATUS_INVALID");
        }
    }
}
