package com.moon.xiaoyuecanvas.bridge;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器画布信箱端点（原 canvas-bridge 的 HTTP 面）。
 *
 * <p>身份一律来自网关注入的 {@code X-User-Id}（{@code JwtAuthFilter} 已强制），
 * 因此浏览器走同源 {@code /api/canvas/bridge/**}，不再需要独立端口、CORS 白名单或共享桥接令牌。
 */
@RestController
@RequestMapping("/bridge")
@RequiredArgsConstructor
public class CanvasBridgeController {

    private final CanvasBrowserMailbox mailbox;

    @PostMapping("/register")
    public Map<String, Object> register(@RequestAttribute("userId") String userId,
                                        @RequestBody Map<String, Object> body) {
        return mailbox.register(
                userId,
                text(body.get("tabId")),
                text(body.get("projectId")),
                text(body.get("projectTitle")),
                text(body.get("canvasId")),
                text(body.get("canvasTitle")));
    }

    @PostMapping("/unregister")
    public Map<String, Object> unregister(@RequestAttribute("userId") String userId,
                                          @RequestBody(required = false) Map<String, Object> body) {
        mailbox.unregister(userId, body == null ? null : text(body.get("tabId")));
        return Map.of("ok", true);
    }

    @GetMapping("/poll")
    public Map<String, Object> poll(@RequestAttribute("userId") String userId,
                                    @RequestParam("tabId") String tabId) {
        return mailbox.poll(userId, tabId);
    }

    @PostMapping("/result")
    public Map<String, Object> result(@RequestAttribute("userId") String userId,
                                      @RequestBody Map<String, Object> body) {
        String requestId = text(body.get("requestId"));
        mailbox.result(userId, requestId, normalizeResponse(requestId, body.get("response")));
        return Map.of("ok", true);
    }

    @GetMapping("/health")
    public Map<String, Object> health(@RequestAttribute("userId") String userId) {
        return mailbox.health(userId);
    }

    /** 浏览器回传的响应体原样透传，只补全 id，避免调用方拿到不一致的标识。 */
    private static Map<String, Object> normalizeResponse(String requestId, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return CanvasBrowserMailbox.failure("INVALID_RESPONSE", "浏览器回传结果格式不正确");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        out.put("id", requestId);
        out.putIfAbsent("version", CanvasBrowserMailbox.PROTOCOL_VERSION);
        out.putIfAbsent("ok", false);
        return out;
    }

    private static String text(Object value) {
        return value instanceof String string ? string : null;
    }
}
