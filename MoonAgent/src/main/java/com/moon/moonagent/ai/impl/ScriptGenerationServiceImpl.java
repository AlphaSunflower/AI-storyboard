package com.moon.moonagent.ai.impl;

import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.service.AssetService;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.ScriptGenerationService;
import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.agent.storyboard.StoryboardBatchGenerator;
import com.moon.moonagent.ai.agent.storyboard.StoryboardJsonParser;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import com.storyboard.common.BusinessException;

/**
 * 分镜脚本生成实现：通过 Spring AI ChatClient 调用 LLM 网关（spring.ai.openai.base-url 已指向网关 /v1，
 * 即原手写 HttpClient 直连的 /v1/chat/completions），生成并解析分镜脚本。
 */
@Service
@RequiredArgsConstructor
public class ScriptGenerationServiceImpl implements ScriptGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ScriptGenerationServiceImpl.class);

    private final GatewayModelService gatewayModelService;
    private final AiConfigProperties config;
    private final AgentAiConfigProperties agentConfig;
    private final StoryboardClient storyboardClient;
    private final AssetService assetService;
    private final ChatClient.Builder chatClientBuilder;
    private final com.moon.moonagent.config.PromptConfig promptConfig;
    /** 懒加载：首次调用时用默认视觉模型构建（@RequiredArgsConstructor 无法承载原构造器内的构建逻辑） */
    private volatile ChatClient chatClient;

    @Override
    public List<Map<String, Object>> generateScenes(String projectId, String scriptText,
                                                      String creationType, String customTypeDesc,
                                                      String aspectRatio, String model,
                                                      String understandingModel, List<String> referenceImages) {
        return generateScenes(projectId, scriptText, creationType, customTypeDesc, aspectRatio, model,
                understandingModel, referenceImages, null);
    }

    @Override
    public List<Map<String, Object>> generateScenes(String projectId, String scriptText,
                                                      String creationType, String customTypeDesc,
                                                      String aspectRatio, String model,
                                                      String understandingModel, List<String> referenceImages,
                                                      List<String> assetIds) {
        return generateScenesWithReasoning(projectId, scriptText, creationType, customTypeDesc,
                aspectRatio, model, understandingModel, referenceImages, assetIds).scenes();
    }

    @Override
    public GeneratedScenes generateScenesWithReasoning(String projectId, String scriptText,
                                                       String creationType, String customTypeDesc,
                                                       String aspectRatio, String model,
                                                       String understandingModel, List<String> referenceImages,
                                                       List<String> assetIds) {
        String systemPrompt = buildSystemPrompt();

        // 资产库设定集注入：项目资产 + 用户全局资产 → 文字卡塞进 system prompt 约束分镜
        //（资产为可选增强，注入失败仅告警跳过，不影响分镜生成；
        //  assetIds 非 null 时只注入指定子集——空列表=不注入，null=全量注入旧行为）
        // 资产库设定集：一次读取，注入 system prompt 约束分镜 + 供 H3 增强兜底
        String assetSheet = "";
        try {
            List<AssetVO> assets = assetService.projectAssets(projectId);
            if (assets != null && !assets.isEmpty()) {
                if (assetIds != null) {
                    assets = assets.stream().filter(a -> assetIds.contains(a.id())).toList();
                }
                if (!assets.isEmpty()) {
                    assetSheet = assetService.buildSheetText(assets);
                    systemPrompt += assetSheet;
                }
            }
        } catch (Exception e) {
            log.warn("资产库设定集注入失败，跳过: {}", e.getMessage());
        }

        // 有参考图 → 先调理解模型看图生成描述，再连同用户提示词交给分镜模型（无图直接分镜模型）
        String understanding = null;
        if (referenceImages != null && !referenceImages.isEmpty()) {
            understanding = callUnderstandingModel(understandingModel, referenceImages);
        }
        String userPrompt = buildUserPrompt(scriptText, understanding, assetSheet);

        long startedAt = System.currentTimeMillis();
        LlmCall planCall = null;
        StoryboardBatchGenerator.Plan plan;
        Integer explicitSceneCount = StoryboardBatchGenerator.detectExplicitSceneCount(scriptText);
        if (explicitSceneCount != null) {
            // 剧本明确声明镜头数时跳过模型估算，避免模型自行增删镜头。
            plan = StoryboardBatchGenerator.fallbackPlan(
                    scriptText,
                    StoryboardBatchGenerator.DEFAULT_MIN_BATCH_SIZE,
                    StoryboardBatchGenerator.DEFAULT_MAX_BATCH_SIZE,
                    StoryboardBatchGenerator.DEFAULT_MAX_SCENES);
            log.info("剧本显式声明分镜数，跳过模型规划: totalScenes={}, batchSize={}",
                    plan.totalScenes(), plan.batchSize());
        } else {
            try {
                planCall = callLLM(resolveSkeletonModel(), buildPlanningSystemPrompt(),
                        buildPlanningPrompt(scriptText, understanding, assetSheet),
                        "skeleton", UUID.randomUUID().toString());
                plan = StoryboardBatchGenerator.parsePlan(
                        planCall.content(), scriptText,
                        StoryboardBatchGenerator.DEFAULT_MIN_BATCH_SIZE,
                        StoryboardBatchGenerator.DEFAULT_MAX_BATCH_SIZE,
                        StoryboardBatchGenerator.DEFAULT_MAX_SCENES);
            } catch (Exception e) {
                plan = StoryboardBatchGenerator.fallbackPlan(
                        scriptText,
                        StoryboardBatchGenerator.DEFAULT_MIN_BATCH_SIZE,
                        StoryboardBatchGenerator.DEFAULT_MAX_BATCH_SIZE,
                        StoryboardBatchGenerator.DEFAULT_MAX_SCENES);
                log.warn("分镜规划调用失败，使用确定性兜底: totalScenes={}, batchSize={}, error={}",
                        plan.totalScenes(), plan.batchSize(), e.getMessage());
            }
        }

        final LlmCall finalPlanCall = planCall;
        final StoryboardBatchGenerator.Plan finalPlan = plan;
        final String finalSystemPrompt = systemPrompt;
        final String finalUnderstanding = understanding;
        final String finalAssetSheet = assetSheet;
        StoryboardBatchGenerator.Result result = StoryboardBatchGenerator.generateParallel(
                finalPlan, StoryboardJsonParser::parseScenes, range -> {
                    String callId = UUID.randomUUID().toString();
                    LlmCall batchCall = callLLM(model, finalSystemPrompt,
                            buildBatchUserPrompt(scriptText, finalUnderstanding, finalAssetSheet,
                                    range, finalPlan.totalScenes()),
                            "agent-scenes-" + range.from() + "-" + range.to(), callId);
                    String details = batchCall.reasoning() == null ? null : batchCall.reasoning().details();
                    return new StoryboardBatchGenerator.CallOutput(batchCall.content(), details);
                }, agentConfig.getMaxParallelAgents());

        String mergedReasoning = mergeReasoning(finalPlanCall, result.reasoning());
        return new GeneratedScenes(
                decorateH3(normalizeParsedScenes(result.scenes(), projectId), understanding, assetSheet),
                reasoningSnapshot(mergedReasoning, model, System.currentTimeMillis() - startedAt));
    }

    /** 解析器输出 → 标准分镜 Map（sceneNumber 按项目 max+1 递增；字段兜底空串）。 */
    private List<Map<String, Object>> normalizeParsedScenes(List<Map<String, Object>> parsed, String projectId) {
        List<Map<String, Object>> out = new ArrayList<>();
        int sceneNum = storyboardClient.maxSceneNumber(projectId);
        for (Map<String, Object> s : parsed) {
            sceneNum++;
            Map<String, Object> scene = new HashMap<>();
            scene.put("projectId", projectId);
            scene.put("sceneNumber", sceneNum);
            scene.put("scriptContent", str(s.get("scriptContent")));
            scene.put("imagePrompt", str(s.get("imagePrompt")));
            scene.put("videoPrompt", str(s.get("videoPrompt")));
            scene.put("negativePrompt", str(s.get("negativePrompt")));
            scene.put("cameraMovement", str(s.get("cameraMovement")));
            scene.put("shotType", str(s.get("shotType")));
            scene.put("soundDesign", str(s.get("soundDesign")));
            scene.put("referenceBindings", s.getOrDefault("referenceBindings", List.of()));
            out.add(scene);
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * H3 增强兜底：模型未写【参考素材说明】时补齐（@图片N + 用途），并确保末尾「非叙事性音乐：N/A」。
     * 参考图数量 ≥1 才有意义；纯文本分镜不篡改模型输出。
     */
    private List<Map<String, Object>> decorateH3(List<Map<String, Object>> scenes,
                                                  String understanding, String assetSheet) {
        if (scenes == null || scenes.isEmpty()) return scenes;
        boolean hasRef = (understanding != null && !understanding.isBlank())
                || (assetSheet != null && !assetSheet.isBlank());
        if (!hasRef) return scenes;
        String refBlock = buildH3ReferenceBlock(understanding, assetSheet);
        for (Map<String, Object> s : scenes) {
            String vp = s.get("videoPrompt") == null ? "" : String.valueOf(s.get("videoPrompt"));
            String decorated;
            if (vp.contains("参考素材说明")) {
                // 已含说明只补尾部音乐；不要重复插整段
                decorated = vp.contains("非叙事性音乐") ? vp : vp + "\n非叙事性音乐：N/A";
            } else {
                decorated = refBlock + "\n" + vp;
                if (!decorated.contains("非叙事性音乐")) {
                    decorated = decorated + "\n非叙事性音乐：N/A";
                }
            }
            s.put("videoPrompt", decorated);
        }
        return scenes;
    }

    /** 由理解描述（图一/图二…）与资产设定集拼 H3 参考素材说明段（@图片N）。 */
    private String buildH3ReferenceBlock(String understanding, String assetSheet) {
        StringBuilder sb = new StringBuilder("【参考素材说明】");
        int idx = 1;
        if (understanding != null && !understanding.isBlank()) {
            String u = understanding.replace("图一", "@图片1").replace("图二", "@图片2")
                    .replace("图三", "@图片3").replace("图四", "@图片4")
                    .replace("图五", "@图片5").replace("图六", "@图片6")
                    .replace("图七", "@图片7").replace("图八", "@图片8")
                    .replace("图九", "@图片9");
            sb.append(u.trim());
        }
        if (assetSheet != null && !assetSheet.isBlank()) {
            sb.append("\n").append(assetSheet.trim());
        }
        return sb.toString();
    }

    @Override
    public Map<String, Object> generateAndSaveScenes(String projectId, String scriptText,
                                                     String creationType, String customTypeDesc,
                                                     String aspectRatio, String model,
                                                     String understandingModel, List<String> referenceImages) {
        // 先生成分镜（复用 generateScenes 的 LLM 调用 + 双路解析）
        List<Map<String, Object>> scenes = generateScenes(
                projectId, scriptText, creationType, customTypeDesc, aspectRatio, model,
                understandingModel, referenceImages);
        // 批量写库（原 AIController.generateScript 的循环落库逻辑下沉至此）
        for (Map<String, Object> s : scenes) {
            Scene scene = new Scene();
            scene.setProjectId(projectId);
            scene.setSceneNumber((Integer) s.get("sceneNumber"));
            scene.setScriptContent((String) s.get("scriptContent"));
            scene.setImagePrompt((String) s.get("imagePrompt"));
            scene.setVideoPrompt((String) s.get("videoPrompt"));
            scene.setNegativePrompt((String) s.get("negativePrompt"));
            scene.setCameraMovement((String) s.get("cameraMovement"));
            scene.setShotType((String) s.get("shotType"));
            scene.setSoundDesign((String) s.get("soundDesign"));
            storyboardClient.insertScene(scene);
        }
        return Map.of("projectId", projectId, "sceneCount", scenes.size());
    }

    /**
     * 懒加载获取 ChatClient：默认模型固定为网关默认文本模型（getDefaultTextModel），
     * 超时由 {@code ai.script-timeout} 控制（默认 300s）
     * （与原 HttpClient timeout 一致）；双重检查锁保证线程安全。
     */
    private ChatClient chatClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    OpenAiChatOptions.Builder defaults = OpenAiChatOptions.builder()
                            .model(gatewayModelService.getDefaultTextModel())
                            .timeout(Duration.ofSeconds(config.getScriptTimeout()))
                            .maxRetries(0);
                    if (config.getScriptMaxTokens() > 0) {
                        defaults.maxTokens(config.getScriptMaxTokens());
                    }
                    chatClient = chatClientBuilder
                            .defaultOptions(defaults)
                            .build();
                }
            }
        }
        return chatClient;
    }

    private String buildSystemPrompt() {
        return promptConfig.get("script/storyboard-system");
    }

    private LlmCall callLLM(String model, String systemPrompt, String userPrompt,
                            String stage, String callId) {
        int attempts = 0;
        String effectiveModel = model == null || model.isBlank()
                ? gatewayModelService.getDefaultTextModel() : model;
        long startedAt = System.currentTimeMillis();
        while (true) {
            attempts++;
            try {
                ChatClient.ChatClientRequestSpec spec = chatClient()
                        .prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .options(buildRequestOptions(effectiveModel, callId));
                ChatResponse response = spec.call().chatResponse();
                AssistantMessage output = response != null && response.getResult() != null
                        ? response.getResult().getOutput() : null;
                String raw = output == null ? "" : (output.getText() == null ? "" : output.getText());
                AgentReasoningSnapshot reasoning = buildReasoning(output, effectiveModel);
                log.info("LLM调用完成: stage={}, model={}, inputChars={}, outputChars={}, duration={}ms, retry={}, callId={}",
                        stage, effectiveModel, length(systemPrompt) + length(userPrompt), raw.length(),
                        System.currentTimeMillis() - startedAt, attempts - 1, callId);
                return new LlmCall(raw, reasoning);
            } catch (Exception e) {
                if (attempts < 2 && isTransient(e)) {
                    log.warn("LLM调用瞬态失败，准备重试: stage={}, model={}, retry={}, callId={}, error={}",
                            stage, effectiveModel, attempts, callId, e.getMessage());
                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                log.error("LLM调用失败: stage={}, model={}, inputChars={}, duration={}ms, retry={}, callId={}, error={}",
                        stage, effectiveModel, length(systemPrompt) + length(userPrompt),
                        System.currentTimeMillis() - startedAt, attempts - 1, callId, e.getMessage());
                throw new BusinessException(50201, "AI 生成分镜脚本失败: " + e.getMessage(), e);
            }
        }
    }

    private OpenAiChatOptions.Builder buildRequestOptions(String model, String callId) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(model)
                .timeout(Duration.ofSeconds(config.getScriptTimeout()))
                .maxRetries(0)
                .customHeaders(Map.of("X-Call-Id", callId));
        if (config.getScriptMaxTokens() > 0) {
            builder.maxTokens(config.getScriptMaxTokens());
        }
        return builder;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    /** 从 AssistantMessage metadata 提取推理；模型未返回时给出可展开的摘要。 */
    private AgentReasoningSnapshot buildReasoning(AssistantMessage output, String model) {
        String reason = extractReasoning(output == null ? null : output.getMetadata());
        String fallback = "已完成分镜方案推理。";
        String text = reason == null || reason.isBlank() ? fallback : reason;
        return new AgentReasoningSnapshot(
                text.length() > 200 ? text.substring(0, 200) + "…" : text,
                text,
                null,
                reason == null || reason.isBlank() ? "trace-summary"
                        : (model == null || model.isBlank() ? gatewayModelService.getDefaultTextModel() : model));
    }

    private AgentReasoningSnapshot reasoningSnapshot(String details, String model, long durationMs) {
        String text = details == null || details.isBlank() ? "已完成分镜方案推理。" : details.trim();
        return new AgentReasoningSnapshot(
                text.length() > 200 ? text.substring(0, 200) + "…" : text,
                text,
                durationMs,
                model == null || model.isBlank() ? gatewayModelService.getDefaultTextModel() : model);
    }

    private String mergeReasoning(LlmCall planCall, String batchReasoning) {
        StringBuilder merged = new StringBuilder();
        if (planCall != null && planCall.reasoning() != null
                && planCall.reasoning().details() != null
                && !planCall.reasoning().details().isBlank()) {
            merged.append("【规划】").append(planCall.reasoning().details().trim());
        }
        if (batchReasoning != null && !batchReasoning.isBlank()) {
            if (!merged.isEmpty()) merged.append("\n");
            merged.append("【分镜】").append(batchReasoning.trim());
        }
        return merged.toString();
    }

    private String extractReasoning(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        for (String key : new String[]{"reasoning_content", "reasoningContent", "thoughts", "thinking"}) {
            Object value = metadata.get(key);
            if (value == null) continue;
            String text = value instanceof String s ? s : String.valueOf(value);
            if (text != null && !text.isBlank()) return text;
        }
        return null;
    }

    /** LLM 调用结果：正文 + 推理摘要。 */
    private record LlmCall(String content, AgentReasoningSnapshot reasoning) {}

    /** 瞬态失败重试：429/5xx、连接失败；超时不重试。 */
    private static boolean isTransient(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof org.springframework.web.client.HttpStatusCodeException sce) {
                int s = sce.getStatusCode().value();
                if (s == 429 || s >= 500) return true;
            }
            if (c instanceof java.net.ConnectException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 理解模型看图：多张参考图 → 「图一/图二…」文字描述（多模态 Media 输入）。
     * 模型优先级：显式传入 understandingModel > 默认视觉模型（与脚本模型共用）。
     */
    private String callUnderstandingModel(String model, List<String> referenceImages) {
        String effModel = (model != null && !model.isBlank()) ? model : gatewayModelService.getDefaultVisionModel();
        String callId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        List<Media> medias = new ArrayList<>();
        for (String img : referenceImages) {
            medias.add(Media.builder()
                    .mimeType(MimeType.valueOf(imageMimeType(img)))
                    .data(img)
                    .build());
        }
        UserMessage msg = UserMessage.builder()
                .text("请逐一描述以下参考图的内容与风格（主体、构图、色调、光线、氛围、画风），"
                        + "用「图一：…」「图二：…」的格式输出，供后续分镜生成参考。")
                .media(medias.toArray(new Media[0]))
                .build();
        String content = chatClient().prompt()
                .system(promptConfig.get("script/visual-understanding"))
                .messages(msg)
                .options(buildRequestOptions(effModel, callId))
                .call()
                .content();
        log.info("LLM调用完成: stage=understanding, model={}, inputChars={}, outputChars={}, duration={}ms, retry=0, callId={}",
                effModel, msg.getText() == null ? 0 : msg.getText().length(),
                content == null ? 0 : content.length(), System.currentTimeMillis() - startedAt, callId);
        return content;
    }

    private String buildPlanningSystemPrompt() {
        return "你是分镜规划器。只输出一个 JSON 对象，不要代码块和解释，格式："
                + "{\"totalScenes\":正整数,\"batchSize\":正整数}。"
                + "totalScenes 必须根据剧本信息量估算，batchSize 必须在 "
                + StoryboardBatchGenerator.DEFAULT_MIN_BATCH_SIZE + " 到 "
                + StoryboardBatchGenerator.DEFAULT_MAX_BATCH_SIZE + " 之间。"
                + "若剧本明确写出「共N个分镜/镜头」或列出N个分镜，totalScenes 必须严格等于 N，不得自行增删。";
    }

    String resolveSkeletonModel() {
        String model = agentConfig.getSkeletonModel();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "ai.agent.skeleton-model 未配置：骨架阶段禁止回退到网关默认强模型");
        }
        return model.trim();
    }

    private String buildPlanningPrompt(String scriptText, String understanding, String assetSheet) {
        StringBuilder prompt = new StringBuilder("请估算剧本应拆分为多少个分镜，并给出每批生成数量。");
        if (understanding != null && !understanding.isBlank()) {
            prompt.append("\n参考图视觉要素：\n").append(understanding.trim());
        }
        if (assetSheet != null && !assetSheet.isBlank()) {
            prompt.append("\n资产约束：\n").append(assetSheet.trim());
        }
        return prompt.append("\n剧本：\n").append(scriptText).toString();
    }

    /** 组装分镜生成 user prompt：有理解描述时前置参考图视觉要素，否则仅剧本。 */
    private String buildUserPrompt(String scriptText, String understanding, String assetSheet) {
        return buildUserPrompt(scriptText, understanding, assetSheet, "");
    }

    private String buildUserPrompt(String scriptText, String understanding, String assetSheet,
                                   String instruction) {
        String base = promptConfig.get("script/storyboard-user");
        if (understanding != null && !understanding.isBlank()) {
            base += "参考图视觉要素（请在分镜中体现这些风格与要素）：\n" + understanding + "\n\n";
        }
        if (assetSheet != null && !assetSheet.isBlank()) {
            base += "【资产文字约束，必须逐条体现且不得违背】\n" + assetSheet.trim() + "\n\n";
        }
        if (instruction != null && !instruction.isBlank()) {
            base += instruction.trim() + "\n\n";
        }
        return base + "剧本：\n" + scriptText;
    }

    private String buildBatchUserPrompt(String scriptText, String understanding, String assetSheet,
                                        StoryboardBatchGenerator.Range range, int totalScenes) {
        String instruction = "【本次批次】全片计划共 " + totalScenes + " 个分镜，本次只生成第 "
                + range.from() + " 到 " + range.to() + " 个，共 " + range.count()
                + " 个。sceneNumber 必须从 " + range.from() + " 连续编号到 " + range.to()
                + "；不得重复、跳号或生成范围外镜头。";
        return buildUserPrompt(scriptText, understanding, assetSheet, instruction);
    }

    /** 从 data URI 前缀提取图片 MIME（data:image/png;base64,... → image/png；非 data URI 兜底 png） */
    private String imageMimeType(String dataUri) {
        if (dataUri != null && dataUri.startsWith("data:")) {
            int end = dataUri.indexOf(';');
            if (end > 5) return dataUri.substring(5, end);
        }
        return "image/png";
    }

}
