package com.llmgateway.security;

import com.llmgateway.config.GatewayConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** JWT 签发与校验（管理后台用；role 进 claims） */
@Component
public class JwtTokenProvider {

    private final GatewayConfig config;
    private final SecretKey accessKey;
    private final SecretKey refreshKey;

    public JwtTokenProvider(GatewayConfig config) {
        this.config = config;
        if (config.getJwt().getAccessSecret() == null || config.getJwt().getRefreshSecret() == null
                || config.getJwt().getAccessSecret().isBlank() || config.getJwt().getRefreshSecret().isBlank()) {
            throw new IllegalStateException("JWT 密钥未配置：请在 .env 设置 LLM_GATEWAY_JWT_ACCESS_SECRET / LLM_GATEWAY_JWT_REFRESH_SECRET");
        }
        this.accessKey = Keys.hmacShaKeyFor(config.getJwt().getAccessSecret().getBytes(StandardCharsets.UTF_8));
        this.refreshKey = Keys.hmacShaKeyFor(config.getJwt().getRefreshSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** 签发 access token（claims：username/role/type=access） */
    public String createAccessToken(String username, String role) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .claim("type", "access")
                .issuer(config.getJwt().getIssuer())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + config.getJwt().getAccessTokenTtl() * 1000L))
                .signWith(accessKey)
                .compact();
    }

    /** 签发 refresh token */
    public String createRefreshToken(String username) {
        return Jwts.builder()
                .subject(username)
                .claim("type", "refresh")
                .issuer(config.getJwt().getIssuer())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + config.getJwt().getRefreshTokenTtl() * 1000L))
                .signWith(refreshKey)
                .compact();
    }

    /** 校验 access token，返回 claims；失败抛异常 */
    public Claims parseAccessToken(String token) {
        return Jwts.parser().verifyWith(accessKey).build().parseSignedClaims(token).getPayload();
    }

    /** 校验 refresh token，返回 claims；失败抛异常 */
    public Claims parseRefreshToken(String token) {
        return Jwts.parser().verifyWith(refreshKey).build().parseSignedClaims(token).getPayload();
    }
}
