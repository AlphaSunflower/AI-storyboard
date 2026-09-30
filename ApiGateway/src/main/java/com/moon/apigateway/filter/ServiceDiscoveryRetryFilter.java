package com.moon.apigateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Absorbs the short window between a service instance unregistering and its
 * replacement registering. Only discovery failures are retried; once a request
 * reaches an application, its failures are returned without replay.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ServiceDiscoveryRetryFilter extends OncePerRequestFilter {

    private final int maxAttempts;
    private final Duration delay;

    public ServiceDiscoveryRetryFilter() {
        this(8, Duration.ofSeconds(1));
    }

    ServiceDiscoveryRetryFilter(int maxAttempts, Duration delay) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.delay = delay == null || delay.isNegative() ? Duration.ZERO : delay;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        IOException ioFailure = null;
        ServletException servletFailure = null;
        RuntimeException runtimeFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                chain.doFilter(request, response);
                return;
            } catch (IOException error) {
                if (!shouldRetry(response, error, attempt)) throw error;
                ioFailure = error;
            } catch (ServletException error) {
                if (!shouldRetry(response, error, attempt)) throw error;
                servletFailure = error;
            } catch (RuntimeException error) {
                if (!shouldRetry(response, error, attempt)) throw error;
                runtimeFailure = error;
            }
            sleep();
        }
        if (ioFailure != null) throw ioFailure;
        if (servletFailure != null) throw servletFailure;
        if (runtimeFailure != null) throw runtimeFailure;
    }

    private void sleep() {
        if (delay.isZero()) return;
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static boolean discoveryUnavailable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("Unable to find instance for")
                    || message.contains("No servers available for service"))) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private boolean shouldRetry(HttpServletResponse response, Throwable error, int attempt) {
        return !response.isCommitted()
                && attempt < maxAttempts
                && discoveryUnavailable(error);
    }
}
