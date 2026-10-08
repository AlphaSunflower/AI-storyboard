package com.moon.moonagent.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 与主后端 /api/files/** 共用同一 HMAC 契约，供历史 agent_assets 响应重新签发。 */
@Component
public class FileUrlSigner {

    private static final Pattern LOCAL_FILE_URL = Pattern.compile("/api/files/[^\\s\"'<>\\]\\)\\}]+");
    private static final String TRAILING_PUNCTUATION = ".,;:!?，。；：！？、";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final byte[] secret;
    private final long ttlSeconds;

    public FileUrlSigner(
            @Value("${files.signing-secret:${storyboard.internal-secret:${storyboard.internal.secret:${internal.secret:}}}}")
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

    /** 重签 result_urls JSON 数组；兼容字符串数组和历史 {url: ...} 对象数组。 */
    public String signJsonArray(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) return json;
            ArrayNode signed = objectMapper.createArrayNode();
            for (JsonNode item : root) {
                if (item.isTextual()) {
                    signed.add(signIfLocal(item.asText()));
                } else if (item.isObject()) {
                    ObjectNode copy = item.deepCopy();
                    String url = copy.path("url").asText("");
                    if (!url.isBlank()) copy.put("url", signIfLocal(url));
                    signed.add(copy);
                } else {
                    signed.add(item);
                }
            }
            return objectMapper.writeValueAsString(signed);
        } catch (Exception ignored) {
            return json;
        }
    }

    /** 重签消息正文中的本地文件 URL（Markdown 图片、视频链接、裸路径等）。 */
    public String signEmbeddedUrls(String text) {
        if (text == null || text.isBlank() || !text.contains("/api/files/")) return text;
        Matcher matcher = LOCAL_FILE_URL.matcher(text);
        StringBuffer signedText = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group();
            int pathEnd = raw.length();
            while (pathEnd > 0 && TRAILING_PUNCTUATION.indexOf(raw.charAt(pathEnd - 1)) >= 0) {
                pathEnd--;
            }
            String path = raw.substring(0, pathEnd);
            String trailing = raw.substring(pathEnd);
            matcher.appendReplacement(signedText,
                    Matcher.quoteReplacement(signIfLocal(path) + trailing));
        }
        matcher.appendTail(signedText);
        return signedText.toString();
    }

    /** 递归重签消息 metadata 中任意层级的本地文件 URL。 */
    public Map<String, Object> signMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return metadata;
        JsonNode root = objectMapper.valueToTree(metadata);
        signJsonUrls(root);
        return objectMapper.convertValue(root, new TypeReference<Map<String, Object>>() {});
    }

    private void signJsonUrls(JsonNode node) {
        if (node == null) return;
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode item = array.get(i);
                if (item.isTextual()) {
                    array.set(i, objectMapper.getNodeFactory().textNode(signEmbeddedUrls(item.asText())));
                } else {
                    signJsonUrls(item);
                }
            }
            return;
        }
        if (!node.isObject()) return;
        ObjectNode object = (ObjectNode) node;
        List<String> fieldNames = new ArrayList<>();
        object.fieldNames().forEachRemaining(fieldNames::add);
        for (String field : fieldNames) {
            JsonNode value = object.get(field);
            if (value != null && value.isTextual()) {
                object.put(field, signEmbeddedUrls(value.asText()));
            } else {
                signJsonUrls(value);
            }
        }
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
