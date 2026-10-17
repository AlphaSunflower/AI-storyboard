package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.creative.ConstraintExtractionService;
import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.CreativeCritiqueService;
import com.moon.moonagent.ai.agent.creative.MediaCritique;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContext;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.ai.agent.creative.TimelineManifest;
import com.moon.moonagent.ai.agent.creative.TimelineManifestService;
import com.moon.moonagent.entity.AgentConversation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AgentScopeCreativeTools {

    public static final String GET_CONTEXT = "get_creative_context";
    public static final String UPDATE_CONTEXT = "update_creative_context";
    public static final String EXTRACT_CONSTRAINTS = "extract_creative_constraints";
    public static final String REVIEW_MEDIA = "review_generated_media";
    public static final String LIST_CRITIQUES = "get_media_critiques";
    public static final String SAVE_TIMELINE = "save_timeline_manifest";
    public static final String GET_TIMELINE = "get_timeline_manifest";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProjectCreativeContextService contextService;
    private final ConstraintExtractionService extractionService;
    private final CreativeCritiqueService critiqueService;
    private final TimelineManifestService timelineService;
    private final AgentScopeProperties properties;

    /**
     * 生成结果诊断工具默认不注册：{@code review_generated_media} 每次调用都是一次付费视觉
     * 模型评审，历史事故是模型在每次生图后主动自检并触发修复重生成，费用与延迟都翻倍。
     * 只有显式打开 {@code agentscope.critique-enabled} 才把它交回模型。
     */
    public List<AgentScopeTool> tools() {
        List<AgentScopeTool> tools = new ArrayList<>(List.of(
                getContext(),
                updateContext(),
                extractConstraints(),
                listCritiques(),
                saveTimeline(),
                getTimeline()));
        if (properties.isCritiqueEnabled()) {
            tools.add(reviewMedia());
        }
        return List.copyOf(tools);
    }

    private AgentScopeTool getContext() {
        return new AgentScopeTool(
                GET_CONTEXT,
                "Read the current project's creative memory. This is scoped to the current project only.",
                AgentScopeTool.schema(Map.of(), List.of()),
                true, true, false,
                (input, param) -> {
                    AgentScopeRunContext context = AgentScopeToolPipeline.context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    ProjectCreativeContext value = contextService.get(
                            conversation.getUserId(), conversation.getProjectId());
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("projectId", value.projectId());
                    result.put("objective", value.objective());
                    result.put("creativeDirection", value.creativeDirection());
                    result.put("visualStyle", value.visualStyle());
                    result.put("references", value.references());
                    result.put("hardConstraints", value.hardConstraints());
                    result.put("softConstraints", value.softConstraints());
                    result.put("approvedAssetIds", value.approvedAssetIds());
                    result.put("rejectedDirections", value.rejectedDirections());
                    result.put("feedback", value.feedback());
                    result.put("modelPreferences", value.modelPreferences());
                    result.put("version", value.version());
                    return result;
                });
    }

    private AgentScopeTool updateContext() {
        return new AgentScopeTool(
                UPDATE_CONTEXT,
                "Merge durable project-level creative facts. Use only for facts that should survive "
                        + "future conversations in the same project, never for one-off instructions.",
                AgentScopeTool.schema(Map.of(
                        "objective", Map.of("type", "string"),
                        "creative_direction", Map.of("type", "string"),
                        "visual_style", Map.of("type", "string"),
                        "references", Map.of("type", "array", "items", Map.of("type", "object")),
                        "hard_constraints", Map.of("type", "array", "items", Map.of("type", "object")),
                        "soft_constraints", Map.of("type", "array", "items", Map.of("type", "object")),
                        "approved_asset_ids", Map.of("type", "array", "items", Map.of("type", "string")),
                        "rejected_directions", Map.of("type", "array", "items", Map.of("type", "string")),
                        "feedback", Map.of("type", "array", "items", Map.of("type", "string")),
                        "model_preferences", Map.of("type", "object")), List.of()),
                false, true, false,
                (input, param) -> {
                    AgentScopeRunContext context = AgentScopeToolPipeline.context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    ProjectCreativeContextService.CreativeContextPatch patch =
                            new ProjectCreativeContextService.CreativeContextPatch(
                                    text(input.get("objective")),
                                    text(input.get("creative_direction")),
                                    text(input.get("visual_style")),
                                    value(input.get("references"),
                                            new TypeReference<List<ProjectCreativeContext.Reference>> () {}),
                                    value(input.get("hard_constraints"),
                                            new TypeReference<List<ConstraintLedger.ConstraintItem>> () {}),
                                    value(input.get("soft_constraints"),
                                            new TypeReference<List<ConstraintLedger.ConstraintItem>> () {}),
                                    value(input.get("approved_asset_ids"),
                                            new TypeReference<List<String>> () {}),
                                    value(input.get("rejected_directions"),
                                            new TypeReference<List<String>> () {}),
                                    value(input.get("feedback"),
                                            new TypeReference<List<String>> () {}),
                                    value(input.get("model_preferences"),
                                            new TypeReference<Map<String, String>> () {}));
                    ProjectCreativeContext updated = contextService.patch(
                            conversation.getUserId(), conversation.getProjectId(), patch);
                    return Map.of(
                            "ok", true,
                            "projectId", updated.projectId(),
                            "version", updated.version(),
                            "updatedAt", updated.updatedAt());
                });
    }

    private AgentScopeTool extractConstraints() {
        return new AgentScopeTool(
                EXTRACT_CONSTRAINTS,
                "Extract hard and soft constraints from a user prompt. Hard constraints must be "
                        + "preserved in strict mode; unresolved constraints require clarification.",
                AgentScopeTool.schema(Map.of(
                        "prompt", Map.of("type", "string")), List.of("prompt")),
                true, true, false,
                (input, param) -> {
                    ConstraintLedger ledger = extractionService.extract(text(input.get("prompt")));
                    return Map.of(
                            "mode", ledger.mode(),
                            "hard", ledger.hard(),
                            "soft", ledger.soft(),
                            "unresolved", ledger.unresolved());
                });
    }

    private AgentScopeTool reviewMedia() {
        return new AgentScopeTool(
                REVIEW_MEDIA,
                "Review generated media frames against the current request and project hard "
                        + "constraints. Use after image or video generation and before claiming success.",
                AgentScopeTool.schema(Map.of(
                        "prompt", Map.of("type", "string"),
                        "media_type", Map.of("type", "string"),
                        "frame_urls", Map.of("type", "array", "items", Map.of("type", "string")),
                        "video_url", Map.of("type", "string"),
                        "item_id", Map.of("type", "string"),
                        "repair_attempt", Map.of("type", "integer")), List.of("frame_urls")),
                true, true, false,
                (input, param) -> {
                    AgentScopeRunContext context = AgentScopeToolPipeline.context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    ConstraintLedger ledger = extractionService.extract(
                            context.request() == null ? "" : context.request().getContent());
                    String mediaType = text(input.get("media_type"));
                    if (mediaType == null || mediaType.isBlank()) mediaType = "image";
                    String videoUrl = text(input.get("video_url"));
                    MediaCritique critique = "video".equalsIgnoreCase(mediaType)
                            && videoUrl != null && !videoUrl.isBlank()
                            ? critiqueService.reviewVideo(
                            conversation,
                            context.request() == null ? "" : context.request().getTurnId(),
                            text(input.get("item_id")),
                            text(input.get("prompt")),
                            videoUrl,
                            ledger,
                            integer(input.get("repair_attempt"), 0))
                            : critiqueService.reviewMedia(
                            conversation,
                            context.request() == null ? "" : context.request().getTurnId(),
                            text(input.get("item_id")),
                            mediaType,
                            text(input.get("prompt")),
                            stringList(input.get("frame_urls")),
                            ledger,
                            integer(input.get("repair_attempt"), 0));
                    return critiqueService.asToolResult(critique);
                });
    }

    private AgentScopeTool listCritiques() {
        return new AgentScopeTool(
                LIST_CRITIQUES,
                "List persisted media critiques for the current turn.",
                AgentScopeTool.schema(Map.of(), List.of()),
                true, true, false,
                (input, param) -> {
                    AgentScopeRunContext context = AgentScopeToolPipeline.context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    String turnId = context.request() == null ? "" : context.request().getTurnId();
                    return critiqueService.listByTurn(
                            conversation.getProjectId(), conversation.getId(), turnId);
                });
    }

    private AgentScopeTool saveTimeline() {
        return new AgentScopeTool(
                SAVE_TIMELINE,
                "Persist a timeline manifest before assembling a multi-shot video. Missing clips "
                        + "must remain explicit and must never be silently skipped.",
                AgentScopeTool.schema(Map.of(
                        "manifest", Map.of("type", "object")), List.of("manifest")),
                false, false, false,
                (input, param) -> {
                    AgentScopeRunContext context = AgentScopeToolPipeline.context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    TimelineManifest manifest = JSON.convertValue(
                            input.get("manifest"), TimelineManifest.class);
                    TimelineManifest saved = timelineService.save(
                            conversation,
                            context.request() == null ? "" : context.request().getTurnId(),
                            manifest);
                    return Map.of(
                            "ok", true,
                            "manifestId", saved.id(),
                            "sourceUrls", saved.sourceUrls(),
                            "totalDurationSeconds", saved.totalDurationSeconds());
                });
    }

    private AgentScopeTool getTimeline() {
        return new AgentScopeTool(
                GET_TIMELINE,
                "Read a timeline manifest in the current project and conversation.",
                AgentScopeTool.schema(Map.of(
                        "manifest_id", Map.of("type", "string")), List.of()),
                true, true, false,
                (input, param) -> {
                    AgentScopeRunContext context = AgentScopeToolPipeline.context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    String id = text(input.get("manifest_id"));
                    TimelineManifest manifest = id.isBlank()
                            ? timelineService.latest(conversation.getProjectId(), conversation.getId())
                            : timelineService.get(
                            conversation.getProjectId(), conversation.getId(), id);
                    return manifest == null ? Map.of("found", false) : Map.of(
                            "found", true,
                            "manifest", manifest,
                            "sourceUrls", manifest.sourceUrls());
                });
    }

    private static <T> T value(Object raw, TypeReference<T> type) {
        if (raw == null) return null;
        try {
            return JSON.convertValue(raw, type);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).map(String::trim)
                .filter(item -> !item.isBlank()).toList();
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
