package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled", havingValue = "true")
public class AgentScopeMessageBus {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeMessageBus.class);

    private final AgentScopeProperties properties;
    private volatile MessageBus bus;

    @PostConstruct
    void init() {
        if (!properties.isWakeupEnabled() || bus != null) return;
        Path workspace = resolveWorkspace();
        LocalFilesystem filesystem = new LocalFilesystem(workspace);
        bus = new WorkspaceMessageBus(filesystem, ".agentscope/bus");
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
