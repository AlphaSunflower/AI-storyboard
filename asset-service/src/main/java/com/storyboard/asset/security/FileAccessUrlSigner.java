package com.storyboard.asset.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** 与主后端 /api/files/** 共用的对象路径签名，用于历史 cover_url 等兼容字段。 */
@Component
public class FileAccessUrlSigner {

    private final byte[] secret;
    private final long ttlSeconds;

    public FileAccessUrlSigner(
            @Value("${files.signing-secret:${storyboard.internal.secret:${storyboard.asset.internal-token:}}}")
            String secretValue,
            @Value("${files.signature-ttl-seconds:2592000}") long ttlSeconds
    ) {
        if (secretValue == null || secretValue.isBlank()) {
            throw new IllegalStateException("文件签名密钥未配置：请设置 FILE_SIGNING_SECRET 或 INTERNAL_SECRET");
        }
        this.secret = secretValue.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = Math.max(60, ttlSeconds);
    }

    public String signIfLocal(String path) {
        if (path == null || path.isBlank()) return path;
        String clean = stripSignature(path.trim());
        if (!clean.startsWith("/api/files/")) return path;
        long expiresAt = Instant.now().plusSeconds(ttlSeconds).getEpochSecond();
        return clean + "?token=" + expiresAt + "." + base64(hmac(clean + "\n" + expiresAt));
    }

    private static String stripSignature(String path) {
        int query = path.indexOf('?');
        return query >= 0 ? path.substring(0, query) : path;
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("文件签名失败", e);
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
