package com.moon.moonagent.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 网关鉴权过滤器 — 读 Gateway 传入的 X-User-Id header。
 * Agent 服务不持有 JWT，完全信任 Gateway 验签结果。
 *
 * 继承 GenericFilterBean（非 OncePerRequestFilter），确保 Tomcat 异步派发（SSE 完成/error 页面）
 * 时也能重新设置 SecurityContext——OncePerRequestFilter 在 async dispatch 时默认跳过，
 * 导致 SecurityContext 为空 → AuthorizationFilter 拒绝。
 */
@Component
public class GatewayAuthenticationFilter implements Filter {

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain chain) throws ServletException, IOException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        String userId = request.getHeader("X-User-Id");
        String role = request.getHeader("X-User-Role");
        String status = request.getHeader("X-User-Status");

        if (userId != null && !userId.isBlank()) {
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + (role != null ? role : "member").toUpperCase()));
            var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            auth.setDetails(Map.of("role", role != null ? role : "member", "status", status != null ? status : "enabled"));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(servletRequest, servletResponse);
    }
}
