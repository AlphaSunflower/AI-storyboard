package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.orchestration.SubagentBudget;
import com.moon.moonagent.ai.agent.orchestration.SubagentCancellation;
import com.moon.moonagent.ai.agent.orchestration.SubagentGraphListener;
import com.moon.moonagent.ai.agent.orchestration.SubagentGraphResult;
import com.moon.moonagent.ai.agent.orchestration.SubagentNodeExecutor;
import com.moon.moonagent.ai.agent.orchestration.SubagentOrchestrator;
import com.moon.moonagent.ai.agent.orchestration.SubagentParameterValidator;
import com.moon.moonagent.ai.agent.orchestration.SubagentRepairPlanner;
import com.moon.moonagent.ai.agent.orchestration.SubagentRoleCatalog;
import com.moon.moonagent.ai.agent.orchestration.SubagentRunAuditService;
import com.moon.moonagent.ai.agent.orchestration.SubagentTask;
import com.moon.moonagent.ai.agent.orchestration.SubagentTaskGraph;
import com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 暴露给主模型的通用子智能体编排工具。
 *
 * <p>模型提交一张任务图，运行时校验后并行调度；父级只拿到每个节点的结构化结果，
 * 不把子对话塞回上下文。子 Agent 默认只读，写操作不会出现在子任务里。
 */
@Component
public class AgentScopeOrchestrationTools {

    public static final String RUN_TASK_GRAPH = "run_task_graph";
    public static final String RUN_WRITE_TASK_GRAPH = "run_write_task_graph";

    private static final Logger LOG = LoggerFactory.getLogger(AgentScopeOrchestrationTools.class);

    private final SubagentRoleCatalog roleCatalog;
    private final SubagentNodeExecutor executor;
    private final SubagentRunAuditService audit;
    private final AgentScopeProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentScopeOrchestrationTools(SubagentRoleCatalog roleCatalog,
                                        SubagentNodeExecutor executor) {
        this(roleCatalog, executor, null, null);
    }

    @Autowired
    public AgentScopeOrchestrationTools(SubagentRoleCatalog roleCatalog,
                                        SubagentNodeExecutor executor,
                                        SubagentRunAuditService audit,
                                        AgentScopeProperties properties) {
        this.roleCatalog = roleCatalog;
        this.executor = executor;
        this.audit = audit;
        this.properties = properties;
    }

    public List<AgentScopeTool> tools() {
        return List.of(runTaskGraph(false), runTaskGraph(true));
    }

    private AgentScopeTool runTaskGraph(boolean allowWrite) {
        String name = allowWrite ? RUN_WRITE_TASK_GRAPH : RUN_TASK_GRAPH;
        String description = allowWrite
                ? "编排可写子智能体：任务图节点允许写文件/执行脚本。该工具会先触发父级 HITL 确认；"
                        + "批准后整个任务图的写操作才执行。适合“调研→写作→落盘”这类需要产物的任务。"
                : "编排多个只读子智能体：提交一张有依赖的任务图（最多 12 个节点、深度 2、"
                        + "单节点下游 6），运行时并行执行并只返回每个节点的结构化结果。"
                        + "适合调研→写作→方案→评审这类可并行的创作任务。";
        return new AgentScopeTool(
                name,
                description,
                schema(), !allowWrite, false, false,
                (input, param) -> runGraph(input, AgentScopeToolPipeline.context(param),
                        param == null || param.getToolUseBlock() == null
                                ? null : param.getToolUseBlock().getId(), allowWrite));
    }

    /** 包内可测入口：校验角色 → 跑任务图 → 压成父级可读的摘要。 */
    Map<String, Object> runGraph(Map<String, Object> input) {
        return runGraph(input, null, null, false);
    }

    Map<String, Object> runGraph(Map<String, Object> input, AgentScopeRunContext parentContext) {
        return runGraph(input, parentContext, null, false);
    }

    Map<String, Object> runGraph(Map<String, Object> input, AgentScopeRunContext parentContext,
                                 String toolCallId) {
        return runGraph(input, parentContext, toolCallId, false);
    }

    Map<String, Object> runGraph(Map<String, Object> input, AgentScopeRunContext parentContext,
                                 String toolCallId, boolean writeGraph) {
        ObjectMapper mapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SubagentTaskGraph graph;
        try {
            graph = mapper.convertValue(input, SubagentTaskGraph.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("任务图格式错误: " + e.getMessage());
        }
        Object contextRefInput = input.get("contextRef");
        String contextRef = contextRefInput == null || String.valueOf(contextRefInput).isBlank()
                ? null : String.valueOf(contextRefInput);
        if ((graph.context() == null || graph.context().isBlank())
                && contextRefInput != null && !String.valueOf(contextRefInput).isBlank()) {
            String root = properties == null
                    ? "docs/specs" : properties.getSubagentContextRoot();
            int maxChars = properties == null
                    ? 32_000 : properties.getSubagentContextMaxChars();
            String context = SubagentContextLoader.load(
                    String.valueOf(contextRefInput), root, maxChars);
            graph = new SubagentTaskGraph(graph.task(), graph.nodes(),
                    graph.maxConcurrency(), graph.failFast(), context);
            LOG.info("[Subagent] contextRef={} 已加载 {} chars", contextRefInput, context.length());
        }
        for (SubagentTask task : graph.nodes()) {
            roleCatalog.require(task.role());
        }
        boolean writeNodes = graph.nodes().stream().anyMatch(SubagentTask::allowWrite);
        if (writeNodes && !writeGraph) {
            throw new IllegalArgumentException(
                    "任务图包含 allowWrite 节点，必须改用 run_write_task_graph 并先经父级 HITL 确认");
        }
        if (writeGraph && parentContext != null
                && parentContext.request() != null
                && parentContext.request().getParams() != null) {
            String approvedTools = parentContext.request().getParams().get("approvedTools");
            graph = filterWriteTools(graph, approvedTools);
        }
        SubagentNodeExecutor scoped = parentContext == null ? executor
                : new SubagentNodeExecutor() {
                    @Override
                    public SubagentTaskResult execute(
                            com.moon.moonagent.ai.agent.orchestration.SubagentTask task,
                            java.util.Map<String,
                                    com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult> deps,
                            com.moon.moonagent.ai.agent.orchestration.SubagentCancellation cancellation)
                            throws Exception {
                        return execute(task, deps, cancellation, "");
                    }

                    @Override
                    public SubagentTaskResult execute(
                            com.moon.moonagent.ai.agent.orchestration.SubagentTask task,
                            java.util.Map<String,
                                    com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult> deps,
                            com.moon.moonagent.ai.agent.orchestration.SubagentCancellation cancellation,
                            String context) throws Exception {
                        try (SubagentRunScope.Scope ignored = SubagentRunScope.open(parentContext)) {
                            return executor.execute(task, deps, cancellation, context);
                        }
                    }
                };
        String graphId = input.get("graphId") == null
                || String.valueOf(input.get("graphId")).isBlank()
                ? UUID.randomUUID().toString() : String.valueOf(input.get("graphId"));
        SubagentGraphListener listener = listener(parentContext, toolCallId, graphId, graph, contextRef);
        SubagentBudget budget = budget();
        Map<String, SubagentTaskResult> resumed = audit == null
                ? Map.of() : audit.completedForGraph(graphId);
        SubagentGraphResult result = new SubagentOrchestrator(scoped, listener, budget)
                .run(graph, new SubagentCancellation(), resumed);
        boolean repairEnabled = input.get("repair") == null
                || Boolean.parseBoolean(String.valueOf(input.get("repair")));
        int maxRounds = properties == null ? 1 : properties.getSubagentMaxRepairRounds();
        SubagentTaskGraph current = graph;
        int repairRounds = 0;
        while (repairEnabled && repairRounds < maxRounds) {
            List<String> violations = SubagentParameterValidator.validate(current, result);
            if (!SubagentRepairPlanner.needsRepair(current, result) && violations.isEmpty()) {
                break;
            }
            SubagentTaskGraph next = SubagentRepairPlanner.repairGraph(
                    current, result, String.join("\n", violations));
            SubagentBudget nextBudget = budget();
            if (next == null || nextBudget == null) {
                break;
            }
            SubagentGraphResult repaired = new SubagentOrchestrator(
                    scoped, listener, nextBudget).run(next);
            result = merge(result, repaired);
            current = next;
            repairRounds++;
        }
        List<String> remainingViolations = SubagentParameterValidator.validate(current, result);
        boolean reviewPassed = remainingViolations.isEmpty()
                && !SubagentRepairPlanner.needsRepair(current, result);
        String stoppedReason = reviewPassed
                ? "passed"
                : repairRounds >= maxRounds ? "max_repair_rounds" : "review_rejected";
        return summary(result, repairRounds, remainingViolations, reviewPassed, stoppedReason);
    }

    /**
     * HITL resume 的 {@code approvedTools} 只保留用户勾选的写工具；未传表示整图批准。
     * 写节点至少保留一个获批写工具，否则整图拒绝执行。
     */
    SubagentTaskGraph filterWriteTools(SubagentTaskGraph graph, String approvedTools) {
        if (approvedTools == null || approvedTools.isBlank()) return graph;
        java.util.Set<String> approved = java.util.Arrays.stream(approvedTools.split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        List<SubagentTask> nodes = new ArrayList<>(graph.nodes().size());
        for (SubagentTask task : graph.nodes()) {
            if (!task.allowWrite()) {
                nodes.add(task);
                continue;
            }
            List<String> base = task.tools().isEmpty()
                    ? roleCatalog.require(task.role()).tools() : task.tools();
            List<String> filtered = base.stream()
                    .filter(name -> isReadOnlyTool(name) || approved.contains(name))
                    .toList();
            boolean hasWrite = filtered.stream().anyMatch(name -> !isReadOnlyTool(name));
            if (!hasWrite) {
                throw new IllegalArgumentException(
                        "节点 " + task.id() + " 没有获批的写工具，拒绝执行整张写任务图");
            }
            nodes.add(new SubagentTask(task.id(), task.role(), task.instruction(),
                    task.dependsOn(), task.skills(), filtered, task.maxTurns(),
                    task.outputSchema(), true));
        }
        return new SubagentTaskGraph(graph.task(), nodes, graph.maxConcurrency(),
                graph.failFast(), graph.context());
    }

    private static boolean isReadOnlyTool(String name) {
        return ToolCatalog.policy(name).map(ToolPolicy::readOnly)
                .orElse(!"write_file".equals(name) && !"run_python_script".equals(name));
    }

    private static SubagentGraphResult merge(SubagentGraphResult first, SubagentGraphResult second) {
        Map<String, SubagentTaskResult> merged = new LinkedHashMap<>(first.results());
        merged.putAll(second.results());
        return new SubagentGraphResult(first.task(), merged, second.success(),
                first.tokensUsed() + second.tokensUsed(),
                first.durationMs() + second.durationMs(),
                first.budgetExceeded() || second.budgetExceeded());
    }

    private SubagentBudget budget() {
        if (properties == null) {
            return SubagentBudget.UNLIMITED;
        }
        return new SubagentBudget(properties.getSubagentMaxTokens(),
                properties.getSubagentMaxDurationMs());
    }

    private SubagentGraphListener listener(AgentScopeRunContext parentContext, String toolCallId,
                                           String graphId, SubagentTaskGraph graph,
                                           String contextRef) {
        AgentTurnEventPublisher publisher = parentContext == null ? null : parentContext.publisher();
        String turnId = parentContext == null || parentContext.request() == null
                ? null : parentContext.request().getTurnId();
        String conversationId = parentContext == null || parentContext.conversation() == null
                ? null : parentContext.conversation().getId();
        return new SubagentGraphListener() {
            @Override
            public void onStarted(SubagentTask task) {
                if (audit != null) {
                    audit.started(graphId, conversationId, turnId, toolCallId, task,
                            graph, contextRef);
                }
                if (publisher != null) {
                    publish(publisher, "SUBAGENT_STARTED",
                            payload(toolCallId, turnId, task, "RUNNING"));
                }
            }

            @Override
            public void onCompleted(SubagentTask task, SubagentTaskResult result) {
                if (audit != null) {
                    audit.completed(graphId, task.id(), result);
                }
                if (publisher == null) {
                    return;
                }
                Map<String, Object> payload = payload(toolCallId, turnId, task,
                        result.status().name());
                if (result.output() != null && !result.output().isBlank()) {
                    payload.put("output", truncate(result.output(), 2_000));
                }
                if (result.error() != null && !result.error().isBlank()) {
                    payload.put("error", result.error());
                }
                payload.put("tokens", result.tokens());
                payload.put("durationMs", result.durationMs());
                publish(publisher, "SUBAGENT_COMPLETED", payload);
            }
        };
    }

    private static Map<String, Object> payload(String toolCallId, String turnId,
                                               SubagentTask task, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (toolCallId != null) payload.put("toolCallId", toolCallId);
        if (turnId != null) payload.put("turnId", turnId);
        payload.put("subagentId", task.id());
        payload.put("role", task.role());
        payload.put("status", status);
        return payload;
    }

    private static void publish(AgentTurnEventPublisher publisher, String type,
                                Map<String, Object> payload) {
        try {
            publisher.publishRaw(type, payload);
        } catch (RuntimeException e) {
            LOG.warn("[Subagent] 进度事件发布失败: type={}, error={}", type, e.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static Map<String, Object> summary(SubagentGraphResult result, int repairRounds,
                                               List<String> violations, boolean reviewPassed,
                                               String stoppedReason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", result.success());
        out.put("reviewPassed", reviewPassed);
        out.put("stoppedReason", stoppedReason);
        out.put("task", result.task());
        out.put("repairRounds", repairRounds);
        out.put("parameterViolations", violations == null ? List.of() : violations);
        out.put("tokensUsed", result.tokensUsed());
        out.put("durationMs", result.durationMs());
        out.put("budgetExceeded", result.budgetExceeded());
        List<Map<String, Object>> items = new ArrayList<>();
        result.results().values().stream()
                .sorted(java.util.Comparator.comparing(SubagentTaskResult::taskId))
                .forEach(item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", item.taskId());
                    row.put("status", item.status().name());
                    if (item.output() != null && !item.output().isBlank()) {
                        row.put("output", item.output());
                    }
                    if (item.error() != null && !item.error().isBlank()) {
                        row.put("error", item.error());
                    }
                    row.put("tokens", item.tokens());
                    row.put("durationMs", item.durationMs());
                    items.add(row);
                });
        out.put("results", items);
        return out;
    }

    private static Map<String, Object> schema() {
        Map<String, Object> nodeProperties = new LinkedHashMap<>();
        nodeProperties.put("id", Map.of("type", "string", "description", "图内唯一 id"));
        nodeProperties.put("role", Map.of("type", "string",
                "description", "本地角色名，如 researcher/screenwriter/visual-director/reviewer"));
        nodeProperties.put("instruction", Map.of("type", "string", "description", "给子智能体的任务说明"));
        nodeProperties.put("dependsOn", Map.of("type", "array",
                "items", Map.of("type", "string"), "description", "依赖的节点 id"));
        nodeProperties.put("tools", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "可选：只读工具名，必须是角色工具集的子集"));
        nodeProperties.put("maxTurns", Map.of("type", "integer", "minimum", 1, "maximum", 12));
        nodeProperties.put("allowWrite", Map.of("type", "boolean",
                "description", "该节点是否允许写工具；默认 false，当前 true 会明确失败（HITL 尚未冒泡）"));

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "object");
        node.put("properties", nodeProperties);
        node.put("required", List.of("id", "role", "instruction"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task", Map.of("type", "string", "description", "整图目标"));
        properties.put("graphId", Map.of("type", "string",
                "description", "可选：重试时传同一个 graphId，已完成节点直接复用，不重复执行"));
        properties.put("nodes", Map.of("type", "array", "items", node,
                "minItems", 1, "maxItems", 12));
        properties.put("maxConcurrency", Map.of("type", "integer", "minimum", 1, "maximum", 6));
        properties.put("failFast", Map.of("type", "boolean"));
        properties.put("context", Map.of("type", "string",
                "description", "所有子 Agent 共享的只读常量（几何/参数/硬约束），原样注入每个节点"));
        properties.put("contextRef", Map.of("type", "string",
                "description", "可选：从服务端 docs/specs 下加载共享常量文件，避免模型复制长 JSON；context 为空时生效"));
        properties.put("repair", Map.of("type", "boolean",
                "description", "review 判不通过时是否自动回灌修复一轮，默认 true"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("task", "nodes"));
        return schema;
    }
}
