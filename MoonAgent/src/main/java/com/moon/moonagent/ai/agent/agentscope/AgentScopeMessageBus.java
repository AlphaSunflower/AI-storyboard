package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.extensions.postgresql.PostgresDistributedStore;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled", havingValue = "true")
public class AgentScopeMessageBus {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeMessageBus.class);

    private final AgentScopeProperties properties;
    private final DataSource dataSource;
    private volatile MessageBus bus;

    /**
     * Binds the bus to the same distributed store used by HarnessAgent. Without this, the runtime
     * creates separate Postgres stores for state, wakeups and async-tool tracking.
     */
    public synchronized void bind(PostgresDistributedStore distributedStore) {
        if (!properties.isWakeupEnabled()) return;
        Path workspace = resolveWorkspace();
        AbstractFilesystem filesystem = distributedStore != null
                ? new RemoteFilesystem(distributedStore.baseStore())
                : properties.isDistributedStoreEnabled()
                ? new RemoteFilesystem(PostgresDistributedStore.create(dataSource).baseStore())
                : new LocalFilesystem(workspace);
        MessageBus previous = bus;
        bus = new WorkspaceMessageBus(filesystem, ".agentscope/bus");
        if (previous != null) {
            try {
                previous.close();
            } catch (Exception e) {
                log.debug("[AgentScope Wakeup] previous MessageBus close failed: {}", e.getMessage());
            }
        }
        log.info("[AgentScope Wakeup] MessageBus ready at {}", workspace.resolve(".agentscope/bus"));
    }

    public MessageBus messageBus() {
        if (bus == null) throw new IllegalStateException("AgentScope MessageBus 尚未初始化");
        return bus;
    }

    public boolean enabled() {
        return properties.isWakeupEnabled() && bus != null;
    }

    @PreDestroy
    void close() {
        MessageBus current = bus;
        bus = null;
        if (current == null) return;
        try {
            current.close();
        } catch (Exception e) {
            log.debug("[AgentScope Wakeup] MessageBus close failed: {}", e.getMessage());
        }
    }

    private Path resolveWorkspace() {
        String configuredDir = properties.getWakeupDir();
        if (configuredDir != null && !configuredDir.isBlank()) return Path.of(configuredDir);
        String workspaceDir = properties.getWorkspaceDir();
        if (workspaceDir != null && !workspaceDir.isBlank()) return Path.of(workspaceDir);
        return Path.of(System.getProperty("java.io.tmpdir"), "moonagent-agentscope");
    }
}
