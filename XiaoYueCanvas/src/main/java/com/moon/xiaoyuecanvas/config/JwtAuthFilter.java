package com.moon.xiaoyuecanvas.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JWT 鉴权过滤器：解析 {@code Authorization: Bearer <jwt>}，校验签名后把 userId 放入 request attribute。
 * {@code /health} 免鉴权（探活），其余端点要求有效 JWT，否则 401。
 *
 * <p>复用 ai分镜/MoonAgent 的交叉系统 JWT（HS256，secret 同源）；claim 取 {@code userId}（无则回退 subject）。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final SecretKey key;

    public JwtAuthFilter(@Value("${auth.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        // CORS 预检放行（预检不含 Authorization，不得走鉴权）
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        // /health 探活、/api/auth/** 登录（代理到 ai分镜）免鉴权；其余端点要求有效 JWT
        if (path.startsWith("/health") || path.startsWith("/api/auth")) {
            chain.doFilter(req, res);
            return;
        }

        String header = req.getHeader("Authorization");
        String token = null;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        }
        // EventSource 无法带 Authorization 头——SSE 订阅端点用查询参数 access_token 兜底
        if (token == null) {
            token = req.getParameter("access_token");
        }
        String userId = null;
        if (token != null) {
            try {
                Claims claims = Jwts.parser().verifyWith(key).build()
                        .parseSignedClaims(token).getPayload();
                userId = claims.get("userId", String.class);
                if (userId == null) userId = claims.getSubject();
            } catch (Exception ignored) {
                // 签名/过期异常 → 视为未认证
            }
        }

        if (userId == null) {
            res.setStatus(HttpStatus.UNAUTHORIZED.value());
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":40101,\"message\":\"未认证或凭证无效\",\"data\":null}");
            return;
        }

        req.setAttribute("userId", userId);
        chain.doFilter(req, res);
    }
}
