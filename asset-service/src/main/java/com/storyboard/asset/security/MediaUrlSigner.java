package com.storyboard.asset.security;

import com.storyboard.asset.config.AssetProperties;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** 媒体短链签名，避免公开文件名和不可控直链。 */
@Component
public class MediaUrlSigner {

    private final byte[] secret;

    public MediaUrlSigner(AssetProperties properties) {
        if (properties.signingSecret() == null || properties.signingSecret().isBlank()) {
            throw new IllegalStateException(
                    "媒体签名密钥未配置：请设置 ASSET_SIGNING_SECRET");
        }
        this.secret = properties.signingSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String mediaId, String userId, Instant expiresAt) {
        long epoch = expiresAt.getEpochSecond();
        String payload = mediaId + "\n" + userId + "\n" + epoch;
        return epoch + "." + base64(hmac(payload));
    }

    public void verify(String mediaId, String userId, String token) {
        if (token == null || token.isBlank()) throw new BusinessException(40301, "媒体访问签名缺失");
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) throw new BusinessException(40301, "媒体访问签名无效");
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            throw new BusinessException(40301, "媒体访问签名无效");
        }
        if (Instant.now().getEpochSecond() > expiresAt) throw new BusinessException(40301, "媒体访问签名已过期");
        String expected = base64(hmac(mediaId + "\n" + userId + "\n" + expiresAt));
        if (!constantTimeEquals(expected, parts[1])) throw new BusinessException(40301, "媒体访问签名无效");
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("媒体签名失败", e);
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) result |= a.charAt(i) ^ b.charAt(i);
        return result == 0;
    }
}
