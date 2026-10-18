package com.moon.moonagent.ai.storyboard.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.AssetRef;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.OptimizationResult;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.OutlinePlan;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(prefix = "ai.storyboard.production-v2", name = "model-mode",
        havingValue = "live", matchIfMissing = true)
@RequiredArgsConstructor
public class SpringAiStoryboardModelClient implements StoryboardModelClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiStoryboardModelClient.class);

    private final ChatClient.Builder chatClientBuilder;
    private final GatewayModelService gatewayModelService;
    private final AgentAiConfigProperties agentConfig;
    private final NormalizedChatStreamClient normalizedStreamClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile ChatClient client;

    @Value("${ai.storyboard.production-v2.fast-timeout-seconds:120}")
    private int fastTimeoutSeconds = 120;

    /** 批量扩写的超时：输出是 N 镜之和，必须比单镜更宽松。 */
    @Value("${ai.storyboard.production-v2.batch-timeout-seconds:240}")
    private int batchTimeoutSeconds = 240;

    /**
     * 分镜模型调用的输出上限（token）。
     *
     * <p>必须显式传：网关的 {@code model_params.max_tokens} 只是 {@code /v1/models} 的展示值，
     * 不会注入请求，所以不写就意味着"上游默认"——批量扩写 6 镜约 2500+ token，
     * 上游默认偏小时会被截断成半截 JSON。
     */
    @Value("${ai.storyboard.production-v2.max-output-tokens:8192}")
    private int maxOutputTokens = 8192;

    @Value("${ai.storyboard.production-v2.repair-timeout-seconds:90}")
    private int repairTimeoutSeconds = 90;

    private static final Pattern MISSING_ARRAY_COMMA = Pattern.compile("(?<=\\\")\\s+(?=\\\")");
    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");

    @Override
    public PlotAssessment assessPlot(StoryboardProductionRequest request, List<AssetRef> assets) {
        return assessPlot(request, assets, StoryboardStreamSink.NOOP);
    }

    @Override
    public PlotAssessment assessPlot(StoryboardProductionRequest request, List<AssetRef> assets,
                                     StoryboardStreamSink sink) {
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
                """.formatted(request.originalScript(), assetsText(assets)),
                gatewayModelService.getDefaultTextModel(), fastTimeoutSeconds,
                "plot_diagnosis", sink,
                field("summary", "诊断结论"), field("expansionPlan", "修正建议"));
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
        return optimizeScript(request, assessment, roles, assets, feedback, StoryboardStreamSink.NOOP);
    }

    @Override
    public OptimizationResult optimizeScript(StoryboardProductionRequest request, PlotAssessment assessment,
                                             List<RoleProfile> roles, List<AssetRef> assets, String feedback,
                                             StoryboardStreamSink sink) {
        String system = scriptOptimizeSystemPrompt();
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
                gatewayModelService.getDefaultTextModel(), 120, "script_optimization", sink,
                field("optimizedScript", "优化后的剧情"),
                field("expansionScript", "扩写剧情"));
        return new OptimizationResult(
                root.path("optimizedScript").asText(""),
                root.path("expansionScript").asText(""),
                readStoryBible(root.path("storyBible"))
        );
    }

    /**
     * Plot/storyboard script prompt. The optimized script is rendered directly in
     * the chat and canvas storyboard views, so it must be standard Markdown.
     */
    static String scriptOptimizeSystemPrompt() {
        return """
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
                optimizedScript 与 expansionScript 必须是标准 Markdown 文本；
                字符串内换行使用 \\n 转义，不要输出代码块标记。
                """
                + StoryboardMarkdownSpec.SPEC;
    }

    @Override
    public ScriptRepairResult repairScript(StoryboardProductionRequest request,
                                           PlotAssessment assessment,
                                           List<RoleProfile> roles,
                                           List<AssetRef> assets) {
        return repairScript(request, assessment, roles, assets, StoryboardStreamSink.NOOP);
    }

    @Override
    public ScriptRepairResult repairScript(StoryboardProductionRequest request,
                                           PlotAssessment assessment,
                                           List<RoleProfile> roles,
                                           List<AssetRef> assets,
                                           StoryboardStreamSink sink) {
        String system = scriptRepairSystemPrompt();
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
                gatewayModelService.getDefaultTextModel(), repairTimeoutSeconds,
                "script_repair", sink,
                field("replacement", "修订内容"), field("reason", "修订原因"));
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

    /**
     * Repair prompt: replacements are spliced back into the Markdown script, so
     * they must keep the existing structure instead of inventing new syntax.
     */
    static String scriptRepairSystemPrompt() {
        return """
                你是影视剧本修稿师。只修复诊断中确认的具体问题，不扩写整篇剧情，不改变未涉及内容。
                每条修改必须包含一段在原文中只出现一次的原句，并用替换句修复对应问题。
                建筑、产品、空间、风景展示脚本不得通过补人物冲突来修复问题。
                替换句必须保持原剧本的 Markdown 结构：不新增或删除标题、列表、表格与分割线，
                表格内的替换保持列与行对齐，不要引入原文没有的 Markdown 语法块。
                只输出 JSON：
                {"changes":[{"issue":"","originalText":"","replacement":"","reason":""}],
                 "storyBible":{"logline":"","characters":[],"relationships":[],"worldRules":[],
                 "timeline":[],"continuity":[],"visualStyle":[]}}。
                如果某项风险无需修改文本，不要为它虚构修改。
                """;
    }

    @Override
    public List<StoryboardSegment> segmentStory(String script, StoryBible storyBible) {
        return segmentStory(script, storyBible, StoryboardStreamSink.NOOP);
    }

    @Override
    public List<StoryboardSegment> segmentStory(String script, StoryBible storyBible,
                                                StoryboardStreamSink sink) {
        String system = """
                你是长剧情分段规划器。按幕、场或完整叙事单元切分，不按单句机械切分。
                每个 segment 必须覆盖连续且不重叠的原文，并给出边界摘要。
                只输出 JSON：{"segments":[{"segmentId":"segment-1","ordinal":1,
                "title":"","sourceText":"","boundarySummary":""}]}。
                """;
        JsonNode root = callJson(system, "剧本：\n" + script + "\n\nStoryBible：\n" + objectJson(storyBible),
                gatewayModelService.getDefaultTextModel(), fastTimeoutSeconds,
                "story_segmentation", sink,
                field("title", "分段标题"), field("boundarySummary", "分段说明"));
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
        return planPseudoShots(request, segment, storyBible, roles, assets, StoryboardStreamSink.NOOP);
    }

    @Override
    public List<PseudoShot> planPseudoShots(StoryboardProductionRequest request,
                                            StoryboardSegment segment,
                                            StoryBible storyBible,
                                            List<RoleProfile> roles,
                                            List<AssetRef> assets,
                                            StoryboardStreamSink sink) {
        String system = """
                你是结构化伪分镜师。按叙事节拍写出可确认的镜头，不生成图片/视频参数。
                如果用户指定目标总镜头数，必须严格按该总数输出，不得按分段分别复制该数量。
                shotId 必须全局唯一：使用「<当前分段id>-shotNN」形式（例如 segment-2-shot03），
                禁止各分段自行从 S01 重新编号；segmentId 必须回填当前分段 id，shotOrdinal 从 1 递增。
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

                目标总镜头数：
                %s

                StoryBible：
                %s

                专业角色：
                %s

                可用资产：
                %s
                """.formatted(scriptContext(request, segment), objectJson(segment),
                request.requestedShotCount().isBlank()
                        ? "未指定，按叙事节拍决定"
                        : request.requestedShotCount() + "（所有分段合计，必须严格遵守）",
                objectJson(storyBible), rolesText(roles), assetsText(assets)),
                gatewayModelService.getDefaultTextModel(), fastTimeoutSeconds,
                "pseudo_planning", sink,
                field("narrativePurpose", "叙事目的"), field("action", "动作"),
                field("conflict", "冲突"), field("emotion", "情绪"));
        return parsePseudoShots(root.path("shots"));
    }

    /**
     * 阶段 A：极简大纲（一次调用，薄输出）。
     *
     * <p>与 {@code planPseudoShots} 的关键差异是**要什么写什么**：只要梗概 + N 行
     * 「一句画面简述 / 角色 / 地点 / 绑定资产」，不写景别、运镜、提示词、时长。
     * 输出量从「整部剧本 + 13 字段 × N」降到「N 行 × 40 字」，这是把阶段 A 从
     * 84~253s 压到 20~40s 的关键；补全交给审批后的阶段 B。
     */
    @Override
    public OutlinePlan planOutline(StoryboardProductionRequest request, List<RoleProfile> roles,
                                   List<AssetRef> assets, StoryboardStreamSink sink) {
        String system = """
                你是分镜大纲编剧。只产出一份「可审批的镜头大纲」：不写景别、不写运镜、不写提示词、不写时长。
                只输出 JSON（不要代码块、不要解释）：
                {"synopsis":"≤120字剧情梗概",
                 "bible":{"logline":"","characters":[],"relationships":[],"worldRules":[],
                          "timeline":[],"continuity":[],"visualStyle":[]},
                 "shots":[{"shotOrdinal":1,"scriptContent":"一句画面简述，必要时带台词",
                           "characters":[],"location":"","referenceAssetIds":[]}]}
                规则：
                - 镜头数：用户给定目标总数就严格遵守；未给定按叙事节拍给 4~12 个。
                - scriptContent 必须是一句话（≤40 字），写「谁在哪里做什么」，禁止写成段落。
                - 严格证据绑定资产：剧情明确出现、用户明确指定或专业角色指出必要时，
                  才把该资产 id 放进 referenceAssetIds；不确定必须为空。
                - 一次成文：不要自我检查、不要输出思考过程。
                """ + StoryboardPromptGuides.DIRECTORS_READ;
        JsonNode root = callJson(system, """
                用户需求 / 剧本：
                %s

                目标总镜头数：
                %s

                专业角色：
                %s

                可用资产：
                %s
                """.formatted(request.originalScript(),
                request.requestedShotCount() == null || request.requestedShotCount().isBlank()
                        ? "未指定，按叙事节拍决定" : request.requestedShotCount(),
                rolesText(roles), assetsText(assets)),
                gatewayModelService.getDefaultTextModel(), fastTimeoutSeconds,
                "outline_planning", sink,
                field("synopsis", "剧情梗概"), field("scriptContent", "画面"));

        List<PseudoShot> shots = new ArrayList<>();
        int index = 0;
        for (JsonNode item : root.path("shots")) {
            index++;
            int ordinal = item.path("shotOrdinal").asInt(index);
            shots.add(new PseudoShot(
                    "shot-" + ordinal, "outline", ordinal,
                    "", strings(item.path("characters")),
                    item.path("location").asText(""), "", "",
                    item.path("scriptContent").asText(""), "",
                    List.of(), List.of(),
                    strings(item.path("referenceAssetIds"))));
        }
        return new OutlinePlan(root.path("synopsis").asText(""),
                readStoryBible(root.path("bible")), shots);
    }

    @Override
    public List<PseudoShot> revisePseudoShots(List<PseudoShot> shots, String instruction, String shotId,
                                              StoryBible storyBible, List<AssetRef> assets) {
        return revisePseudoShots(shots, instruction, shotId, storyBible, assets,
                StoryboardStreamSink.NOOP);
    }

    @Override
    public List<PseudoShot> revisePseudoShots(List<PseudoShot> shots, String instruction,
                                              String shotId, StoryBible storyBible,
                                              List<AssetRef> assets,
                                              StoryboardStreamSink sink) {
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
                objectJson(storyBible), assetsText(assets)),
                gatewayModelService.getDefaultTextModel(), fastTimeoutSeconds,
                "pseudo_planning", sink,
                field("narrativePurpose", "叙事目的"), field("action", "动作"),
                field("conflict", "冲突"), field("emotion", "情绪"));
        return parsePseudoShots(root.path("shots"));
    }

    @Override
    public StoryboardShot generateShot(ShotGenerationRequest request) {
        return generateShot(request, StoryboardStreamSink.NOOP);
    }

    @Override
    public StoryboardShot generateShot(ShotGenerationRequest request, StoryboardStreamSink sink) {
        PseudoShot pseudo = request.pseudoShot();
        String system = """
                你是专业分镜师和视频导演。只把一个已确认的伪分镜扩写为一个正式镜头。
                不得新增、删除、重排镜头，不得改变镜头编号和资产绑定。
                只输出 JSON 对象：{"shotId":"","segmentId":"","shotOrdinal":1,
                "sceneDescription":"","shotType":"","cameraMovement":"","durationSeconds":3,
                "imagePrompt":"","videoPrompt":"","negativePrompt":"","referenceHints":[],
                "continuityNote":""}。
                sceneDescription、提示词和运镜必须使用中文。
                continuityNote 写「本镜从前一镜继承了什么、本镜不允许改什么」（≤60 字，
                例：承接雨夜冷蓝侧逆光；湿发与外衣水痕不得回退；人物始终在窗的左半侧）。
                """ + StoryboardLexicon.promptConstraint() + StoryboardPromptGuides.SHOT_TABLE + """

                一次成文：不要自我检查、不要输出思考过程或解释，只输出上面这一个 JSON 对象。
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
                assetsForPseudo(request.assets(), pseudo)),
                gatewayModelService.getDefaultTextModel(), fastTimeoutSeconds,
                "final_generation", sink,
                field("sceneDescription", "画面"), field("cameraMovement", "运镜"));
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
                strings(root.path("referenceHints")),
                root.path("continuityNote").asText("")
        );
    }

    /**
     * 批量扩写：一次调用把 N 个伪分镜扩写成 N 个正式镜头。
     *
     * <p>为什么能快：逐镜版是「每镜一次模型调用」，30 镜 = 30 次；批量版按 6 镜一批只有 5 次，
     * 且单次输出仍在可比范围内。代价是单镜注意力被摊薄，所以批次默认只给 6，
     * 并对返回值逐镜后处理（字典/时长/空值兜底）——质量由代码兜，不靠模型自查。
     *
     * <p>解析策略：<b>以 shotId 对齐</b>，不以数组下标对齐——模型偶尔会漏一个或多写一个，
     * 下标对齐会把整批错位。返回结果可能少于请求数，调用方按缺失记失败。
     */
    @Override
    public List<StoryboardShot> generateShots(List<ShotGenerationRequest> requests,
                                              StoryboardStreamSink sink) {
        if (requests == null || requests.isEmpty()) return List.of();
        if (requests.size() == 1) {
            return List.of(generateShot(requests.getFirst(), sink));
        }
        StringBuilder pseudoBlock = new StringBuilder();
        for (ShotGenerationRequest request : requests) {
            PseudoShot pseudo = request.pseudoShot();
            pseudoBlock.append("\n【").append(pseudo.shotId()).append("】\n")
                    .append(objectJson(pseudo)).append('\n')
                    .append("严格绑定的资产：\n")
                    .append(assetsForPseudo(request.assets(), pseudo)).append('\n');
        }
        String system = """
                你是专业分镜师和视频导演。把给出的每个伪分镜各扩写成一个正式镜头，
                输出数量必须与输入伪分镜数量一致，不得新增、删除、重排镜头，
                不得改变 shotId / segmentId / shotOrdinal，不得改变资产绑定。
                只输出 JSON 对象：{"shots":[
                  {"shotId":"","segmentId":"","shotOrdinal":1,"sceneDescription":"",
                   "shotType":"","cameraMovement":"","durationSeconds":3,
                   "imagePrompt":"","videoPrompt":"","negativePrompt":"","referenceHints":[],
                   "continuityNote":""}]}。
                sceneDescription、提示词和运镜必须使用中文。
                continuityNote 每镜必填（≤60 字）：写「本镜从前一镜继承了什么、本镜不允许改什么」，
                例：承接雨夜冷蓝侧逆光；湿发与外衣水痕不得回退；人物始终在窗的左半侧。整批必须自洽。
                """ + StoryboardLexicon.promptConstraint() + StoryboardPromptGuides.SHOT_TABLE + """

                一次成文：不要自我检查、不要输出思考过程或解释。
                """;
        JsonNode root = callJson(system, """
                待扩写的伪分镜（共 %d 个）：
                %s

                StoryBible（整片共用）：
                %s

                资产引用标记必须只在 referenceHints 中使用；不得使用未绑定资产。
                """.formatted(requests.size(), pseudoBlock,
                objectJson(requests.getFirst().storyBible())),
                gatewayModelService.getDefaultTextModel(), batchTimeoutSeconds,
                "final_generation", sink,
                field("sceneDescription", "画面"), field("cameraMovement", "运镜"));

        Map<String, PseudoShot> byShotId = new java.util.LinkedHashMap<>();
        for (ShotGenerationRequest request : requests) {
            byShotId.put(request.pseudoShot().shotId(), request.pseudoShot());
        }
        List<StoryboardShot> out = new ArrayList<>();
        for (JsonNode item : root.path("shots")) {
            String shotId = item.path("shotId").asText("");
            PseudoShot pseudo = byShotId.get(shotId);
            if (pseudo == null) {
                log.warn("storyboard batch shot ignored: unknown shotId={}", shotId);
                continue;
            }
            int duration = item.path("durationSeconds").asInt(3);
            if (duration <= 0) duration = 3;
            out.add(new StoryboardShot(
                    pseudo.shotId(),
                    pseudo.segmentId(),
                    pseudo.shotOrdinal(),
                    nonBlank(item.path("sceneDescription").asText(""), pseudo.action()),
                    nonBlank(item.path("shotType").asText(""), "中景"),
                    nonBlank(item.path("cameraMovement").asText(""), "固定"),
                    duration,
                    nonBlank(item.path("imagePrompt").asText(""), pseudo.action()),
                    nonBlank(item.path("videoPrompt").asText(""), pseudo.action()),
                    item.path("negativePrompt").asText(""),
                    strings(item.path("referenceHints")),
                    item.path("continuityNote").asText("")));
        }
        if (out.size() < requests.size()) {
            log.warn("storyboard batch returned {} of {} shots", out.size(), requests.size());
        }
        return List.copyOf(out);
    }

    private JsonNode callJson(String system, String user) {
        return callJson(system, user, gatewayModelService.getDefaultTextModel(), fastTimeoutSeconds);
    }

    private JsonNode callJson(String system, String user, String model, int timeoutSeconds) {
        String raw = invokeModel(system, user, model, timeoutSeconds);
        return parseJsonOrThrow(system, user, model, timeoutSeconds, raw);
    }

    /** Streaming version with stage-specific, user-readable JSON field extraction. */
    private JsonNode callJson(String system, String user, String model, int timeoutSeconds,
                              String stage, StoryboardStreamSink sink, StreamField... fields) {
        String raw = invokeModel(system, user, model, timeoutSeconds, stage, sink, fields);
        return parseJsonOrThrow(system, user, model, timeoutSeconds, raw);
    }

    private JsonNode parseJsonOrThrow(String system, String user, String model,
                                      int timeoutSeconds, String raw) {
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

    /**
     * 分镜调用的请求选项 builder（包内可见，便于单测断言"真的设了输出上限、且没钉死采样参数"）。
     *
     * <p>刻意只设 model / maxRetries / maxTokens / timeout：
     * temperature、top_p 不传，交上游默认，避免我们替用户把创作采样钉死。
     */
    OpenAiChatOptions.Builder chatOptionsBuilder(String model, int timeoutSeconds) {
        return OpenAiChatOptions.builder()
                .model(model)
                .maxRetries(0)
                // 显式声明输出上限：网关只在 /v1/models 的 defaults 里"展示" max_tokens，
                // 从不注入 OpenAI 兼容请求；不写这一行就等于把批量输出交给上游默认值，
                // 一旦上游默认偏小，6 镜一批的 JSON 会被砍在半截（表现为整批解析失败）。
                .maxTokens(maxOutputTokens)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
    }

    private String invokeModel(String system, String user, String model, int timeoutSeconds) {
        String raw;
        try {
            raw = client().prompt()
                    .options(chatOptionsBuilder(model, timeoutSeconds))
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

    /**
     * Uses the gateway normalized stream so both visible content and
     * reasoning_content can be delivered without Spring AI dropping metadata.
     */
    private String invokeModel(String system, String user, String model, int timeoutSeconds,
                               String stage, StoryboardStreamSink sink, StreamField... fields) {
        if (sink == null || sink == StoryboardStreamSink.NOOP || stage == null || stage.isBlank()) {
            return invokeModel(system, user, model, timeoutSeconds);
        }
        int seconds = Math.max(1, timeoutSeconds);
        StringBuilder raw = new StringBuilder();
        List<JsonFieldStreamExtractor> extractors = new ArrayList<>();
        for (StreamField field : fields == null ? new StreamField[0] : fields) {
            extractors.add(new JsonFieldStreamExtractor(field.jsonName(), field.label()));
        }
        try {
            NormalizedChatStreamClient.StreamResult result = normalizedStreamClient.stream(
                    model, system, user, seconds,
                    chunk -> {
                        raw.append(chunk);
                        if (extractors.isEmpty()) return;
                        StringBuilder visible = new StringBuilder();
                        for (JsonFieldStreamExtractor extractor : extractors) {
                            visible.append(extractor.accept(chunk));
                        }
                        if (!visible.isEmpty()) sink.onDelta(stage, visible.toString());
                    },
                    thinking -> sink.onThinking(stage, thinking));
            if (!result.completed()) {
                throw new BusinessException(50201,
                        "storyboard production stream ended before a terminal frame");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(49999, "storyboard production model call interrupted", e);
        } catch (IOException e) {
            if (raw.length() > 0) {
                throw new BusinessException(50201,
                        "storyboard production stream failed after partial output: " + e.getMessage(), e);
            }
            log.warn("normalized storyboard stream failed before output, falling back to blocking: {}",
                    e.getMessage());
            return invokeModel(system, user, model, timeoutSeconds);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            if (raw.length() > 0) {
                throw new BusinessException(50201,
                        "storyboard production stream failed after partial output: " + e.getMessage(), e);
            }
            log.warn("storyboard streaming call failed, falling back to blocking: {}", e.getMessage());
            return invokeModel(system, user, model, timeoutSeconds);
        }
        if (raw.length() == 0) return invokeModel(system, user, model, timeoutSeconds);
        return raw.toString();
    }

    private static StreamField field(String jsonName, String label) {
        return new StreamField(jsonName, label);
    }

    private record StreamField(String jsonName, String label) {
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
