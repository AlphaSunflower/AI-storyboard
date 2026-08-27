package com.moon.xiaoyuecanvas.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 鉴权过滤器：信任网关注入的 {@code X-User-Id} header（网关已完成 JWT 验签）。
 * {@code /api/canvas/health} 免鉴权（探活），其余端点要求 X-User-Id，否则 401。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        // CORS 预检放行
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        // /health 探活免鉴权（context-path /api/canvas，getRequestURI 含前缀）
        if (path.startsWith("/api/canvas/health")) {
            chain.doFilter(req, res);
            return;
        }

        // 信任网关注入的 header（Gateway JwtAuthenticationFilter 已验签）
        String userId = req.getHeader("X-User-Id");

        if (userId == null || userId.isBlank()) {
            res.setStatus(HttpStatus.UNAUTHORIZED.value());
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":40101,\"message\":\"未认证或凭证无效\",\"data\":null}");
            return;
        }

        req.setAttribute("userId", userId);
        chain.doFilter(req, res);
    }
}
