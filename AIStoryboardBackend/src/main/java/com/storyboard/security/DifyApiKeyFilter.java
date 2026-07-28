package com.storyboard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Dify Agent API Key 认证过滤器
 * 检查 X-Dify-Key header 是否匹配配置的密钥
 */
public class DifyApiKeyFilter extends OncePerRequestFilter {

    private final String expectedKey;

    public DifyApiKeyFilter(String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String difyKey = request.getHeader("X-Dify-Key");
        if (difyKey == null || !difyKey.equals(expectedKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":40101,\"message\":\"Dify API Key 无效\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅拦截 /api/ai/dify/ 路径
        String path = request.getServletPath();
        return !path.startsWith("/api/ai/dify/");
    }
}
