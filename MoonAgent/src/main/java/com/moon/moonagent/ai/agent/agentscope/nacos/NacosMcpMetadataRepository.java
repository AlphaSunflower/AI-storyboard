package com.moon.moonagent.ai.agent.agentscope.nacos;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.mcp.McpServerDetailInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves Remote MCP server metadata from Nacos with a classpath fallback.
 *
 * <p>The snapshot is consumed by runtime diagnostics and can be used by a
 * later registration adapter without introducing a second configuration
 * source.
 */
public class NacosMcpMetadataRepository {

    private static final Logger log = LoggerFactory.getLogger(NacosMcpMetadataRepository.class);

    private final NacosAssetProperties properties;
    private final Supplier<AiService> aiServiceSupplier;
    private final Resource classpathFallback;
    private final ObjectMapper objectMapper;

    private volatile Snapshot selected;
    private final AtomicReference<CompletableFuture<Snapshot>> selecting = new AtomicReference<>();

    public NacosMcpMetadataRepository(
            NacosAssetProperties properties,
            Supplier<AiService> aiServiceSupplier,
            Resource classpathFallback,
            ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.aiServiceSupplier = Objects.requireNonNull(aiServiceSupplier, "aiServiceSupplier");
        this.classpathFallback = Objects.requireNonNull(classpathFallback, "classpathFallback");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Snapshot snapshot() {
        Snapshot current = selected;
        if (current != null) {
            return current;
        }
        CompletableFuture<Snapshot> created = new CompletableFuture<>();
        CompletableFuture<Snapshot> existing = selecting.compareAndExchange(null, created);
        if (existing != null) {
            return existing.join();
        }
        try {
            Snapshot resolved = resolve();
            selected = resolved;
            created.complete(resolved);
            return resolved;
        } catch (RuntimeException error) {
            created.completeExceptionally(error);
            throw error;
        } finally {
            selecting.compareAndSet(created, null);
        }
    }

    public Map<String, Object> status() {
        Snapshot snapshot = snapshot();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("source", snapshot.source());
        status.put("fallbackReason", snapshot.fallbackReason());
        status.put("servers", snapshot.servers().stream()
                .map(server -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", server.get("name"));
                    item.put("version", serverVersion(server));
                    item.put("tools", toolNames(server));
                    return item;
                })
                .toList());
        return status;
    }

    public void refresh() {
        selected = null;
    }

    private Snapshot resolve() {
        List<Map<String, Object>> fallback = loadClasspath();
        RuntimeException primaryFailure = null;
        if (properties.isEnabled()) {
            try {
                AiService aiService = aiServiceSupplier.get();
                if (aiService != null) {
                    return new Snapshot("nacos", loadFromNacos(aiService, fallback), null);
                }
            } catch (RuntimeException exception) {
                primaryFailure = exception;
            }
        }

        if (!properties.isAllowClasspathFallback()) {
            throw new IllegalStateException(
                    "Nacos MCP metadata unavailable and classpath fallback is disabled",
                    primaryFailure);
        }
        String reason = primaryFailure == null
                ? "Nacos assets disabled"
                : rootMessage(primaryFailure);
        log.warn("[NacosAssets] using classpath MCP metadata fallback: {}", reason);
        return new Snapshot("classpath", fallback, reason);
    }

    private List<Map<String, Object>> loadFromNacos(
            AiService aiService,
            List<Map<String, Object>> fallback) {
        List<Map<String, Object>> servers = new ArrayList<>(fallback.size());
        for (Map<String, Object> fallbackServer : fallback) {
            String name = text(fallbackServer.get("name"));
            String version = text(fallbackServer.get("version"));
            if (name.isBlank() || version.isBlank()) {
                throw new IllegalStateException("MCP fallback metadata has no name/version");
            }
            try {
                McpServerDetailInfo detail = aiService.getMcpServer(name, version);
                if (detail == null) {
                    throw new IllegalStateException(
                            "Nacos MCP metadata not found: " + name + "@" + version);
                }
                servers.add(normalizeNacosServer(detail));
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Unable to load Nacos MCP metadata: " + name + "@" + version,
                        exception);
            }
        }
        return List.copyOf(servers);
    }

    private Map<String, Object> normalizeNacosServer(McpServerDetailInfo detail) {
        Map<String, Object> server = objectMapper.convertValue(
                detail, new TypeReference<LinkedHashMap<String, Object>>() {});
        Object toolSpec = server.remove("toolSpec");
        if (toolSpec instanceof Map<?, ?> toolMap) {
            Object tools = toolMap.get("tools");
            server.put("tools", tools == null ? List.of() : tools);
        } else {
            server.put("tools", List.of());
        }
        return server;
    }

    private List<Map<String, Object>> loadClasspath() {
        try (var input = classpathFallback.getInputStream()) {
            JsonNode root = objectMapper.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            JsonNode servers = root.path("servers");
            if (!servers.isArray()) {
                throw new IllegalStateException("MCP fallback file has no servers array");
            }
            return List.copyOf(objectMapper.convertValue(
                    servers, new TypeReference<List<Map<String, Object>>>() {}));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to read classpath MCP metadata: " + classpathFallback,
                    exception);
        }
    }

    private static List<String> toolNames(Map<String, Object> server) {
        Object tools = server.get("tools");
        if (!(tools instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(tool -> text(tool.get("name")))
                .filter(name -> !name.isBlank())
                .toList();
    }

    private static String serverVersion(Map<String, Object> server) {
        String version = text(server.get("version"));
        if (!version.isBlank()) {
            return version;
        }
        Object versionDetail = server.get("versionDetail");
        if (versionDetail instanceof Map<?, ?> detail) {
            return text(detail.get("version"));
        }
        return "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    public record Snapshot(
            String source,
            List<Map<String, Object>> servers,
            String fallbackReason) {

        public Snapshot {
            servers = servers == null ? List.of() : List.copyOf(servers);
        }
    }
}
