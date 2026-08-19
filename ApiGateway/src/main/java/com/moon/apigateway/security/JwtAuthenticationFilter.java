package com.moon.apigateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
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

    /** 白名单路径（不需要 JWT） */
    private static final String[] WHITELIST = {
            "/api/auth/",
            "/api/files/",
            "/actuator/"
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        // 白名单放行
        String path = request.getRequestURI();
        for (String prefix : WHITELIST) {
            if (path.startsWith(prefix)) {
                chain.doFilter(request, response);
                return;
            }
        }

        // 内部 API 禁止外部访问
        if (path.startsWith("/api/internal/")) {
            writeError(response, HttpStatus.FORBIDDEN.value(), "40301", "内部接口不允许外部访问");
            return;
        }

        // 提取 Bearer token
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeError(response, HttpStatus.UNAUTHORIZED.value(), "40101", "未授权：缺少 token");
            return;
        }

        // 验签（仅验签失败返回 401；后续路由/转发异常须冒泡，不能吞）
        Claims claims;
        try {
            claims = jwtVerifier.verify(header.substring(7));
        } catch (Exception e) {
            log.warn("JWT 验签失败: {}", e.getMessage());
            writeError(response, HttpStatus.UNAUTHORIZED.value(), "40101", "未授权：token 无效或已过期");
            return;
        }
        // 验签通过，写入下游 header（用 X-User-* 前缀避免与原始 Authorization 冲突）
        HttpServletRequest wrapped = new HeaderWrapper(request,
                Map.of(
                        "X-User-Id", claims.getSubject(),
                        "X-User-Role", claims.get("role", String.class),
                        "X-User-Status", claims.get("status", String.class)
                ));
        chain.doFilter(wrapped, response);
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}");
    }
}
