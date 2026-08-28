package com.storyboard.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 请求访问日志 Filter：每个请求一行，输出 method/path/status/耗时/用户 + 链路 traceId，
 * 落到独立 logs/access.log（logback-spring.xml 中 ACCESS_LOG logger）。
 * 只记 REQUEST 分发：SseEmitter 等异步 asyncDispatch 会二次经过过滤器链，跳过避免重复记录。
 *
 * 链路 traceId：进入时生成本请求关联 ID（优先透传上游 X-Request-Id）写入 MDC(key=traceId)，
 * 使本请求内所有服务/控制器日志经 logback 的 %X{traceId} 自动带上同一 ID；finally 清理防线程复用污染。
 * ponytail: MDC 只覆盖同步请求线程；SSE 虚拟线程不传播，如跨线程追踪需引 sleuth/zipkin。
 */
@Component
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("ACCESS_LOG");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /** 请求关联 ID：透传/生成后回写到响应头，前端可回显，也便于网关侧按同一 ID 串联 */
    private static final String TRACE_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            chain.doFilter(request, response);
            return;
        }
        String traceId = resolveTraceId(request);
        MDC.put("traceId", traceId);
        response.setHeader(TRACE_HEADER, traceId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String qs = request.getQueryString();
            log.info("{} [user={}] {} {}{} {} {}ms traceId={}",
                    LocalDateTime.now().format(FMT), currentUser(), request.getMethod(),
                    request.getRequestURI(), qs == null ? "" : "?" + qs,
                    response.getStatus(), ms, traceId);
            MDC.remove("traceId");
        }
    }

    /** 生成/透传请求关联 ID：上游已带 X-Request-Id 则复用（跨服务串联），否则生成 16 位随机 ID */
    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(TRACE_HEADER);
        if (incoming != null && !incoming.isBlank()) {
            return incoming.length() > 64 ? incoming.substring(0, 64) : incoming;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return "-";
        }
        return auth.getName();
    }
}
