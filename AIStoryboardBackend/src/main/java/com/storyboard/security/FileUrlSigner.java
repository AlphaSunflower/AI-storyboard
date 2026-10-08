package com.storyboard.security;

import com.storyboard.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;

/** 文件对象级 HMAC 签名，避免使用全局 media_token 读取任意对象。 */
@Component
public class FileUrlSigner {

    private final byte[] secret;
    private final long ttlSeconds;

    public FileUrlSigner(
            @Value("${files.signing-secret:${internal.secret:}}") String secretValue,
            @Value("${files.signature-ttl-seconds:2592000}") long ttlSeconds
    ) {
        if (secretValue == null || secretValue.isBlank()) {
            throw new IllegalStateException(
                    "文件签名密钥未配置：请设置 FILE_SIGNING_SECRET 或 INTERNAL_SECRET");
        }
        this.secret = secretValue.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = Math.max(60, ttlSeconds);
    }

    public String signPath(String path) {
        String normalized = stripSignature(path);
        long expiresAt = Instant.now().plusSeconds(ttlSeconds).getEpochSecond();
        String separator = normalized.contains("?") ? "&" : "?";
        return normalized + separator + "token=" + expiresAt + "." + base64(hmac(normalized + "\n" + expiresAt));
    }

    /** 本地文件路径读取时重签；外部 URL/data URI 原样返回。 */
    public String signIfLocal(String path) {
        if (path == null || path.isBlank()) return path;
        String clean = path.trim();
        return clean.startsWith("/api/files/") ? signPath(clean) : path;
    }

    /** 重签逗号分隔的本地文件 URL 列表（历史 scene.image_urls 格式）。 */
    public String signCommaSeparated(String paths) {
        if (paths == null || paths.isBlank()) return paths;
        return Arrays.stream(paths.split(","))
                .map(String::trim)
                .filter(path -> !path.isBlank())
                .map(this::signIfLocal)
                .collect(Collectors.joining(","));
    }

    public void verifyPath(String path, String token) {
        if (token == null || token.isBlank()) throw new BusinessException(40301, "文件访问签名缺失");
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) throw new BusinessException(40301, "文件访问签名无效");
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            throw new BusinessException(40301, "文件访问签名无效");
        }
        if (Instant.now().getEpochSecond() > expiresAt) {
            throw new BusinessException(40301, "文件访问签名已过期");
        }
        String expected = base64(hmac(stripSignature(path) + "\n" + expiresAt));
        if (!constantTimeEquals(expected, parts[1])) {
            throw new BusinessException(40301, "文件访问签名无效");
        }
    }

    private static String stripSignature(String path) {
        if (path == null) return "";
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

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) result |= a.charAt(i) ^ b.charAt(i);
        return result == 0;
    }
}
