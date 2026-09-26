package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Guards media side effects before the underlying tool executes. Simple,
 * explicit requests pass through without a model call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaToolPreflightService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> MEDIA_TOOLS = Set.of(
            AgentScopeImageTools.GENERATE_IMAGE,
            AgentScopeImageTools.GENERATE_IMAGE_BATCH,
            AgentScopeVideoTools.GENERATE_VIDEO);
    private static final List<String> REFERENTIAL_PHRASES = List.of(
            "这张", "那张", "刚才", "上一张", "上一张图", "继续完善", "用它",
            "这个图", "该图", "同一个人", "同一场景", "参考刚才", "沿用");

    private final AgentScopeProperties properties;
    private final StructuredDecisionService decisions;
    private final SessionAssetService sessionAssets;
    private final MeterRegistry meters;

    public boolean appliesTo(String toolName) {
        return MEDIA_TOOLS.contains(toolName);
    }

    public Map<String, Object> guard(String toolName, Map<String, Object> input,
                                     ToolCallParam param,
                                     Function<Map<String, Object>, Map<String, Object>> invocation) {
        if (!MEDIA_TOOLS.contains(toolName) || !properties.getPreflight().isEnabled()) {
            return invocation.apply(input == null ? Map.of() : input);
        }
        AgentScopeRunContext context = context(param);
        if (context == null || context.request() == null) {
            return invocation.apply(input == null ? Map.of() : input);
        }
        long started = System.nanoTime();
        Map<String, Object> original = input == null ? Map.of() : new LinkedHashMap<>(input);
        String requestText = safe(context.request().getContent());
        Trigger trigger = trigger(toolName, original, context, requestText);
        List<String> issues = deterministicIssues(toolName, original, context);
        if (!trigger.reviewRequired()) {
            if (!issues.isEmpty()) {
                return ask(toolName, "none", questionFor(issues), issues, context, started);
            }
            return execute(toolName, "none", original, invocation, context, started, "EXECUTE", issues);
        }

        Map<String, Object> candidateInput = original;
        AgentScopeStructuredModels.MediaActionReview review = null;
        if (trigger.referenceResolution()) {
            List<SessionAssetRef> candidates = sessionAssets.list(
                    context.conversation() == null ? "" : context.conversation().getId(),
                    null, properties.getAssetSearchLimit(), true);
            if (candidates.isEmpty()) {
                return ask(toolName, trigger.name(),
                        "你指的是哪一条历史素材？请说明图片内容或重新上传。",
                        List.of("reference candidates missing"), context, started);
            }
            AgentScopeStructuredModels.ReferenceSelection selection =
                    decisions.selectReferences(requestText, candidates);
            if (selection.confidence() < properties.getPreflight()
                    .getReferenceConfidenceThreshold()
                    || selection.assetIds().isEmpty()) {
                return ask(toolName, trigger.name(),
                        "你指的是哪一张历史图片？请说明图片内容，避免使用错误参考图。",
                        List.of(selection.rationale()), context, started);
            }
            candidateInput = withReferenceIds(toolName, original, selection.assetIds());
        } else {
            review = decisions.reviewMediaAction(
                    toolName, JSON.valueToTree(original), turnContext(context), List.of());
            if (modelUnavailable(review)) {
                if (issues.isEmpty() && explicit(requestText, trigger)) {
                    return execute(toolName, trigger.name(), original, invocation,
                            context, started, "FAIL_OPEN", issues);
                }
                return ask(toolName, trigger.name(), questionFor(issues),
                        List.of("preflight model unavailable"), context, started);
            }
            String decision = upper(review.decision());
            if ("ASK".equals(decision)) {
                return ask(toolName, trigger.name(),
                        review.question().isBlank() ? "请补充一个必要信息后继续。" : review.question(),
                        review.reasons(), context, started);
            }
            if ("REJECT".equals(decision)) {
                return reject(toolName, trigger.name(),
                        review.reasons().isEmpty() ? "媒体请求无法安全执行" : review.reasons().getFirst(),
                        context, started);
            }
            if ("REPAIR".equals(decision)) {
                candidateInput = mergeInput(original, review.repairedInput());
            }
        }

        List<String> repairedIssues = deterministicIssues(toolName, candidateInput, context);
        if (!repairedIssues.isEmpty()) {
            return ask(toolName, trigger.name(), questionFor(repairedIssues),
                    repairedIssues, context, started);
        }
        String finalDecision = review != null && "REPAIR".equals(upper(review.decision()))
                ? "REPAIR" : "EXECUTE";
        return execute(toolName, trigger.name(), candidateInput, invocation,
                context, started, finalDecision,
                review == null ? List.of() : review.reasons());
    }

    private Map<String, Object> execute(String toolName, String trigger, Map<String, Object> input,
                                        Function<Map<String, Object>, Map<String, Object>> invocation,
                                        AgentScopeRunContext context, long started,
                                        String decision, List<String> reasons) {
        Map<String, Object> result = invocation.apply(input);
        if (result == null) result = Map.of();
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("preflightDecision", decision);
        if (!"none".equals(trigger)) out.put("preflightTrigger", trigger);
        record(toolName, trigger, decision, started, reasons, context, "media_preflight_completed");
        return out;
    }

    private Map<String, Object> ask(String toolName, String trigger, String question,
                                    List<String> reasons, AgentScopeRunContext context,
                                    long started) {
        if (context.turnState() != null) context.turnState().recordPreflightFailure(toolName);
        record(toolName, trigger, "ASK", started, reasons, context, "media_preflight_asked");
        return Map.of(
                "ok", false,
                "needsClarification", true,
                "question", question,
                "instruction", "请调用 request_human_input，把这个聚焦问题原样发给用户。",
                "preflightDecision", "ASK");
    }

    private Map<String, Object> reject(String toolName, String trigger, String error,
                                       AgentScopeRunContext context, long started) {
        if (context.turnState() != null) context.turnState().recordPreflightFailure(toolName);
        record(toolName, trigger, "REJECT", started, List.of(error), context, "media_preflight_rejected");
        return Map.of(
                "ok", false,
                "rejected", true,
                "error", error,
                "preflightDecision", "REJECT");
    }

    private List<String> deterministicIssues(String toolName, Map<String, Object> input,
                                             AgentScopeRunContext context) {
        List<String> issues = new ArrayList<>();
        String prompt = safe(input.get("prompt"));
        List<String> prompts = stringList(input.get("prompts"));
        int count = requestedCount(input, prompts);
        if (AgentScopeImageTools.GENERATE_IMAGE_BATCH.equals(toolName)) {
            if (prompt.isBlank() && prompts.isEmpty()) issues.add("批量生图缺少提示词");
            if (count > properties.getBatchConfirmThreshold()) {
                issues.add("批量图片数量需要用户确认");
            }
        } else if (prompt.isBlank()) {
            issues.add("缺少媒体生成提示词");
        }

        List<String> assetIds = new ArrayList<>();
        assetIds.addAll(stringList(input.get("source_asset_ids")));
        assetIds.addAll(stringList(input.get("reference_asset_ids")));
        if (!assetIds.isEmpty() && context.conversation() != null) {
            Set<String> existing = sessionAssets.existingAssetIds(
                    context.conversation().getId(), assetIds);
            for (String assetId : new LinkedHashSet<>(assetIds)) {
                if (!existing.contains(assetId)) issues.add("参考资产不存在：" + assetId);
            }
        }

        if (context.turnState() != null) {
            String mediaType = AgentScopeVideoTools.GENERATE_VIDEO.equals(toolName)
                    ? "video" : "image";
            if (context.turnState().hasStartedMedia(mediaType)) {
                issues.add("本轮已经执行过同类媒体任务，请复用已有结果");
            }
        }
        return issues;
    }

    private Trigger trigger(String toolName, Map<String, Object> input,
                            AgentScopeRunContext context, String requestText) {
        if (context.turnState() != null
                && context.turnState().referenceInputMode()
                == AgentScopeTurnState.ReferenceInputMode.MIXED) {
            return new Trigger("mixed_reference", true);
        }
        if (hasReferentialPhrase(requestText)) {
            return new Trigger("reference_resolution", true);
        }
        if (AgentScopeImageTools.GENERATE_IMAGE_BATCH.equals(toolName)) {
            return new Trigger("batch_image", false);
        }
        if (requestedCount(input, stringList(input.get("prompts"))) > 1) {
            return new Trigger("batch_image", false);
        }
        if (requestText.strip().length() < 12) {
            return new Trigger("short_prompt", false);
        }
        int references = stringList(input.get("reference_asset_ids")).size()
                + stringList(input.get("source_asset_ids")).size();
        if (AgentScopeVideoTools.GENERATE_VIDEO.equals(toolName) && references > 1) {
            return new Trigger("multi_reference_video", false);
        }
        if (context.turnState() != null
                && context.turnState().preflightFailures(toolName) > 0) {
            return new Trigger("previous_failure", false);
        }
        return Trigger.none();
    }

    private static boolean modelUnavailable(AgentScopeStructuredModels.MediaActionReview review) {
        return review == null || review.confidence() <= 0.0
                || review.reasons().contains("fallback");
    }

    private static boolean explicit(String requestText, Trigger trigger) {
        return !requestText.isBlank() && !trigger.referenceResolution();
    }

    private static String turnContext(AgentScopeRunContext context) {
        OrchestrationRequest request = context.request();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("request", safe(request.getContent()));
        values.put("referenceMode", context.turnState() == null
                ? "NONE" : context.turnState().referenceInputMode().name());
        values.put("selectedAssetIds", request.getAssetIds() == null
                ? List.of() : request.getAssetIds());
        values.put("attachedImageCount", request.getPicUrls() == null
                ? 0 : request.getPicUrls().size());
        return values.toString();
    }

    private void record(String toolName, String trigger, String decision, long started,
                        List<String> reasons, AgentScopeRunContext context, String status) {
        try {
            Counter.builder("agentscope_preflight_total")
                    .tag("tool", toolName)
                    .tag("decision", decision)
                    .tag("trigger", trigger)
                    .register(meters)
                    .increment();
            if ("REPAIR".equals(decision)) {
                Counter.builder("agentscope_preflight_repair_total")
                        .tag("tool", toolName)
                        .register(meters)
                        .increment();
            }
            if ("ASK".equals(decision)) {
                Counter.builder("agentscope_reference_clarification_total")
                        .tag("tool", toolName)
                        .register(meters)
                        .increment();
            }
            Timer.builder("agentscope_preflight_latency")
                    .tag("tool", toolName)
                    .tag("decision", decision)
                    .register(meters)
                    .record(Duration.ofNanos(System.nanoTime() - started));
        } catch (Exception e) {
            log.debug("preflight metric failed: {}", e.getMessage());
        }
        if (context.trace() != null) {
            try {
                String summary = "decision=" + decision + ", trigger=" + trigger
                        + ", confidenceReasons=" + String.join("|", reasons);
                context.trace().add("agentscope-preflight", toolName,
                        "媒体请求预检", summary, "", "", status);
            } catch (Exception ignored) {
                // Trace is observability only.
            }
        }
    }

    private static Map<String, Object> withReferenceIds(
            String toolName, Map<String, Object> input, List<String> assetIds) {
        Map<String, Object> merged = new LinkedHashMap<>(input);
        String key = AgentScopeVideoTools.GENERATE_VIDEO.equals(toolName)
                ? "reference_asset_ids" : "source_asset_ids";
        LinkedHashSet<String> ids = new LinkedHashSet<>(stringList(input.get(key)));
        ids.addAll(assetIds);
        merged.put(key, List.copyOf(ids));
        return merged;
    }

    private static Map<String, Object> mergeInput(
            Map<String, Object> original, Map<String, Object> repaired) {
        Map<String, Object> merged = new LinkedHashMap<>(original);
        if (repaired != null) {
            repaired.forEach((key, value) -> {
                if (value != null) merged.put(key, value);
            });
        }
        return merged;
    }

    private static String questionFor(List<String> issues) {
        if (issues == null || issues.isEmpty()) return "请补充一个必要信息后继续。";
        return issues.getFirst();
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static int requestedCount(Map<String, Object> input, List<String> prompts) {
        if (prompts != null && !prompts.isEmpty()) return prompts.size();
        Object value = input == null ? null : input.get("n");
        if (value instanceof Number number) return Math.max(1, number.intValue());
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(value)));
        } catch (Exception ignored) {
            return 1;
        }
    }

    private static boolean hasReferentialPhrase(String text) {
        String value = safe(text);
        return REFERENTIAL_PHRASES.stream().anyMatch(value::contains);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(String::valueOf).toList();
    }

    private static AgentScopeRunContext context(ToolCallParam param) {
        return param == null || param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().get(AgentScopeRunContext.class);
    }

    private record Trigger(String name, boolean referenceResolution) {
        static Trigger none() {
            return new Trigger("none", false);
        }

        boolean reviewRequired() {
            return !"none".equals(name);
        }
    }
}
