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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Canvas MCP 连接看门狗。
 *
 * <p>Spring AI MCP Client 可以在应用启动时不强制连接（{@code initialized:false}），而
 * Canvas 服务是可选进程：真正决定「模型能不能调画布工具」的不是发现记录
 * {@link SkillExpander}，而是 Toolkit 注册表。因此这里做两件事：
 *
 * <ol>
 *   <li><b>就绪判定落在 Toolkit</b>：只要注册表里缺画布工具，就继续重试并尝试补齐；
 *       发现记录里有、注册表里没有时，直接触发一次 {@code refreshCanvasTools()} 修复。</li>
 *   <li><b>常驻看门狗</b>：不再「就绪即退出」。画布 MCP 后连、断链重连、注册表被
 *       意外改写都能在下一轮自愈，同时把不可达原因（URL + 真实异常）打成 WARN，避免
 *       再次出现「界面只看到 Tool not found，日志什么都查不到」的事故。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class CanvasMcpConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(CanvasMcpConnectionManager.class);
    /** 未就绪时的重试间隔。 */
    private static final long RETRY_INTERVAL_MS = 2_000L;
    /** 已就绪后的巡检间隔：只做校验，不在正常态反复重写注册表。 */
    private static final long RECONCILE_INTERVAL_MS = 30_000L;
    /** 同一条未就绪原因最多每 N 轮重复上报一次，避免日志刷屏。 */
    private static final int REASON_REPEAT_EVERY = 30;

    private final ObjectProvider<List<McpSyncClient>> syncClients;
    private final SkillExpander skillExpander;
    private final ObjectProvider<AgentScopeToolkitFactory> toolkitFactory;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** Canvas MCP 地址，仅用于日志定位（连不上时把地址一起打出来）。 */
    @Value("${spring.ai.mcp.client.streamable-http.connections.canvas-server.url:}"
            + "${spring.ai.mcp.client.streamable-http.connections.canvas-server.endpoint:}")
    private String canvasMcpAddress = "";

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread.ofVirtual().name("canvas-mcp-connect").start(this::watchLoop);
    }

    @PreDestroy
    public void stop() {
        stopped.set(true);
    }

    private void watchLoop() {
        String lastReason = "";
        int repeats = 0;
        while (!stopped.get()) {
            boolean ready;
            String reason;
            try {
                reason = reconcile();
                ready = reason == null;
            } catch (Exception e) {
                ready = false;
                reason = "巡检异常: " + describe(e);
            }
            if (ready) {
                if (!lastReason.isEmpty()) {
                    log.info("[MCP] 画布/媒体远程工具已就绪: canvas={}, media={}, 画布工具数={}",
                            canvasToolsReady(), mediaToolsReady(), canvasToolCount());
                }
                lastReason = "";
                repeats = 0;
            } else {
                boolean changed = !reason.equals(lastReason);
                if (changed || repeats % REASON_REPEAT_EVERY == 0) {
                    log.warn("[MCP] 远程工具未就绪{}: {}（稍后继续重试）",
                            canvasMcpAddress.isBlank() ? "" : "，canvas-server=" + canvasMcpAddress,
                            reason);
                }
                lastReason = reason;
                repeats++;
            }
            if (!sleep(ready ? RECONCILE_INTERVAL_MS : RETRY_INTERVAL_MS)) return;
        }
    }

    /**
     * 一轮巡检：把 MCP 客户端的工具并入发现记录，并在注册表缺画布工具时补齐。
     *
     * @return {@code null} 表示就绪；否则返回人类可读的未就绪原因
     */
    private String reconcile() {
        List<String> failures = new ArrayList<>();
        List<McpSyncClient> clients = syncClients.getIfAvailable(List::of);
        if (clients.isEmpty()) {
            failures.add("没有可用的 MCP 客户端");
        }
        for (McpSyncClient client : clients) {
            String name = clientName(client);
            try {
                if (!client.isInitialized()) client.initialize();
                ToolCallback[] callbacks = SyncMcpToolCallbackProvider.builder()
                        .mcpClients(List.of(client))
                        .build()
                        .getToolCallbacks();
                skillExpander.registerRemoteCallbacks(callbacks);
            } catch (Exception clientError) {
                failures.add(name + ": " + describe(clientError));
            }
        }

        // 发现记录里有画布工具、注册表里没有 → 注册表需要修复（启动顺序、断链重建等）。
        if (!canvasToolsReady() || canvasToolCount() == 0) {
            toolkitFactory.ifAvailable(AgentScopeToolkitFactory::refreshCanvasTools);
        }

        if (!canvasToolsReady()) {
            failures.add("画布工具未发现（canvas MCP 不可达或未暴露 canvas_graph_*）");
        } else if (canvasToolCount() == 0) {
            failures.add("画布工具未注册进 Toolkit");
        }
        if (!mediaToolsReady()) {
            failures.add("图片/视频远程工具未发现");
        }
        return failures.isEmpty() ? null : String.join("；", failures);
    }

    /**
     * 画布 MCP 存活探针：这 6 个标记必须是「任何受支持版本都一定有的」工具。
     *
     * <p>**新增工具不得加进就绪标记**（例如 {@code canvas_graph_apply_plan} /
     * {@code canvas_graph_create_edges}）：连上旧版 canvas 服务时会被判成「未就绪」，
     * 于是向模型谎报「画布不可用」，正是 2026-10-15 那类事故的形态。
     * 版本差异（新工具缺失）由技能里的降级分支处理：退回逐节点/逐条连接线建图，不中止本轮。
     */
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

    /** Toolkit 注册表里真实的画布工具数——模型能否调到只看这个。 */
    private int canvasToolCount() {
        AgentScopeToolkitFactory factory = toolkitFactory.getIfAvailable();
        if (factory == null) return 0;
        Set<String> names = factory.canvasToolNames();
        return names.size();
    }

    private static String clientName(McpSyncClient client) {
        try {
            String text = String.valueOf(client.getClientInfo());
            if (text != null && !text.isBlank() && !"null".equals(text)) return text;
        } catch (Exception ignored) {
            // 客户端信息只用于日志，取不到就退回类名
        }
        return client.getClass().getSimpleName();
    }

    private static String describe(Throwable error) {
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 6; depth++) {
            String message = cursor.getMessage();
            if (message != null && !message.isBlank()) return message;
            cursor = cursor.getCause();
        }
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
