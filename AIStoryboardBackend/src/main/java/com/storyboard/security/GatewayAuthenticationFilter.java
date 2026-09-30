package com.storyboard.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.storyboard.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * 网关鉴权过滤器 — 优先读 Gateway 传入的 X-User-Id header，无 header 则回退到 JWT 验签。
 * <p>
 * 微服务架构下 Gateway 统一验签后写入 X-User-Id/X-User-Role/X-User-Status，
 * 后端服务不再重复验 JWT；直连场景（开发/测试）保留 JWT 回退。
 */
@Component
public class GatewayAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Value("${internal.secret:}")
    private String internalSecret = "";

    public GatewayAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        // 1. 优先读 Gateway 传入的 header
        String userId = request.getHeader("X-User-Id");
        String role = request.getHeader("X-User-Role");
        String status = request.getHeader("X-User-Status");

        if (userId != null && !userId.isBlank()) {
            if (!trustedInternalRequest(request.getHeader("X-Internal-Token"))) {
                writeError(response);
                return;
            }
            if ("disabled".equalsIgnoreCase(status)) {
                writeError(response);
                return;
            }
            // Gateway 已验签，且内部令牌已确认，直接用
            setAuth(userId, role != null ? role : "member", status != null ? status : "enabled");
            chain.doFilter(request, response);
            return;
        }

        // 业务服务只接受 Gateway 注入的可信身份头；直连 JWT 无法校验 Redis 会话撤销。
        chain.doFilter(request, response);
    }

    private void setAuth(String userId, String role, String status) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
        var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
        auth.setDetails(Map.of("role", role, "status", status));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void writeError(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(40101, "未授权")));
    }

    private boolean trustedInternalRequest(String suppliedToken) {
        if (internalSecret == null || internalSecret.isBlank()) return false;
        if (suppliedToken == null || suppliedToken.isBlank()) return false;
        return MessageDigest.isEqual(
                suppliedToken.getBytes(StandardCharsets.UTF_8),
                internalSecret.getBytes(StandardCharsets.UTF_8));
    }
}
