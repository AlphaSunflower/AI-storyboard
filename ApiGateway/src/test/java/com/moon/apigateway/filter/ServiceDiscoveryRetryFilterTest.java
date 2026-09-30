package com.moon.apigateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceDiscoveryRetryFilterTest {

    @Test
    void retriesOnlyTheDiscoveryGap() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        FilterChain chain = (request, response) -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ServletException(
                        "503 Unable to find instance for moon-agent");
            }
        };
        ServiceDiscoveryRetryFilter filter =
                new ServiceDiscoveryRetryFilter(4, Duration.ZERO);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(attempts).hasValue(3);
    }

    @Test
    void doesNotReplayApplicationFailures() {
        AtomicInteger attempts = new AtomicInteger();
        ServiceDiscoveryRetryFilter filter =
                new ServiceDiscoveryRetryFilter(4, Duration.ZERO);
        FilterChain chain = (request, response) -> {
            attempts.incrementAndGet();
            throw new ServletException("model request failed");
        };

        assertThatThrownBy(() -> filter.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .hasMessageContaining("model request failed");

        assertThat(attempts).hasValue(1);
    }

    @Test
    void retriesDiscoveryRuntimeFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ServiceDiscoveryRetryFilter filter =
                new ServiceDiscoveryRetryFilter(4, Duration.ZERO);
        FilterChain chain = (request, response) -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException(
                        "503 Unable to find instance for moon-agent");
            }
        };

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(attempts).hasValue(3);
    }

    @Test
    void doesNotRetryCommittedResponse() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ServiceDiscoveryRetryFilter filter =
                new ServiceDiscoveryRetryFilter(4, Duration.ZERO);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (request, ignoredResponse) -> {
            attempts.incrementAndGet();
            response.setCommitted(true);
            throw new ServletException(
                    "503 Unable to find instance for moon-agent");
        };

        assertThatThrownBy(() -> filter.doFilter(
                new MockHttpServletRequest(), response, chain))
                .hasMessageContaining("Unable to find instance for moon-agent");

        assertThat(attempts).hasValue(1);
    }
}
