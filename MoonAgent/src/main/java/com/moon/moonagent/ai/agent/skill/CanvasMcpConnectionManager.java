package com.moon.moonagent.ai.agent.skill;

import com.moon.moonagent.ai.agent.agentscope.AgentScopeToolkitFactory;
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
    private final ObjectProvider<AgentScopeToolkitFactory> toolkitFactory;
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
        while (!stopped.get() && !(canvasToolsReady() && mediaToolsReady())) {
            try {
                List<McpSyncClient> clients = syncClients.getIfAvailable(List::of);
                for (McpSyncClient client : clients) {
                    try {
                        if (!client.isInitialized()) client.initialize();
                        ToolCallback[] callbacks = SyncMcpToolCallbackProvider.builder()
                                .mcpClients(List.of(client))
                                .build()
                                .getToolCallbacks();
                        skillExpander.registerRemoteCallbacks(callbacks);
                        toolkitFactory.ifAvailable(AgentScopeToolkitFactory::refreshCanvasTools);
                    } catch (Exception clientError) {
                        log.debug("[MCP] 连接尚未就绪，将重试: {}", clientError.getMessage());
                    }
                }
            } catch (Exception e) {
                log.debug("[MCP] 连接尚未就绪，将重试: {}", e.getMessage());
            }
            if (!(canvasToolsReady() && mediaToolsReady()) && !stopped.get()) {
                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (canvasToolsReady() || mediaToolsReady()) {
            log.info("[MCP] 已注册远程工具: canvas={}, media={}",
                    canvasToolsReady(), mediaToolsReady());
        }
    }

    private boolean canvasToolsReady() {
        return skillExpander.hasTool("canvas_graph_get_state")
                && skillExpander.hasTool("canvas_graph_create_nodes")
                && skillExpander.hasTool("canvas_graph_create_edge")
                && skillExpander.hasTool("canvas_graph_import_media")
                && skillExpander.hasTool("canvas_graph_import_media_batch")
                && skillExpander.hasTool("canvas_graph_layout_graph");
    }

    private boolean mediaToolsReady() {
        return skillExpander.hasTool("generate_image") && skillExpander.hasTool("generate_video");
    }
}
