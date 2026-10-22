package com.llmgateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 校验主平台签发的「网关令牌」（{@code typ=gateway}）。
 *
 * <p>桌面客户端不再内置共享的 {@code LLM_GATEWAY_API_KEY}——那把厂商额度直接发给了所有人。
 * 登录时主后端用平台 JWT 密钥签一个 30 天的网关令牌，客户端持有它调 {@code /v1/**}；
 * 网关用同一密钥验签，拿到 userId 做归属与限流。
 *
 * <p>密钥通过 {@code PLATFORM_JWT_ACCESS_SECRET} 注入，必须与主后端 {@code JWT_ACCESS_SECRET} 一致。
 * 未配置时该通道关闭，仅保留原有静态 Key 校验（不影响云端既有部署）。
 */
@Component
public class PlatformTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(PlatformTokenVerifier.class);

    private final SecretKey key;

    public PlatformTokenVerifier(@Value("${platform.jwt.access-secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            this.key = null;
            log.info("[platform-token] 未配置 platform.jwt.access-secret，网关令牌通道关闭");
        } else {
            this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            log.info("[platform-token] 网关令牌通道已启用");
        }
    }

    public boolean enabled() {
        return key != null;
    }

    /**
     * @return 令牌主体（userId）；令牌非法/过期/类型不符时返回 null
     */
    public String verify(String token) {
        if (key == null || token == null || token.isBlank()) return null;
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!"gateway".equals(claims.get("typ", String.class))) {
                return null;
            }
            String userId = claims.getSubject();
            return userId == null || userId.isBlank() ? null : userId;
        } catch (Exception error) {
            log.debug("[platform-token] 网关令牌校验失败: {}", error.getMessage());
            return null;
        }
    }
}
