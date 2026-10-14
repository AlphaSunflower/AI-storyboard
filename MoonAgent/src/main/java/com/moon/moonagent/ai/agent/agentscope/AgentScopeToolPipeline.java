package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moon.moonagent.ai.agent.AgentInterruptException;
import com.moon.moonagent.ai.agent.audit.AgentInvocationContext;
import com.moon.moonagent.ai.agent.audit.AgentInvocationContextHolder;
import com.moon.moonagent.ai.agent.audit.ToolExecutionTemplate;
import com.moon.moonagent.ai.agent.audit.ToolInvocationContext;
import com.moon.moonagent.ai.agent.audit.ToolInvocationLedger;
import com.moon.moonagent.ai.agent.audit.ToolInvocationScope;
import com.moon.moonagent.ai.agent.audit.ToolInvocationScopeHolder;
import com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowService;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentToolInvocation;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class AgentScopeToolPipeline {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 画布编辑模式下不加载的分镜工具：画布回合内调用直接被拒绝，用户需要分镜时先退出画布模式。
     */
    private static final Set<String> STORYBOARD_TOOL_NAMES = Set.of(
            AgentScopeStoryboardTools.START_STORYBOARD,
            AgentScopeStoryboardTools.REVISE_STORYBOARD,
            AgentScopeStoryboardTools.APPROVE_STORYBOARD,
            AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGE,
            AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGES,
            AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO,
            AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEOS,
            AgentScopeStoryboardTools.UPSERT_STORYBOARD_SHOT,
            AgentScopeStoryboardTools.LIST_STORYBOARD_DRAFT,
            AgentScopeStoryboardTools.LIST_PROJECT_STORYBOARD,
            AgentScopeStoryboardTools.UPDATE_PROJECT_STORYBOARD_SHOT,
            AgentScopeStoryboardTools.DELETE_STORYBOARD_DRAFT_SHOT,
            AgentScopeStoryboardTools.REORDER_STORYBOARD_DRAFT,
            AgentScopeStoryboardTools.DELETE_PROJECT_STORYBOARD_SHOT,
            AgentScopeStoryboardTools.CLEAR_PROJECT_STORYBOARD,
            AgentScopeVideoMergeTools.MERGE_STORYBOARD_VIDEOS);

    private static final String CANVAS_MODE_STORYBOARD_MESSAGE =
            "画布编辑模式下不加载分镜工具，也无法生成或修改分镜。"
                    + "请改用画布节点编排完成需求；如确实需要分镜，请通过 / 菜单退出画布模式后再试。";

    /** 非 Map 工具结果在管线内部传递时使用的装箱键，只在 {@link #rawResult} 拆箱，绝不外泄到模型。 */
    private static final String RAW_RESULT_KEY = "__moonRawToolResult";

    private static final Map<String, ToolPolicy> POLICIES = Map.ofEntries(
            Map.entry(AgentScopeImageTools.GENERATE_IMAGE,
                    new ToolPolicy("MEDIA_IMAGE", "media-image", true, false)),
            Map.entry(AgentScopeImageTools.GENERATE_IMAGE_BATCH,
                    new ToolPolicy("MEDIA_IMAGE_BATCH", "media-image-batch", true, false)),
            Map.entry(AgentScopeVideoTools.GENERATE_VIDEO,
                    new ToolPolicy("MEDIA_VIDEO", "media-video", true, false)),
            Map.entry(AgentScopeStoryboardTools.START_STORYBOARD,
                    new ToolPolicy("LONG_TOOL", "storyboard-start", false, false)),
            Map.entry(AgentScopeStoryboardTools.REVISE_STORYBOARD,
                    new ToolPolicy("STORYBOARD_WRITE", "storyboard-revise", false, false)),
            Map.entry(AgentScopeStoryboardTools.APPROVE_STORYBOARD,
                    new ToolPolicy("LONG_TOOL", "storyboard-approve", false, false)),
            Map.entry(AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGE,
                    new ToolPolicy("MEDIA_IMAGE", "storyboard-image", false, false)),
            Map.entry(AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO,
                    new ToolPolicy("MEDIA_VIDEO", "storyboard-video", false, false)),
            Map.entry(AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEOS,
                    new ToolPolicy("MEDIA_VIDEO_BATCH", "storyboard-video-batch", false, false)),
            Map.entry(AgentScopeStoryboardTools.UPSERT_STORYBOARD_SHOT,
                    new ToolPolicy("STORYBOARD_WRITE", "", false, false)),
            Map.entry(AgentScopeStoryboardTools.LIST_STORYBOARD_DRAFT,
                    new ToolPolicy("READ", "", false, true)),
            Map.entry(AgentScopeStoryboardTools.LIST_PROJECT_STORYBOARD,
                    new ToolPolicy("READ", "", false, true)),
            Map.entry(AgentScopeStoryboardTools.UPDATE_PROJECT_STORYBOARD_SHOT,
                    new ToolPolicy("STORYBOARD_WRITE", "", false, false)),
            Map.entry(AgentScopeStoryboardTools.DELETE_STORYBOARD_DRAFT_SHOT,
                    new ToolPolicy("STORYBOARD_WRITE", "storyboard-draft-delete", false, false)),
            Map.entry(AgentScopeStoryboardTools.REORDER_STORYBOARD_DRAFT,
                    new ToolPolicy("STORYBOARD_WRITE", "storyboard-draft-reorder", false, false)),
            Map.entry(AgentScopeStoryboardTools.DELETE_PROJECT_STORYBOARD_SHOT,
                    new ToolPolicy("STORYBOARD_DELETE", "storyboard-project-delete", false, false)),
            Map.entry(AgentScopeStoryboardTools.CLEAR_PROJECT_STORYBOARD,
                    new ToolPolicy("STORYBOARD_DELETE", "storyboard-project-clear", false, false)),
            Map.entry(AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGES,
                    new ToolPolicy("MEDIA_IMAGE_BATCH", "storyboard-image-batch", false, false)),
            Map.entry(AgentScopeVideoMergeTools.MERGE_STORYBOARD_VIDEOS,
                    new ToolPolicy("MEDIA_VIDEO", "storyboard-video-merge", false, false)),
            Map.entry(AgentScopeCreativeTools.GET_CONTEXT,
                    new ToolPolicy("READ", "", false, true)),
            Map.entry(AgentScopeCreativeTools.UPDATE_CONTEXT,
                    new ToolPolicy("CREATIVE_CONTEXT_WRITE", "", false, false)),
            Map.entry(AgentScopeCreativeTools.EXTRACT_CONSTRAINTS,
                    new ToolPolicy("READ", "", false, true)),
            Map.entry(AgentScopeCreativeTools.REVIEW_MEDIA,
                    new ToolPolicy("READ", "", false, true)),
            Map.entry(AgentScopeCreativeTools.LIST_CRITIQUES,
                    new ToolPolicy("READ", "", false, true)),
            Map.entry(AgentScopeCreativeTools.SAVE_TIMELINE,
                    new ToolPolicy("TIMELINE_WRITE", "", false, false)),
            Map.entry(AgentScopeCreativeTools.GET_TIMELINE,
                    new ToolPolicy("READ", "", false, true)),
            Map.entry("refineImage",
                    new ToolPolicy("MEDIA_IMAGE", "", false, false)));

    private final ToolInvocationLedger ledger;
    private final ToolExecutionTemplate executionTemplate;
    private final MediaToolPreflightService preflight;
    private final GenerationPreferenceDefaults generationPreferenceDefaults;
    private final AgentWorkflowService workflows;
    private final ToolCallStateGuard callStates;

    public AgentScopeTool wrap(AgentScopeTool tool) {
        AgentScopeTool original = tool;
        return tool.withHandler((input, param) -> executeMap(original, input, param));
    }

    public ToolBase wrapGeneric(ToolBase tool) {
        return new AuditedToolBase(tool, this);
    }

    Map<String, Object> executeMap(AgentScopeTool tool,
                                   Map<String, Object> input,
                                   ToolCallParam param) {
        Map<String, Object> rejected = canvasModeRejection(tool.getName(), param);
        if (rejected != null) return ToolResultContract.normalize(tool.getName(), rejected);
        // 生成偏好作为缺省值补齐（只补空值，不覆盖模型/用户已给的值；自动模式原样返回）
        Map<String, Object> effectiveInput = generationPreferenceDefaults.applyDefaults(
                tool.getName(), input, preferencesOf(param));
        ToolInvocationContext context = context(tool, effectiveInput, param);
        String guardedTurnId = turnId(param);
        String guardedToolCallId = toolCallId(param);
        // 生命周期守卫：真正执行前进入 RUNNING（放在 ledger.start 之前，越界时不留孤儿审计行）
        callStates.begin(guardedTurnId, guardedToolCallId);
        AgentToolInvocation record = ledger.start(context, effectiveInput);
        long started = System.nanoTime();
        try (ToolInvocationScopeHolder.Scope ignored =
                     ToolInvocationScopeHolder.open(new ToolInvocationScope(
                             record, context, OffsetDateTime.now()))) {
            workflows.beforeTool(context.getConversationId(), tool.getName(), effectiveInput, tool.isReadOnly());
            Supplier<Map<String, Object>> invocation = () -> asMap(tool.invoke(effectiveInput, param));
            Supplier<Map<String, Object>> guarded = preflightFor(tool).contains("preflight")
                    ? () -> preflight.guard(tool.getName(), effectiveInput, param,
                    effective -> externalAction(tool, context, effective, param, invocation))
                    : () -> externalAction(tool, context, effectiveInput, param, invocation);
            // 自研工具全链唯一一次归一：rawResult 只保留 null 掩码修复，不再提前归一
            Map<String, Object> output = ToolResultContract.normalize(tool.getName(), rawResult(guarded.get()));
            workflows.afterTool(context.getConversationId(), tool.getName(), output, null,
                    context(param) == null ? null : context(param).turnState());
            finish(record, context, effectiveInput, output, null, started);
            // 成功终态写在审计收尾之后，位于 try 内、catch 之外：这里抛错不会被下面的
            // catch 吞成 ERROR，守卫的显式失败一定能送达调用方。
            callStates.complete(guardedTurnId, guardedToolCallId,
                    ToolResultContract.resultStatusOf(output));
            return output;
        } catch (RuntimeException e) {
            // 与成功出口互斥：调用已经以 SUCCESS 落定后这里不会再写终态
            if (callStates.state(guardedTurnId, guardedToolCallId)
                    == ToolCallStateGuard.ToolCallState.RUNNING) {
                callStates.complete(guardedTurnId, guardedToolCallId,
                        interrupted(e) ? ToolCallStateGuard.INTERRUPTED
                                : ToolResultContract.ERROR);
            }
            workflows.afterTool(context.getConversationId(), tool.getName(), null, e,
                    context(param) == null ? null : context(param).turnState());
            finish(record, context, input, null, e, started);
            throw e;
        }
    }

    /**
     * 工具原始返回值保持不变，只把 {@code null} 掩码成契约错误信封。
     *
     * <p>这里不再调用 {@link ToolResultContract#normalize}：非 Map 返回值（String/List 等）
     * 必须保持原形状，交给 {@code executeMap} 里唯一一次归一，避免包成
     * {@code {result:{result:...}}} 改变模型可见 JSON。
     */
    static Object rawResult(Object value) {
        if (value instanceof Map<?, ?> map && map.size() == 1 && map.containsKey(RAW_RESULT_KEY)) {
            return ((RawToolResult) map.get(RAW_RESULT_KEY)).value();
        }
        return value == null ? ToolResultContract.normalize(null, null) : value;
    }

    /**
     * 非 Map 工具结果的形状保持包装：{@link #asMap} 的返回类型必须是 Map（外部幂等执行与
     * preflight 都按 Map 签名），但 String/List 等结果不能在这里被改形状或提前归一，
     * 因此先原样装箱，由 {@link #rawResult} 拆箱后走 {@code executeMap} 里唯一一次归一。
     */
    private record RawToolResult(Object value) {
    }

    /** 工具结果转 Map；非 Map 值（含 {@code null}）形状保持地装箱，不做归一。 */
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), item));
            return out;
        }
        return Map.of(RAW_RESULT_KEY, new RawToolResult(value));
    }

    /** 异常链里出现中断/取消时记入中断终态，其它异常记失败终态。 */
    private static boolean interrupted(Throwable error) {
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 8; depth++) {
            if (cursor instanceof AgentInterruptException
                    || cursor instanceof InterruptedException
                    || cursor instanceof CancellationException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    Mono<ToolBaseResult> executeGeneric(ToolBase tool,
                                        ToolCallParam param,
                                        Mono<ToolBaseResult> invocation) {
        ToolInvocationContext context = context(tool.getName(), param, param.getInput());
        AgentToolInvocation record = ledger.start(context, param.getInput());
        long started = System.nanoTime();
        try (ToolInvocationScopeHolder.Scope ignored =
                     ToolInvocationScopeHolder.open(new ToolInvocationScope(
                             record, context, OffsetDateTime.now()))) {
            return invocation
                    .doOnSuccess(result -> finish(record, context, param.getInput(),
                            result == null ? null : result.auditValue(), null, started))
                    .doOnError(error -> finish(record, context, param.getInput(),
                            null, error, started));
        } catch (RuntimeException e) {
            finish(record, context, param.getInput(), null, e, started);
            throw e;
        }
    }

    private Map<String, Object> externalAction(AgentScopeTool tool,
                                               ToolInvocationContext context,
                                               Map<String, Object> input,
                                               ToolCallParam param,
                                               Supplier<Map<String, Object>> invocation) {
        String actionType = policy(tool).actionType();
        AgentScopeRunContext runContext = context(param);
        if (actionType.isBlank() || runContext == null
                || runContext.externalActions() == null
                || runContext.checkpointId() == null
                || runContext.checkpointId().isBlank()) {
            return asMap(invocation.get());
        }
        String toolCallId = toolCallId(param);
        Map<String, Object> result = runContext.externalActions().executeOnce(
                context.getConversationId(),
                runContext.checkpointId(),
                toolCallId,
                tool.getName(),
                actionType,
                input,
                invocation);
        Map<String, Object> audited = new LinkedHashMap<>(result == null ? Map.of() : result);
        audited.putIfAbsent("externalActionKey",
                runContext.checkpointId() + ":" + toolCallId + ":" + tool.getName());
        return audited;
    }

    private void finish(AgentToolInvocation record,
                        ToolInvocationContext context,
                        Object input,
                        Object output,
                        Throwable error,
                        long started) {
        executionTemplate.finish(record, context, input, output, error, started);
    }

    private ToolInvocationContext context(AgentScopeTool tool,
                                          Map<String, Object> input,
                                          ToolCallParam param) {
        return context(tool.getName(), param, input);
    }

    private ToolInvocationContext context(String toolName,
                                          ToolCallParam param,
                                          Map<String, Object> input) {
        AgentScopeRunContext runContext = context(param);
        AgentConversation conversation = runContext == null ? null : runContext.conversation();
        AgentInvocationContext base = AgentInvocationContextHolder.current();
        ToolPolicy policy = policy(toolName, false);
        return ToolInvocationContext.builder()
                .invocationKey(invocationKey(runContext, toolName, param))
                .traceId(base.traceId())
                .loopRunId(base.loopRunId())
                .loopStepId(base.loopStepId())
                .taskId(first(base.taskId(), value(input, "taskId"), value(input, "task_id")))
                .conversationId(first(base.conversationId(),
                        conversation == null ? "" : conversation.getId(),
                        value(input, "conversationId")))
                .userId(first(base.userId(), conversation == null ? "" : conversation.getUserId()))
                .projectId(first(base.projectId(),
                        conversation == null ? "" : conversation.getProjectId(),
                        value(input, "projectId"), value(input, "project_id")))
                .toolCallId(toolCallId(param))
                .toolName(toolName)
                .toolKind(policy.toolKind())
                .source("AGENT_SCOPE")
                .skillVersion(base.skillVersion())
                .model(base.model())
                .engineVersion(first(base.engineVersion(),
                        conversation == null ? "" : conversation.getEngineVersion()))
                .build();
    }

    private static String invocationKey(AgentScopeRunContext runContext,
                                        String toolName,
                                        ToolCallParam param) {
        String checkpointId = runContext == null ? "" : runContext.checkpointId();
        String toolCallId = toolCallId(param);
        if (checkpointId != null && !checkpointId.isBlank()
                && toolCallId != null && !toolCallId.isBlank()) {
            return checkpointId + ":" + toolCallId + ":" + toolName;
        }
        return "";
    }

    private static ToolPolicy policy(AgentScopeTool tool) {
        return policy(tool.getName(), tool.isReadOnly());
    }

    private static ToolPolicy policy(String toolName, boolean readOnly) {
        return POLICIES.getOrDefault(toolName,
                new ToolPolicy(readOnly ? "READ" : "WRITE", "", false, readOnly));
    }

    private java.util.Set<String> preflightFor(AgentScopeTool tool) {
        return policy(tool).preflight() && preflight.appliesTo(tool.getName())
                ? java.util.Set.of("preflight") : java.util.Set.of();
    }

    static AgentScopeRunContext context(ToolCallParam param) {
        return param == null || param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().get(AgentScopeRunContext.class);
    }

    /** 本轮生成偏好（无会话上下文时为空偏好 → 不做任何补齐）。 */
    private static com.moon.moonagent.ai.agent.AgentGenerationPreferences preferencesOf(ToolCallParam param) {
        AgentScopeRunContext runContext = context(param);
        return runContext == null || runContext.request() == null
                ? com.moon.moonagent.ai.agent.AgentGenerationPreferences.none()
                : runContext.request().getGenerationPreferences();
    }

    /** 画布编辑回合内的分镜工具调用：返回结构化拒绝，不执行、不落审计记录。 */
    private static Map<String, Object> canvasModeRejection(String toolName, ToolCallParam param) {
        if (toolName == null || !STORYBOARD_TOOL_NAMES.contains(toolName)) return null;
        AgentScopeRunContext runContext = context(param);
        if (runContext == null || runContext.request() == null) return null;
        CanvasSessionContext canvas = runContext.request().getCanvasContext();
        if (canvas == null || !canvas.isCanvasEditMode()) return null;
        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("ok", false);
        rejected.put("code", "CANVAS_MODE_STORYBOARD_UNAVAILABLE");
        rejected.put("tool", toolName);
        rejected.put("message", CANVAS_MODE_STORYBOARD_MESSAGE);
        return rejected;
    }

    private static String toolCallId(ToolCallParam param) {
        return param == null || param.getToolUseBlock() == null
                ? "" : param.getToolUseBlock().getId();
    }

    /** 生命周期守卫的 turn 键，复用本轮 OrchestrationRequest 的 turnId（不新造关联 ID）。 */
    private static String turnId(ToolCallParam param) {
        AgentScopeRunContext runContext = context(param);
        return runContext == null || runContext.request() == null
                ? "" : runContext.request().getTurnId();
    }

    private static String value(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String first(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private record ToolPolicy(
            String toolKind,
            String actionType,
            boolean preflight,
            boolean readOnly
    ) {
    }

    record ToolBaseResult(Object value, Object auditValue) {
    }

    /**
     * 解析框架工具的文本结果用于审计。解析失败不再静默返回 {@code null}（审计行会变成空白）：
     * 看起来是信封 JSON 却解析不了 → 契约错误；纯文本（MCP 文本结果）→ 归一为文本结果。
     */
    private static Object auditValue(String toolName, ToolResultBlock block) {
        if (block == null) return null;
        if (block.getOutput().isEmpty()) return ToolResultContract.normalize(toolName, null);
        Object first = block.getOutput().getFirst();
        if (!(first instanceof TextBlock textBlock)) return ToolResultContract.normalize(toolName, null);
        String text = textBlock.getText();
        if (text == null || text.isBlank()) return ToolResultContract.normalize(toolName, null);
        try {
            return JSON.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            if (text.trim().startsWith("{")) {
                Map<String, Object> broken = new LinkedHashMap<>();
                broken.put("ok", false);
                broken.put("code", ToolResultContract.CODE_UNPARSEABLE_TOOL_RESULT);
                broken.put("message", "工具返回的信封 JSON 无法解析，结果可能被截断或不是合法 JSON。");
                broken.put("hint", "请缩小单次返回的数据量或改用其它工具，不要把非法 JSON 直接返回给模型。");
                broken.put("result", text);
                return ToolResultContract.normalize(toolName, broken);
            }
            return ToolResultContract.normalize(toolName, text);
        }
    }

    static final class AuditedToolBase extends ToolBase {
        private final ToolBase delegate;
        private final AgentScopeToolPipeline pipeline;

        AuditedToolBase(ToolBase delegate, AgentScopeToolPipeline pipeline) {
            super(builder(delegate));
            this.delegate = delegate;
            this.pipeline = pipeline;
        }

        private static ToolBase.Builder builder(ToolBase delegate) {
            ToolBase.Builder builder = ToolBase.builder()
                    .name(delegate.getName())
                    .description(delegate.getDescription())
                    .inputSchema(delegate.getParameters())
                    .readOnly(delegate.isReadOnly())
                    .concurrencySafe(delegate.isConcurrencySafe())
                    .externalTool(delegate.isExternalTool())
                    .stateInjected(delegate.isStateInjected());
            if (delegate.isMcp()) builder.mcp(delegate.getMcpName());
            return builder;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return pipeline.executeGeneric(delegate, param, delegate.callAsync(param)
                    .map(block -> new ToolBaseResult(block, auditValue(delegate.getName(), block))))
                    .map(result -> (ToolResultBlock) result.value());
        }
    }
}
