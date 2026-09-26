package com.moon.moonagent.ai.storyboard.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.AssetRef;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.OptimizationResult;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PlotAssessment;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RoleProfile;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ScriptChange;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ScriptRepairResult;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ShotGenerationRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryBible;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardProductionRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardSegment;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(prefix = "ai.storyboard.production-v2", name = "model-mode",
        havingValue = "live", matchIfMissing = true)
@RequiredArgsConstructor
public class SpringAiStoryboardModelClient implements StoryboardModelClient {

    private final ChatClient.Builder chatClientBuilder;
    private final GatewayModelService gatewayModelService;
    private final AgentAiConfigProperties agentConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile ChatClient client;

    @Value("${ai.storyboard.production-v2.fast-timeout-seconds:120}")
    private int fastTimeoutSeconds = 120;

    @Value("${ai.storyboard.production-v2.repair-timeout-seconds:90}")
    private int repairTimeoutSeconds = 90;

    private static final Pattern MISSING_ARRAY_COMMA = Pattern.compile("(?<=\\\")\\s+(?=\\\")");
    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");

    @Override
    public PlotAssessment assessPlot(StoryboardProductionRequest request, List<AssetRef> assets) {
        String system = """
                你是剧情完整性审查员。判断剧情是否具备主角目标、阻碍或冲突、因果推进、
                以及结局或明确续接点。只输出 JSON：
                {"complete":true或false,"missingElements":[],"risks":[],"summary":"",
                 "expansionPlan":"当不完整时给出可执行的扩写方案，否则为空"}。
                如果输入明确是建筑、产品、空间、风景等展示脚本，或用户明确不要剧情，
                完整性按展示路线、空间顺序、视觉重点、节奏和收束判断，不要求人物目标或冲突。
                不改变原意，不把未写明的信息当成已存在。
                """;
        JsonNode root = callJson(system, """
                原始剧情：
                %s

                可用资产：
                %s
                """.formatted(request.originalScript(), assetsText(assets)));
        return new PlotAssessment(
                root.path("complete").asBoolean(false),
                strings(root.path("missingElements")),
                strings(root.path("risks")),
                root.path("summary").asText(""),
                root.path("expansionPlan").asText("")
        );
    }

    @Override
    public OptimizationResult optimizeScript(StoryboardProductionRequest request, PlotAssessment assessment,
                                             List<RoleProfile> roles, List<AssetRef> assets, String feedback) {
        String system = """
                你是影视剧本总编。按指定专业角色共同优化剧情：
                保持用户核心设定和主线；补足人物动机、矛盾、因果、情绪弧光；
                删除只推动情节的工具人行为；职业行为必须符合相应专业流程。
                如果用户要求建筑、产品、空间、风景等展示，或明确不要剧情，
                则生成完整展示脚本：明确空间/产品动线、镜头顺序、视觉重点、节奏和收束，
                不得虚构人物冲突或无必要的叙事角色。
                只输出 JSON：
                {"optimizedScript":"保留原意并优化后的完整剧本",
                 "expansionScript":"仅在剧情不完整时给出扩写版本，否则为空",
                 "storyBible":{"logline":"","characters":[],"relationships":[],"worldRules":[],
                 "timeline":[],"continuity":[],"visualStyle":[]}}。
                """;
        String user = """
                原始剧情：
                %s

                完整性判断：
                %s

                选中的专业角色：
                %s

                资产约束：
                %s

                用户修改意见：
                %s
                """.formatted(
                request.originalScript(),
                objectJson(assessment),
                rolesText(roles),
                assetsText(assets),
                feedback == null ? "" : feedback);
        JsonNode root = callJson(system, user,
                gatewayModelService.getDefaultTextModel(), 120);
        return new OptimizationResult(
                root.path("optimizedScript").asText(""),
                root.path("expansionScript").asText(""),
                readStoryBible(root.path("storyBible"))
        );
    }

    @Override
    public ScriptRepairResult repairScript(StoryboardProductionRequest request,
                                           PlotAssessment assessment,
                                           List<RoleProfile> roles,
                                           List<AssetRef> assets) {
        String system = """
                你是影视剧本修稿师。只修复诊断中确认的具体问题，不扩写整篇剧情，不改变未涉及内容。
                每条修改必须包含一段在原文中只出现一次的原句，并用替换句修复对应问题。
                建筑、产品、空间、风景展示脚本不得通过补人物冲突来修复问题。
                只输出 JSON：
                {"changes":[{"issue":"","originalText":"","replacement":"","reason":""}],
                 "storyBible":{"logline":"","characters":[],"relationships":[],"worldRules":[],
                 "timeline":[],"continuity":[],"visualStyle":[]}}。
                如果某项风险无需修改文本，不要为它虚构修改。
                """;
        String user = """
                原始剧情：
                %s

                诊断结果：
                %s

                专业角色：
                %s

                资产约束：
                %s
                """.formatted(
                request.originalScript(),
                objectJson(assessment),
                rolesText(roles),
                assetsText(assets));
        JsonNode root = callJson(system, user,
                gatewayModelService.getDefaultTextModel(), repairTimeoutSeconds);
        List<ScriptChange> changes = new ArrayList<>();
        JsonNode changeNodes = root.path("changes");
        if (changeNodes.isArray()) {
            for (JsonNode change : changeNodes) {
                changes.add(new ScriptChange(
                        change.path("issue").asText(""),
                        change.path("originalText").asText(""),
                        change.path("replacement").asText(""),
                        change.path("reason").asText("")));
            }
        }
        return new ScriptRepairResult(
                applyChanges(request.originalScript(), changes),
                readStoryBible(root.path("storyBible")),
                changes);
    }

    @Override
    public List<StoryboardSegment> segmentStory(String script, StoryBible storyBible) {
        String system = """
                你是长剧情分段规划器。按幕、场或完整叙事单元切分，不按单句机械切分。
                每个 segment 必须覆盖连续且不重叠的原文，并给出边界摘要。
                只输出 JSON：{"segments":[{"segmentId":"segment-1","ordinal":1,
                "title":"","sourceText":"","boundarySummary":""}]}。
                """;
        JsonNode root = callJson(system, "剧本：\n" + script + "\n\nStoryBible：\n" + objectJson(storyBible));
        List<StoryboardSegment> segments = new ArrayList<>();
        JsonNode array = root.path("segments");
        if (array.isArray()) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode item = array.get(index);
                String id = item.path("segmentId").asText("segment-" + (index + 1));
                segments.add(new StoryboardSegment(
                        id,
                        item.path("ordinal").asInt(index + 1),
                        item.path("title").asText(""),
                        item.path("sourceText").asText(""),
                        item.path("boundarySummary").asText("")
                ));
            }
        }
        if (segments.isEmpty()) {
            segments.add(new StoryboardSegment("segment-1", 1, "完整剧情", script, ""));
        }
        return List.copyOf(segments);
    }

    @Override
    public List<PseudoShot> planPseudoShots(StoryboardProductionRequest request, StoryboardSegment segment,
                                            StoryBible storyBible, List<RoleProfile> roles, List<AssetRef> assets) {
        String system = """
                你是结构化伪分镜师。按叙事节拍写出可确认的镜头，不生成图片/视频参数。
                严格证据绑定资产：只有在剧情明确出现、用户明确指定或专业角色指出必要时，
                才把对应资产 ID 放入 referenceAssetIds；不确定必须为空。
                只输出 JSON：{"shots":[{"shotId":"","segmentId":"","shotOrdinal":1,
                "narrativePurpose":"","characters":[],"location":"","time":"","conflict":"",
                "action":"","emotion":"","continuity":[],"sourceExcerpt":[],
                "referenceAssetIds":[]}]}。
                """;
        JsonNode root = callJson(system, """
                完整剧本：
                %s

                当前分段：
                %s

                StoryBible：
                %s

                专业角色：
                %s

                可用资产：
                %s
                """.formatted(scriptContext(request, segment), objectJson(segment), objectJson(storyBible),
                rolesText(roles), assetsText(assets)));
        return parsePseudoShots(root.path("shots"));
    }

    @Override
    public List<PseudoShot> revisePseudoShots(List<PseudoShot> shots, String instruction, String shotId,
                                              StoryBible storyBible, List<AssetRef> assets) {
        String system = """
                你是结构化伪分镜修稿器。只修改用户指定的内容，保持未指定镜头和稳定编号不变。
                严格保持资产证据绑定；不确定时清空 referenceAssetIds。
                只输出 JSON：{"shots":[全部伪分镜，字段与输入一致]}。
                """;
        JsonNode root = callJson(system, """
                当前伪分镜：
                %s

                修改要求：
                %s

                指定镜头：
                %s

                StoryBible：
                %s

                可用资产：
                %s
                """.formatted(objectJson(shots), instruction, shotId == null ? "" : shotId,
                objectJson(storyBible), assetsText(assets)));
        return parsePseudoShots(root.path("shots"));
    }

    @Override
    public StoryboardShot generateShot(ShotGenerationRequest request) {
        PseudoShot pseudo = request.pseudoShot();
        String system = """
                你是专业分镜师和视频导演。只把一个已确认的伪分镜扩写为一个正式镜头。
                不得新增、删除、重排镜头，不得改变镜头编号和资产绑定。
                只输出 JSON 对象：{"shotId":"","segmentId":"","shotOrdinal":1,
                "sceneDescription":"","shotType":"","cameraMovement":"","durationSeconds":3,
                "imagePrompt":"","videoPrompt":"","negativePrompt":"","referenceHints":[]}。
                sceneDescription、提示词和运镜必须使用中文。
                """;
        JsonNode root = callJson(system, """
                伪分镜：
                %s

                StoryBible：
                %s

                严格绑定的资产：
                %s

                资产引用标记必须只在 referenceHints 中使用；不得使用未绑定资产。
                """.formatted(objectJson(pseudo), objectJson(request.storyBible()),
                assetsForPseudo(request.assets(), pseudo)));
        int duration = root.path("durationSeconds").asInt(3);
        if (duration <= 0) duration = 3;
        return new StoryboardShot(
                pseudo.shotId(),
                pseudo.segmentId(),
                pseudo.shotOrdinal(),
                nonBlank(root.path("sceneDescription").asText(""), pseudo.action()),
                nonBlank(root.path("shotType").asText(""), "中景"),
                nonBlank(root.path("cameraMovement").asText(""), "固定镜头"),
                duration,
                nonBlank(root.path("imagePrompt").asText(""), pseudo.action()),
                nonBlank(root.path("videoPrompt").asText(""), pseudo.action()),
                root.path("negativePrompt").asText(""),
                strings(root.path("referenceHints"))
        );
    }

    private JsonNode callJson(String system, String user) {
        return callJson(system, user, gatewayModelService.getDefaultTextModel(), fastTimeoutSeconds);
    }

    private JsonNode callJson(String system, String user, String model, int timeoutSeconds) {
        String raw = invokeModel(system, user, model, timeoutSeconds);
        String json = extractJsonObject(raw);
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            String repaired = repairJson(json);
            try {
                return objectMapper.readTree(repaired);
            } catch (Exception repairedError) {
                String retryRaw = invokeModel(
                        system + "\n上一次输出不是合法 JSON。只输出修复后的合法 JSON，不要解释、不要代码块。",
                        "原始输出：\n" + raw, model, timeoutSeconds);
                String retryJson = repairJson(extractJsonObject(retryRaw));
                try {
                    return objectMapper.readTree(retryJson);
                } catch (Exception retryError) {
                    throw new BusinessException(50201,
                            "storyboard production model returned invalid JSON", retryError);
                }
            }
        }
    }

    private String invokeModel(String system, String user, String model, int timeoutSeconds) {
        String raw;
        try {
            raw = client().prompt()
                    .options(OpenAiChatOptions.builder()
                            .model(model)
                            .maxRetries(0)
                            .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds))))
                    .system(system)
                    .user(user)
                    .call()
                    .content();
        } catch (Exception e) {
            throw new BusinessException(50201, "storyboard production model call failed: " + e.getMessage(), e);
        }
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(50201, "storyboard production model returned empty content");
        }
        return raw;
    }

    private ChatClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) client = chatClientBuilder.build();
            }
        }
        return client;
    }

    private List<PseudoShot> parsePseudoShots(JsonNode array) {
        List<PseudoShot> shots = new ArrayList<>();
        if (!array.isArray()) return shots;
        for (int index = 0; index < array.size(); index++) {
            JsonNode item = array.get(index);
            shots.add(new PseudoShot(
                    item.path("shotId").asText("shot-" + (index + 1)),
                    item.path("segmentId").asText("segment-1"),
                    item.path("shotOrdinal").asInt(index + 1),
                    item.path("narrativePurpose").asText(""),
                    strings(item.path("characters")),
                    item.path("location").asText(""),
                    item.path("time").asText(""),
                    item.path("conflict").asText(""),
                    item.path("action").asText(""),
                    item.path("emotion").asText(""),
                    strings(item.path("continuity")),
                    strings(item.path("sourceExcerpt")),
                    strings(item.path("referenceAssetIds"))
            ));
        }
        return shots;
    }

    private StoryBible readStoryBible(JsonNode node) {
        return new StoryBible(
                node.path("logline").asText(""),
                strings(node.path("characters")),
                strings(node.path("relationships")),
                strings(node.path("worldRules")),
                strings(node.path("timeline")),
                strings(node.path("continuity")),
                strings(node.path("visualStyle"))
        );
    }

    private String scriptContext(StoryboardProductionRequest request, StoryboardSegment segment) {
        String source = segment.sourceText();
        return source == null || source.isBlank() ? request.originalScript() : source;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.asText("").isBlank()) values.add(item.asText(""));
        }
        return List.copyOf(values);
    }

    private static String rolesText(List<RoleProfile> roles) {
        if (roles == null || roles.isEmpty()) return "（无）";
        StringBuilder builder = new StringBuilder();
        for (RoleProfile role : roles) {
            builder.append("- ").append(role.label()).append(": ").append(role.instructions()).append('\n');
        }
        return builder.toString();
    }

    private static String assetsText(List<AssetRef> assets) {
        if (assets == null || assets.isEmpty()) return "（无）";
        StringBuilder builder = new StringBuilder();
        for (AssetRef asset : assets) {
            builder.append("- id=").append(asset.assetId())
                    .append(", type=").append(asset.type())
                    .append(", url=").append(asset.url())
                    .append(", purpose=").append(asset.purpose()).append('\n');
        }
        return builder.toString();
    }

    private static String assetsForPseudo(List<AssetRef> assets, PseudoShot pseudo) {
        if (pseudo.referenceAssetIds().isEmpty()) return "（该镜严格绑定为空）";
        return assets.stream().filter(asset -> pseudo.referenceAssetIds().contains(asset.assetId()))
                .map(asset -> "- id=" + asset.assetId() + ", type=" + asset.type()
                        + ", purpose=" + asset.purpose() + ", url=" + asset.url())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("（绑定资产不在运行时快照中，禁止使用）");
    }

    private String objectJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : raw;
    }

    static String repairJson(String raw) {
        String repaired = raw == null ? "" : raw.strip();
        repaired = repaired.replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "");
        repaired = MISSING_ARRAY_COMMA.matcher(repaired).replaceAll(", ");
        repaired = TRAILING_COMMA.matcher(repaired).replaceAll("$1");
        return repaired;
    }

    static String applyChanges(String original, List<ScriptChange> changes) {
        String revised = original == null ? "" : original;
        if (changes == null || changes.isEmpty()) return revised;
        for (ScriptChange change : changes) {
            if (change == null || change.originalText().isBlank()) {
                throw new BusinessException(50201, "剧情修正缺少可定位的原句");
            }
            String target = change.originalText();
            int first = revised.indexOf(target);
            int last = revised.lastIndexOf(target);
            if (first < 0 || first != last) {
                throw new BusinessException(50201,
                        "剧情修正原句无法唯一定位: " + target);
            }
            revised = revised.substring(0, first)
                    + change.replacement()
                    + revised.substring(first + target.length());
        }
        return revised;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
