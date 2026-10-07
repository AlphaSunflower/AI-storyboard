package com.moon.moonagent.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * Base class for MoonAgent tests that need the full Spring context backed by a real
 * PostgreSQL instance.
 *
 * <p>The production configuration activates the {@code local} profile, whose datasource
 * lives in a gitignored {@code application-local.yml}. That file does not exist on CI,
 * so any full-context test would fail with "Failed to determine suitable jdbc url".
 * The full context also builds {@code MinioStorage} eagerly in a {@code @PostConstruct}
 * bucket check. Throwaway PostgreSQL and MinIO containers keep these tests
 * self-contained: the schema is applied from {@code db/moonagent-test-schema.sql} and
 * every external endpoint is injected before the context is created.
 */
@SpringBootTest
@ActiveProfiles("testcontainers")
@TestPropertySource(properties = {
        "storyboard.internal-secret=test-internal-secret",
        "storyboard.storage.access-key=minioadmin",
        "storyboard.storage.secret-key=minioadmin123",
        // No Nacos / Redis / MCP infrastructure is available inside CI.
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.ai.mcp.client.enabled=false",
        "spring.ai.mcp.server.enabled=false"
})
public abstract class PostgresContainerTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("moon_agent")
            .withUsername("test")
            .withPassword("test");

    static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("quay.io/minio/minio:latest"))
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin123")
            .withExposedPorts(9000)
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))
            .withStartupTimeout(Duration.ofMinutes(2));

    static {
        // Start once per JVM and keep the container alive for every subclass. A shared
        // container avoids re-running the init script on restart, which otherwise trips
        // Testcontainers' shaded commons-io lookup on the second test class.
        POSTGRES.start();
        applySchema();
        MINIO.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            MINIO.stop();
            POSTGRES.stop();
        }));
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("storyboard.storage.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
    }

    private static void applySchema() {
        try (InputStream in = new ClassPathResource("db/moonagent-test-schema.sql").getInputStream();
             Connection connection = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            statement.execute(sql);
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to initialize MoonAgent test schema", e);
        }
    }
}
