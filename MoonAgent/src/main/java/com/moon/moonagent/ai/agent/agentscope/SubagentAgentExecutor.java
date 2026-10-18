package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.AgentApprovalMode;
import com.moon.moonagent.ai.agent.orchestration.SubagentCancellation;
import com.moon.moonagent.ai.agent.orchestration.SubagentNodeExecutor;
import com.moon.moonagent.ai.agent.orchestration.SubagentRole;
import com.moon.moonagent.ai.agent.orchestration.SubagentRoleCatalog;
import com.moon.moonagent.ai.agent.orchestration.SubagentTask;
import com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用父 Agent 的模型和工具集构造一个**只读**子 Agent。
 *
 * <p>子 Agent 的工具只从父 Toolkit 里取「角色允许 + 工具本身只读」的交集；
 * 写工具永远不进入子 Agent，因此子任务不需要 HITL。后续若要允许写，必须先
 * 把审批冒泡链路补上，不能直接放开这里的过滤。
 */
@Component
public class SubagentAgentExecutor implements SubagentNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubagentAgentExecutor.class);
    private static final int MAX_DEPENDENCY_CHARS = 4_000;
    private static final int MAX_OUTPUT_CHARS = 8_000;

    private final SubagentRoleCatalog roleCatalog;
    private final AgentScopeProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public SubagentAgentExecutor(SubagentRoleCatalog roleCatalog, AgentScopeProperties properties) {
        this.roleCatalog = roleCatalog;
        this.properties = properties;
    }

    public SubagentAgentExecutor(SubagentRoleCatalog roleCatalog) {
        this(roleCatalog, new AgentScopeProperties());
    }
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<HarnessAgent> parent = new AtomicReference<>();

    /** 父 Agent 构建完成后绑定；调用任务图之前必须已绑定。 */
    public void attach(HarnessAgent agent) {
        parent.set(agent);
    }

    @Override
    public SubagentTaskResult execute(SubagentTask task,
                                      Map<String, SubagentTaskResult> dependencies,
                                      SubagentCancellation cancellation) {
        return execute(task, dependencies, cancellation, "");
    }

    @Override
    public SubagentTaskResult execute(SubagentTask task,
                                      Map<String, SubagentTaskResult> dependencies,
                                      SubagentCancellation cancellation,
                                      String context) {
        if (cancellation.isCancelled()) {
            return SubagentTaskResult.cancelled(task.id());
        }
        HarnessAgent parentAgent = parent.get();
        if (parentAgent == null || parentAgent.getDelegate() == null) {
            return SubagentTaskResult.failed(task.id(), "父 Agent 尚未初始化", 0);
        }
        SubagentRole role = roleCatalog.require(task.role());
        Model model = parentAgent.getDelegate().getModel();
        if (model == null) {
            return SubagentTaskResult.failed(task.id(), "子 Agent 没有可用模型", 0);
        }
        List<String> allowed = allowedTools(role, task);
        Toolkit toolkit = childToolkit(parentAgent.getToolkit(), allowed, task.allowWrite());
        ReActAgent child = ReActAgent.builder()
                .name("subagent-" + task.id())
                .sysPrompt(role.prompt())
                .model(model)
                .toolkit(toolkit)
                .maxIters(Math.min(task.maxTurns(), role.maxTurns()))
                .permissionContext(task.allowWrite()
                        ? MoonAgentRuntime.permissionContext(
                                AgentApprovalMode.FULL_ACCESS, Set.of(), Set.of())
                        : MoonAgentRuntime.permissionContext(
                                AgentApprovalMode.PLAN_APPROVAL, Set.of(), new HashSet<>(allowed)))
                .build();
        long started = System.nanoTime();
        java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread watcher = null;
        try {
            String prompt = prompt(task, dependencies, context);
            AgentScopeRunContext parentRun = SubagentRunScope.current();
            RuntimeContext childContext = null;
            final String childSessionId = "subagent-" + task.id() + "-" + UUID.randomUUID();
            final String childUserId = parentRun == null || parentRun.conversation() == null
                    ? null : parentRun.conversation().getUserId();
            if (parentRun != null && parentRun.conversation() != null) {
                AgentScopeRunContext childRun = new AgentScopeRunContext(
                        parentRun.conversation(), parentRun.request(), parentRun.support(),
                        new AgentScopeTurnState(), null, null, null, parentRun.toolProfile());
                childContext = RuntimeContext.builder()
                        .sessionId(childSessionId)
                        .userId(childUserId)
                        .put(AgentScopeRunContext.class, childRun)
                        .build();
            }
            // 真正取消：不能只等 blockLast 返回；轮询 token，触发子 Agent 的 interrupt。
            watcher = Thread.ofVirtual().name("subagent-cancel-" + task.id()).start(() -> {
                while (!finished.get()) {
                    if (cancellation.isCancelled()) {
                        try {
                            if (childUserId != null) {
                                child.interrupt(childUserId, childSessionId);
                            } else {
                                child.interrupt();
                            }
                        } catch (RuntimeException ignored) {
                            // 子 Agent 已结束
                        }
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            Msg result = child.streamEvents(List.<Msg>of(new UserMessage(prompt)), childContext)
                    .filter(AgentResultEvent.class::isInstance)
                    .cast(AgentResultEvent.class)
                    .map(AgentResultEvent::getResult)
                    .blockLast(properties.getSubagentNodeTimeout());
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            if (cancellation.isCancelled()) {
                return SubagentTaskResult.cancelled(task.id());
            }
            if (result == null) {
                return SubagentTaskResult.failed(task.id(), "子 Agent 无返回", durationMs);
            }
            ChatUsage usage = result.getUsage();
            int tokens = usage == null ? 0 : usage.getTotalTokens();
            return SubagentTaskResult.completed(task.id(),
                    extractOutput(result.getTextContent(), task), durationMs, tokens);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.warn("[Subagent] 子任务失败: task={}, role={}, error={}",
                    task.id(), task.role(), e.getMessage());
            return SubagentTaskResult.failed(task.id(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    durationMs);
        } finally {
            finished.set(true);
            if (watcher != null) {
                watcher.interrupt();
            }
            child.close();
        }
    }

    private static List<String> allowedTools(SubagentRole role, SubagentTask task) {
        if (task.tools().isEmpty()) {
            return role.tools();
        }
        Set<String> requested = new HashSet<>(task.tools());
        return role.tools().stream().filter(requested::contains).toList();
    }

    private static Toolkit childToolkit(Toolkit parentToolkit, List<String> allowed,
                                        boolean allowWrite) {
        Toolkit child = new Toolkit();
        for (String name : allowed) {
            AgentTool tool = parentToolkit.getTool(name);
            if (tool == null) continue;
            boolean readOnly = ToolCatalog.policy(name)
                    .map(ToolPolicy::readOnly)
                    .orElseGet(tool::isReadOnly);
            if (!readOnly && !allowWrite) continue;
            child.registerAgentTool(tool);
        }
        return child;
    }

    private static String prompt(SubagentTask task,
                                 Map<String, SubagentTaskResult> dependencies,
                                 String context) {
        StringBuilder out = new StringBuilder();
        if (context != null && !context.isBlank()) {
            out.append("## 全局常量（只读，不得改写；所有节点共用同一份）\n")
                    .append(truncate(context, 12_000))
                    .append("\n\n");
        }
        out.append("## 任务\n").append(task.instruction()).append("\n");
        if (dependencies != null && !dependencies.isEmpty()) {
            out.append("\n## 依赖结果\n");
            dependencies.forEach((id, result) -> out.append("### ").append(id)
                    .append(" (").append(result.status()).append(")\n")
                    .append(truncate(result.output(), MAX_DEPENDENCY_CHARS))
                    .append("\n"));
        }
        out.append("\n只返回 JSON：{\"summary\":\"一句话结论\",\"output\":\"正文\","
                + "\"unresolved\":[]}。不要输出 Markdown 代码块。");
        if ("parameter-table".equals(task.outputSchema())) {
            out.append("\n\n除上述字段外，必须增加 parameters 对象（数字用 number，不要写字符串）：\n")
                    .append("{\"fps\":25,\"durationSeconds\":14,\"aspectRatio\":\"16:9\","
                            + "\"colorTemperatureK\":5600,\"totalFrames\":350,"
                            + "\"renderFrames\":358,\"extraFrames\":8,\"shots\":[{\"id\":1,"
                            + "\"durationSeconds\":4,\"focalLengthMm\":28,\"aperture\":5.6,"
                            + "\"cameraHeightM\":1.2,\"startDistanceM\":3.0,"
                            + "\"moveDistanceM\":1.5,\"endDistanceM\":4.5,"
                            + "\"doorHeightM\":2.4,\"doorHeightRatioStart\":0.4,"
                            + "\"doorHeightRatioEnd\":0.4,\"doorDistanceStartM\":8.296,"
                            + "\"doorDistanceEndM\":8.296,\"distanceM\":3.0,"
                            + "\"riseM\":0.0,\"riseSpeedMps\":0.0}]}\n")
                    .append("distanceM 统一为“沿轴线累计前移量”，三镜必须单调递增；"
                            + "doorDistanceStartM/EndM 是镜头到门洞的起止直线距离，只用于投影校验；"
                            + "焦段三镜固定，除非 zoomAllowed=true；"
                            + "所有镜头的 durationSeconds 之和必须等于总时长。");
        }
        return out.toString();
    }

    private String extractOutput(String raw, SubagentTask task) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = stripFence(raw.trim());
        try {
            JsonNode root = objectMapper.readTree(text);
            String output = root.path("output").asText("");
            if (output.isBlank()) {
                output = root.path("summary").asText("");
            }
            String body = output.isBlank() ? text : output;
            if ("parameter-table".equals(task.outputSchema())) {
                JsonNode parameters = root.path("parameters");
                String params = parameters.isMissingNode() || parameters.isNull()
                        ? "{}" : objectMapper.writeValueAsString(parameters);
                body = "[PARAMS]" + params + "\n" + body;
            }
            return truncate(body, MAX_OUTPUT_CHARS + 2_000);
        } catch (Exception ignored) {
            return truncate(text, MAX_OUTPUT_CHARS + 2_000);
        }
    }

    private static String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstBreak = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstBreak < 0 || lastFence <= firstBreak) {
            return text;
        }
        return text.substring(firstBreak + 1, lastFence).trim();
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…[truncated]";
    }
}
