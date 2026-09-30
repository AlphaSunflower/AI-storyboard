package com.moon.apigateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT 鉴权过滤器 — Gateway 统一验签点。
 * 验签通过后将 userId/role/status 写入 header 传给下游服务。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtVerifier jwtVerifier;

    @Value("${internal.secret:}")
    private String internalSecret = "";

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /** 白名单路径（不需要 JWT） */
    private static final String[] PUBLIC_AUTH_PATHS = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/unlogin"
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        // 白名单放行
        String path = request.getRequestURI();
        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        // 内部 API 禁止外部访问
        if (path.startsWith("/api/internal/")) {
            writeError(response, HttpStatus.FORBIDDEN.value(), "40301", "内部接口不允许外部访问");
            return;
        }

        // 提取 Bearer token
        String header = request.getHeader("Authorization");
        String token = null;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        }
        if (token == null && path != null && path.startsWith("/api/files/")) {
            token = cookieValue(request, "media_token");
        }
        if (token == null && isSsePath(path)) {
            String ticket = request.getParameter("ticket");
            if (ticket != null && !ticket.isBlank() && consumeStreamTicket(ticket, path, request, response, chain)) {
                return;
            }
        }
        if (token == null) {
            writeError(response, HttpStatus.UNAUTHORIZED.value(), "40101", "未授权：缺少 token");
            return;
        }

        // 验签（仅验签失败返回 401；后续路由/转发异常须冒泡，不能吞）
        Claims claims;
        try {
            claims = jwtVerifier.verify(token);
        } catch (Exception e) {
            log.warn("JWT 验签失败: {}", e.getMessage());
            writeError(response, HttpStatus.UNAUTHORIZED.value(), "40101", "未授权：token 无效或已过期");
            return;
        }
        if ("disabled".equalsIgnoreCase(claims.get("status", String.class))) {
            writeError(response, HttpStatus.FORBIDDEN.value(), "40302", "账号已停用");
            return;
        }
        String sessionId = claims.get("sid", String.class);
        if (sessionId != null && !sessionId.isBlank()
                && redisTemplate != null
                && !Boolean.TRUE.equals(redisTemplate.hasKey("session:" + sessionId))) {
            writeError(response, HttpStatus.UNAUTHORIZED.value(), "40101", "会话已失效，请重新登录");
            return;
        }
        // 验签通过，写入下游 header（用 X-User-* 前缀避免与原始 Authorization 冲突）
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-User-Id", claims.getSubject());
        headers.put("X-User-Role", claims.get("role", String.class));
        headers.put("X-User-Status", claims.get("status", String.class));
        if (internalSecret != null && !internalSecret.isBlank()) {
            headers.put("X-Internal-Token", internalSecret);
        }
        HttpServletRequest wrapped = new HeaderWrapper(request,
                headers);
        chain.doFilter(wrapped, response);
    }

    private static boolean isSsePath(String path) {
        return path != null && path.endsWith("/events");
    }

    private static boolean isPublicPath(String path) {
        if (path == null) return false;
        if (path.startsWith("/actuator/")) return true;
        if (path.startsWith("/api/auth/health")) return true;
        // 资产服务会继续校验短时签名 token。
        if (path.startsWith("/api/library/media/")) return true;
        for (String publicPath : PUBLIC_AUTH_PATHS) {
            if (publicPath.equals(path)) return true;
        }
        return false;
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private boolean consumeStreamTicket(String ticket, String path, HttpServletRequest request,
                                        HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (redisTemplate == null) return false;
        String value = redisTemplate.opsForValue().getAndDelete("stream-ticket:" + ticket);
        if (value == null || value.isBlank()) return false;
        String[] parts = value.split("\\n", 2);
        if (parts.length != 2 || !path.equals(parts[1]) || parts[0].isBlank()) {
            writeError(response, HttpStatus.UNAUTHORIZED.value(), "40101", "流票据无效");
            return true;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-User-Id", parts[0]);
        headers.put("X-User-Role", "member");
        headers.put("X-User-Status", "enabled");
        if (internalSecret != null && !internalSecret.isBlank()) {
            headers.put("X-Internal-Token", internalSecret);
        }
        chain.doFilter(new HeaderWrapper(request, headers), response);
        return true;
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}");
    }
}
