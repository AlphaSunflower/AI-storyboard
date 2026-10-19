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

    /** 校验 `signIfLocal` 生成的签名；缩略图端点用 query 里的 path + token。 */
    public void verify(String path, String token) {
        if (path == null || path.isBlank() || !path.startsWith("/api/files/")) {
            throw new IllegalArgumentException("文件路径不合法");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("文件签名缺失");
        }
        String clean = stripSignature(path.trim());
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) throw new IllegalArgumentException("文件签名无效");
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("文件签名无效");
        }
        if (Instant.now().getEpochSecond() > expiresAt) {
            throw new IllegalArgumentException("文件签名已过期");
        }
        String expected = base64(hmac(clean + "\n" + expiresAt));
        if (!constantTimeEquals(expected, parts[1])) {
            throw new IllegalArgumentException("文件签名无效");
        }
    }

    private static boolean constantTimeEquals(String first, String second) {
        if (first.length() != second.length()) return false;
        int result = 0;
        for (int index = 0; index < first.length(); index += 1) {
            result |= first.charAt(index) ^ second.charAt(index);
        }
        return result == 0;
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
