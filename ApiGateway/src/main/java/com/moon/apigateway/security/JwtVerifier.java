package com.moon.apigateway.security;

import com.moon.apigateway.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** JWT 验签器 — 只做验签+解析，不签发 token（签发在主后端） */
@Component
public class JwtVerifier {

    private final SecretKey accessKey;
    private final String issuer;

    public JwtVerifier(JwtConfig config) {
        this.accessKey = new SecretKeySpec(config.getAccessSecret().getBytes(), "HmacSHA256");
        this.issuer = config.getIssuer();
    }

    /**
     * 验签并解析 access token
     * @return claims（含 subject=userId, role, status）
     * @throws io.jsonwebtoken.JwtException token 无效/过期
     */
    public Claims verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(accessKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        // 校验 typ 必须是 access
        String typ = claims.get("typ", String.class);
        if (!"access".equals(typ)) {
            throw new IllegalArgumentException("TOKEN_TYPE_INVALID");
        }
        return claims;
    }
}
