package com.moon.apigateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/** Redis-backed fixed-window limits for expensive and abuse-prone endpoints. */
@Component
public class RedisRateLimitFilter extends OncePerRequestFilter {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (redisTemplate == null) {
            chain.doFilter(request, response);
            return;
        }
        Rule rule = ruleFor(request.getRequestURI(), request.getMethod());
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }
        String subject = request.getHeader("X-User-Id");
        if (subject == null || subject.isBlank()) subject = request.getRemoteAddr();
        String key = "rate:" + rule.scope() + ":" + subject + ":" + (System.currentTimeMillis() / 60_000);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) redisTemplate.expire(key, Duration.ofMinutes(2));
        if (count != null && count > rule.limit()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":42901,\"message\":\"请求过于频繁，请稍后重试\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static Rule ruleFor(String path, String method) {
        if ("POST".equals(method) && "/api/auth/login".equals(path)) return new Rule("login", 10);
        if ("POST".equals(method) && path.endsWith("/turns/stream")) return new Rule("chat-turn", 20);
        if ("POST".equals(method) && ("/api/agent/upload".equals(path) || "/api/files/upload".equals(path))) {
            return new Rule("upload", 10);
        }
        if ("POST".equals(method) && path.startsWith("/api/ai/")) return new Rule("ai", 20);
        if ("POST".equals(method) && "/api/agent/prompt/optimize".equals(path)) {
            return new Rule("prompt-optimize", 20);
        }
        return null;
    }

    private record Rule(String scope, int limit) {
    }
}
