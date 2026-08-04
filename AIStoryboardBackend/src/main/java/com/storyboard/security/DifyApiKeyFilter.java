package com.storyboard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Dify Agent API Key 认证过滤器
 * 检查 X-Dify-Key header 是否匹配配置的密钥
 */
public class DifyApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DifyApiKeyFilter.class);

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
            // 401 静默拒绝排查困难：Dify 工作流回调失败时没有日志可查。
            // 打 warn 日志（不打印 key 明文），方便定位"工作流 env.DIFY_KEY 未配置/不匹配"类问题
            log.warn("Dify 回调认证失败: path={}, X-Dify-Key={} (期望值已配置，可能不匹配)", request.getRequestURI(), difyKey == null ? "<缺失>" : "<存在但不匹配>");
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
