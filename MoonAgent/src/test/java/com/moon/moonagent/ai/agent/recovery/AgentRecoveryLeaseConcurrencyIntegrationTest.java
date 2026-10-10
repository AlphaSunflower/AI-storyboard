package com.moon.moonagent.ai.agent.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = AgentRecoveryLeaseConcurrencyIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.web-application-type=none")
class AgentRecoveryLeaseConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AgentRecoveryLeaseService leases;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS public.agent_recovery_leases (
                    resource_type VARCHAR(64) NOT NULL,
                    resource_id VARCHAR(255) NOT NULL,
                    lease_owner VARCHAR(128) NOT NULL,
                    lease_until TIMESTAMPTZ NOT NULL,
                    attempt INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    last_error TEXT,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (resource_type, resource_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS public.agent_recovery_test_results (
                    id SERIAL PRIMARY KEY,
                    resource_id TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.update("DELETE FROM public.agent_recovery_leases");
        jdbc.update("DELETE FROM public.agent_recovery_test_results");
    }

    @Test
    void twoCoordinatorsExecuteTheSameResourceOnlyOnce() throws Exception {
        AgentRecoveryProperties properties = new AgentRecoveryProperties();
        properties.setMode(RecoveryMode.COORDINATOR);
        properties.setMaxParallel(1);
        properties.setLeaseDuration(Duration.ofSeconds(5));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AtomicInteger recoveries = new AtomicInteger();
        CountDownLatch recoveryEntered = new CountDownLatch(1);
        CountDownLatch allowRecovery = new CountDownLatch(1);
        CountDownLatch recoveryDone = new CountDownLatch(1);
        RecoveryCandidate candidate =
                new RecoveryCandidate("integration-test", "resource-1", "conversation-1", Map.of());
        AgentRecoverySource source = new AgentRecoverySource() {
            @Override
            public String name() {
                return "integration-test";
            }

            @Override
            public Duration interval() {
                return Duration.ZERO;
            }

            @Override
            public int batchSize() {
                return 1;
            }

            @Override
            public List<RecoveryCandidate> findCandidates(OffsetDateTime now, int limit) {
                return List.of(candidate);
            }

            @Override
            public RecoveryOutcome recover(RecoveryCandidate ignored, int attempt,
                                           String leaseOwner) {
                try {
                    recoveries.incrementAndGet();
                    recoveryEntered.countDown();
                    await(allowRecovery);
                    jdbc.update("INSERT INTO public.agent_recovery_test_results(resource_id) VALUES (?)",
                            ignored.resourceId());
                    return RecoveryOutcome.completed();
                } finally {
                    recoveryDone.countDown();
                }
            }
        };
        AgentRecoveryCoordinator first = new AgentRecoveryCoordinator(
                properties, leases, List.of(source), meterRegistry);
        AgentRecoveryCoordinator second = new AgentRecoveryCoordinator(
                properties, leases, List.of(source), meterRegistry);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = executor.submit(first::scan);
            await(recoveryEntered);
            second.scan();
            long conflictDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < conflictDeadline) {
                io.micrometer.core.instrument.Counter conflict =
                        meterRegistry.find("agent_recovery_lease_conflict_total")
                                .tag("lane", "integration-test").counter();
                if (conflict != null && conflict.count() >= 1.0) break;
                Thread.sleep(25);
            }
            allowRecovery.countDown();
            await(recoveryDone);
            a.get();
        } finally {
            executor.shutdownNow();
            first.close();
            second.close();
        }

        Integer terminalRows = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            terminalRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM public.agent_recovery_test_results", Integer.class);
            if (terminalRows != null && terminalRows == 1) break;
            Thread.sleep(25);
        }
        assertThat(recoveries).hasValue(1);
        assertThat(terminalRows).isEqualTo(1);
        assertThat(meterRegistry.get("agent_recovery_lease_conflict_total")
                .tag("lane", "integration-test")
                .counter()
                .count()).isEqualTo(1.0);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("com.moon.moonagent.mapper")
    @Import({AgentRecoveryProperties.class, AgentRecoveryLeaseService.class})
    static class TestConfig {
    }
}
