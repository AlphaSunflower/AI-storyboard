package com.moon.moonagent.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.service.AssetService;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.ScriptGenerationService;
import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.agent.storyboard.StoryboardJsonParser;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.ParameterizedTypeReference;
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

    /**
     * 结构化解析用的分镜定义（字段名与 AI 返回的 JSON 键一致；sceneNumber 仅占位，实际递增逻辑见 toSceneMaps/parseScenes）。
     */
    public record SceneSpec(String sceneNumber, String scriptContent, String imagePrompt,
                            String videoPrompt, String negativePrompt, String cameraMovement,
                            String shotType, String soundDesign) {}

    private final GatewayModelService gatewayModelService;
    private final AiConfigProperties config;
    private final StoryboardClient storyboardClient;
    private final AssetService assetService;
    private final ObjectMapper objectMapper = new ObjectMapper();
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

        LlmCall llm = callLLM(model, systemPrompt, userPrompt);
        String content = llm.content();
        // 容错解析优先：裸数组/包装对象/代码块/字符串数字都兼容；存在合法分镜就不清空
        List<Map<String, Object>> parsed = StoryboardJsonParser.parseScenes(content);
        return new GeneratedScenes(
                decorateH3(normalizeParsedScenes(parsed, projectId), understanding, assetSheet),
                llm.reasoning());
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
     * 懒加载获取 ChatClient：默认模型固定为网关默认文本模型（getDefaultTextModel），超时 120s
     * （与原 HttpClient timeout 一致）；双重检查锁保证线程安全。
     */
    private ChatClient chatClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    chatClient = chatClientBuilder
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .model(gatewayModelService.getDefaultTextModel())
                                    .timeout(Duration.ofSeconds(config.getScriptTimeout())))
                            .build();
                }
            }
        }
        return chatClient;
    }

    private String buildSystemPrompt() {
        return promptConfig.get("script/storyboard-system");
    }

    private LlmCall callLLM(String model, String systemPrompt, String userPrompt) {
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                ChatClient.ChatClientRequestSpec spec = chatClient()
                        .prompt()
                        .system(systemPrompt)
                        .user(userPrompt);
                if (model != null) {
                    // 单次调用覆盖模型（默认模型已在 defaultOptions 固定）
                    spec = spec.options(OpenAiChatOptions.builder().model(model));
                }
                ChatResponse response = spec.call().chatResponse();
                AssistantMessage output = response != null && response.getResult() != null
                        ? response.getResult().getOutput() : null;
                String raw = output == null ? "" : (output.getText() == null ? "" : output.getText());
                AgentReasoningSnapshot reasoning = buildReasoning(output, model);
                return new LlmCall(raw, reasoning);
            } catch (Exception e) {
                if (attempts < 2 && isTransient(e)) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                throw new BusinessException(50201, "AI 生成分镜脚本失败: " + e.getMessage(), e);
            }
        }
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

    /** 瞬态失败重试：429/5xx、连接/超时。 */
    private static boolean isTransient(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof org.springframework.web.client.HttpStatusCodeException sce) {
                int s = sce.getStatusCode().value();
                if (s == 429 || s >= 500) return true;
            }
            if (c instanceof java.net.ConnectException
                    || c instanceof java.net.http.HttpTimeoutException
                    || c instanceof java.net.SocketTimeoutException) {
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
        return chatClient().prompt()
                .system(promptConfig.get("script/visual-understanding"))
                .messages(msg)
                .options(OpenAiChatOptions.builder().model(effModel))
                .call()
                .content();
    }

    /** 组装分镜生成 user prompt：有理解描述时前置参考图视觉要素，否则仅剧本。 */
    private String buildUserPrompt(String scriptText, String understanding, String assetSheet) {
        String base = promptConfig.get("script/storyboard-user");
        if (understanding != null && !understanding.isBlank()) {
            base += "参考图视觉要素（请在分镜中体现这些风格与要素）：\n" + understanding + "\n\n";
        }
        if (assetSheet != null && !assetSheet.isBlank()) {
            base += "【资产文字约束，必须逐条体现且不得违背】\n" + assetSheet.trim() + "\n\n";
        }
        return base + "剧本：\n" + scriptText;
    }

    /** 从 data URI 前缀提取图片 MIME（data:image/png;base64,... → image/png；非 data URI 兜底 png） */
    private String imageMimeType(String dataUri) {
        if (dataUri != null && dataUri.startsWith("data:")) {
            int end = dataUri.indexOf(';');
            if (end > 5) return dataUri.substring(5, end);
        }
        return "image/png";
    }

    /** 结构化解析成功路径：SceneSpec → Map（sceneNumber 递增逻辑与 parseScenes 一致）。 */
    private List<Map<String, Object>> toSceneMaps(List<SceneSpec> specs, String projectId) {
        List<Map<String, Object>> scenes = new ArrayList<>();
        int sceneNum = storyboardClient.maxSceneNumber(projectId);
        for (SceneSpec s : specs) {
            sceneNum++;
            Map<String, Object> scene = new HashMap<>();
            scene.put("projectId", projectId);
            scene.put("sceneNumber", sceneNum);
            scene.put("scriptContent", s.scriptContent() == null ? "" : s.scriptContent());
            scene.put("imagePrompt", s.imagePrompt() == null ? "" : s.imagePrompt());
            scene.put("videoPrompt", s.videoPrompt() == null ? "" : s.videoPrompt());
            scene.put("negativePrompt", s.negativePrompt() == null ? "" : s.negativePrompt());
            scene.put("cameraMovement", s.cameraMovement() == null ? "" : s.cameraMovement());
            scene.put("shotType", s.shotType() == null ? "" : s.shotType());
            scene.put("soundDesign", s.soundDesign() == null ? "" : s.soundDesign());
            scenes.add(scene);
        }
        return scenes;
    }

    private List<Map<String, Object>> parseScenes(String content, String projectId) {
        try {
            // 提取 JSON 数组（AI 可能在前后包裹 markdown 代码块）
            String json = content;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7);
                json = json.substring(0, json.lastIndexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3);
                json = json.substring(0, json.lastIndexOf("```"));
            }
            json = json.trim();
            if (!json.startsWith("[")) {
                json = json.substring(json.indexOf('['), json.lastIndexOf(']') + 1);
            }

            List<Map<String, Object>> scenes = new ArrayList<>();
            JsonNode arr = objectMapper.readTree(json);
            int sceneNum = storyboardClient.maxSceneNumber(projectId);
            for (JsonNode node : arr) {
                sceneNum++;
                Map<String, Object> scene = new HashMap<>();
                scene.put("projectId", projectId);
                scene.put("sceneNumber", sceneNum);
                scene.put("scriptContent", node.path("scriptContent").asText(""));
                scene.put("imagePrompt", node.path("imagePrompt").asText(""));
                scene.put("videoPrompt", node.path("videoPrompt").asText(""));
                scene.put("negativePrompt", node.path("negativePrompt").asText(""));
                scene.put("cameraMovement", node.path("cameraMovement").asText(""));
                scene.put("shotType", node.path("shotType").asText(""));
                scene.put("soundDesign", node.path("soundDesign").asText(""));
                scenes.add(scene);
            }
            return scenes;
        } catch (Exception e) {
            throw new BusinessException(50201, "解析 AI 返回的分镜数据失败: " + e.getMessage(), e);
        }
    }
}
