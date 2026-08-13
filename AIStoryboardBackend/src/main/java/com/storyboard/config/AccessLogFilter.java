package com.storyboard.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 请求访问日志 Filter：每个请求一行，输出 method/path/status/耗时/用户，
 * 落到独立 logs/access.log（logback-spring.xml 中 ACCESS_LOG logger）。
 * 只记 REQUEST 分发：SseEmitter 等异步 asyncDispatch 会二次经过过滤器链，跳过避免重复记录。
 */
@Component
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("ACCESS_LOG");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            chain.doFilter(request, response);
            return;
        }
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String qs = request.getQueryString();
            log.info("{} [user={}] {} {}{} {} {}ms",
                    LocalDateTime.now().format(FMT), currentUser(), request.getMethod(),
                    request.getRequestURI(), qs == null ? "" : "?" + qs,
                    response.getStatus(), ms);
        }
    }

    /** 已认证用户 id（JwtAuthenticationFilter 写入）；匿名/未认证为 "-" */
    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return "-";
        }
        return auth.getName();
    }
}
