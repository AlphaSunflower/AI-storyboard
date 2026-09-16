package com.moon.moonagent.ai.agent.skill;

import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Canvas MCP 延迟连接器。
 *
 * <p>Spring AI MCP Client 默认可在应用启动时初始化，但 Canvas Bridge 是本地可选进程。
 * 这里关闭启动期强制连接，在应用就绪后后台重试；连接成功后动态注册 canvas_* 工具，
 * 不阻塞 MoonAgent 的普通对话能力。
 */
@Component
@RequiredArgsConstructor
public class CanvasMcpConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(CanvasMcpConnectionManager.class);
    private static final long RETRY_INTERVAL_MS = 2_000L;

    private final ObjectProvider<List<McpSyncClient>> syncClients;
    private final SkillExpander skillExpander;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread.ofVirtual().name("canvas-mcp-connect").start(this::connectLoop);
    }

    @PreDestroy
    public void stop() {
        stopped.set(true);
    }

    private void connectLoop() {
        while (!stopped.get() && !canvasToolsReady()) {
            try {
                List<McpSyncClient> clients = syncClients.getIfAvailable(List::of);
                for (McpSyncClient client : clients) {
                    if (!client.isInitialized()) client.initialize();
                }
                if (!clients.isEmpty()) {
                    ToolCallback[] callbacks = SyncMcpToolCallbackProvider.builder()
                            .mcpClients(clients)
                            .build()
                            .getToolCallbacks();
                    skillExpander.registerRemoteCallbacks(callbacks);
                }
            } catch (Exception e) {
                log.debug("[CanvasMCP] Canvas MCP 尚未连接，将重试: {}", e.getMessage());
            }
            if (!canvasToolsReady() && !stopped.get()) {
                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (canvasToolsReady()) {
            log.info("[CanvasMCP] Canvas MCP 已连接并注册工具");
        }
    }

    private boolean canvasToolsReady() {
        return skillExpander.hasTool("canvas_get_context")
                && skillExpander.hasTool("canvas_import_media")
                && skillExpander.hasTool("canvas_create_canvas")
                && skillExpander.hasTool("canvas_rename_canvas")
                && skillExpander.hasTool("canvas_create_result_slots")
                && skillExpander.hasTool("canvas_mark_result_slots")
                && skillExpander.hasTool("canvas_get_node_model_config")
                && skillExpander.hasTool("canvas_set_node_model_config");
    }
}
