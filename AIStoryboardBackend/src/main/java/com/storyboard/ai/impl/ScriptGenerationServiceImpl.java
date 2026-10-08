package com.storyboard.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.Scene;
import com.storyboard.entity.ScenePrompt;
import com.storyboard.entity.SceneReferenceImage;
import com.storyboard.dto.response.AssetVO;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import com.storyboard.mapper.SceneReferenceImageMapper;
import com.storyboard.service.AssetService;
import com.storyboard.service.FileStorageService;
import com.storyboard.ai.GatewayModelService;
import com.storyboard.ai.ScriptGenerationService;
import com.storyboard.ai.AiConfigProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
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
    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final ScenePromptMapper scenePromptMapper;
    private final SceneReferenceImageMapper sceneReferenceImageMapper;
    private final AssetService assetService;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatClient.Builder chatClientBuilder;
    private final com.storyboard.config.PromptConfig promptConfig;
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
        String systemPrompt = buildSystemPrompt();

        // 资产库设定集注入：仅当调用方显式传入 assetIds 时注入对应资产的文字卡；
        // 默认（assetIds 为 null 或空列表）不注入任何资产 —— 生成剧本默认与资产库无关，
        // 需通过「选择资产生成」显式指定（旧版 null=全量注入已废弃，避免用户未选择却被资产约束）。
        try {
            List<AssetVO> assets = List.of();
            if (assetIds != null && !assetIds.isEmpty()) {
                assets = assetService.projectAssets(projectId).stream()
                        .filter(a -> assetIds.contains(a.id()))
                        .toList();
            }
            if (!assets.isEmpty()) {
                systemPrompt += assetService.buildSheetText(assets);
            }
        } catch (Exception e) {
            log.warn("资产库设定集注入失败，跳过: {}", e.getMessage());
        }

        // 有参考图 → 先调理解模型看图生成描述，再连同用户提示词交给分镜模型（无图直接分镜模型）
        String understanding = null;
        if (referenceImages != null && !referenceImages.isEmpty()) {
            understanding = callUnderstandingModel(understandingModel, referenceImages);
        }
        String userPrompt = buildUserPrompt(scriptText, understanding);

        String content = callLLM(model, systemPrompt, userPrompt);
        // 结构化解析优先；失败/空结果走下方原有 JSON 兜底逻辑
        try {
            BeanOutputConverter<List<SceneSpec>> conv = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {});
            List<SceneSpec> specs = conv.convert(content);
            if (!specs.isEmpty()) {
                return toSceneMaps(specs, projectId);
            }
        } catch (RuntimeException e) {
            // 结构化解析失败（AI 返回非 JSON / 字段不符等），走兜底
        }
        return parseScenes(content, projectId);
    }

    @Override
    public Map<String, Object> generateAndSaveScenes(String projectId, String scriptText,
                                                     String creationType, String customTypeDesc,
                                                     String aspectRatio, String model,
                                                     String understandingModel, List<String> referenceImages,
                                                     List<String> assetIds, String mode) {
        // 先生成分镜（复用 generateScenes 的 LLM 调用 + 双路解析）
        List<Map<String, Object>> scenes = generateScenes(
                projectId, scriptText, creationType, customTypeDesc, aspectRatio, model,
                understandingModel, referenceImages, assetIds);
        // 覆盖写：先清空项目现有分镜 + 其级联资源（scene_assets/scene_refs 由 DB 级联删），再批量写入。
        // 仅在 LLM 生成成功后才删除，避免生成失败时把旧分镜清掉。
        if ("overwrite".equalsIgnoreCase(mode)) {
            sceneMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Scene>()
                    .eq(Scene::getProjectId, projectId));
        }
        // 批量写库（原 AIController.generateScript 的循环落库逻辑下沉至此）
        List<Scene> inserted = new ArrayList<>();
        for (Map<String, Object> s : scenes) {
            Scene scene = new Scene();
            scene.setProjectId(projectId);
            scene.setSceneNumber((Integer) s.get("sceneNumber"));
            sceneMapper.insert(scene);
            ScenePrompt prompt = new ScenePrompt();
            prompt.setSceneId(scene.getId());
            prompt.setScriptContent((String) s.get("scriptContent"));
            prompt.setImagePrompt((String) s.get("imagePrompt"));
            prompt.setVideoPrompt((String) s.get("videoPrompt"));
            prompt.setNegativePrompt((String) s.get("negativePrompt"));
            prompt.setCameraMovement((String) s.get("cameraMovement"));
            prompt.setShotType((String) s.get("shotType"));
            prompt.setSoundDesign((String) s.get("soundDesign"));
            scenePromptMapper.insert(prompt);
            inserted.add(scene);
        }
        linkGeneratedMaterials(inserted, referenceImages, assetIds);
        return Map.of("projectId", projectId, "sceneCount", scenes.size());
    }

    /** 手动生成分镜后：把本次上传参考图/选中资产挂载到每个新分镜（用户可在分镜审查卡逐镜调整）。 */
    private void linkGeneratedMaterials(List<Scene> scenes, List<String> referenceImages, List<String> assetIds) {
        if (scenes == null || scenes.isEmpty()) return;
        try {
            if (assetIds != null && !assetIds.isEmpty()) {
                for (Scene s : scenes) {
                    assetService.linkSceneAssets(s.getId(), assetIds);
                }
            }
            if (referenceImages != null && !referenceImages.isEmpty()) {
                List<String> savedUrls = new ArrayList<>();
                for (String ref : referenceImages) {
                    if (ref == null || ref.isBlank()) continue;
                    try { savedUrls.add(fileStorageService.saveImageFromBase64(ref)); }
                    catch (Exception e) { log.warn("参考图保存失败，跳过挂载: {}", e.getMessage()); }
                }
                for (Scene s : scenes) {
                    int idx = 0;
                    for (String url : savedUrls) {
                        SceneReferenceImage ref = new SceneReferenceImage();
                        ref.setSceneId(s.getId());
                        ref.setImageUrl(url);
                        ref.setType("image");
                        ref.setPurpose("video");
                        ref.setRole("REFERENCE");
                        ref.setSortOrder(idx++);
                        sceneReferenceImageMapper.insert(ref);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("手动分镜素材挂载失败（分镜不受影响）: {}", e.getMessage());
        }
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

    private String callLLM(String model, String systemPrompt, String userPrompt) {
        try {
            String effectiveModel = resolveScriptModel(model, gatewayModelService.getDefaultTextModel());
            ChatClient.ChatClientRequestSpec spec = chatClient()
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(OpenAiChatOptions.builder().model(effectiveModel));
            return spec.call().content();
        } catch (Exception e) {
            throw new BusinessException(50201, "AI 生成分镜脚本失败: " + e.getMessage(), e);
        }
    }

    static String resolveScriptModel(String requestedModel, String gatewayDefaultModel) {
        return requestedModel == null || requestedModel.isBlank()
                ? gatewayDefaultModel
                : requestedModel;
    }

    /**
     * 理解模型看图：多张参考图 → 「图一/图二…」文字描述（多模态 Media 输入）。
     * 模型优先级：显式传入 understandingModel > 默认视觉模型（与脚本模型共用）。
     */
    private String callUnderstandingModel(String model, List<String> referenceImages) {
        String preferred = (model != null && !model.isBlank()) ? model : gatewayModelService.getDefaultVisionModel();
        List<String> candidates = resolveVisionModelCandidates(
                preferred,
                gatewayModelService.getDefaultVisionModel(),
                gatewayModelService.fetchModels("vision").stream()
                        .map(item -> item.get("value"))
                        .filter(value -> value != null && !value.isBlank())
                        .toList()
        );
        Exception lastError = null;
        for (String candidate : candidates) {
            try {
                return callVisionModel(candidate, referenceImages);
            } catch (Exception e) {
                lastError = e;
                log.warn("理解模型调用失败，尝试下一个: model={}, error={}", candidate, e.getMessage());
            }
        }
        throw new BusinessException(50201,
                "理解模型全部调用失败: " + (lastError == null ? "无可用模型" : lastError.getMessage()),
                lastError);
    }

    private String callVisionModel(String model, List<String> referenceImages) {
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
                .options(OpenAiChatOptions.builder().model(model))
                .call()
                .content();
    }

    static List<String> resolveVisionModelCandidates(String preferred, String gatewayDefault,
                                                     List<String> availableModels) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (preferred != null && !preferred.isBlank()) candidates.add(preferred);
        if (gatewayDefault != null && !gatewayDefault.isBlank()) candidates.add(gatewayDefault);
        if (availableModels != null) {
            availableModels.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(candidates::add);
        }
        return List.copyOf(candidates);
    }

    /** 组装分镜生成 user prompt：有理解描述时前置参考图视觉要素，否则仅剧本。 */
    private String buildUserPrompt(String scriptText, String understanding) {
        String base = promptConfig.get("script/storyboard-user");
        if (understanding != null && !understanding.isBlank()) {
            base += "参考图视觉要素（请在分镜中体现这些风格与要素）：\n" + understanding + "\n\n";
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
        int sceneNum = sceneMapper.maxSceneNumber(projectId);
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
            String json = StoryboardResponseParser.extractJsonArray(content);

            List<Map<String, Object>> scenes = new ArrayList<>();
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) {
                throw new IllegalArgumentException("解析结果不是 JSON 数组");
            }
            int sceneNum = sceneMapper.maxSceneNumber(projectId);
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
            String raw = content == null ? "" : content;
            log.error("解析 AI 返回的分镜数据失败: outputLength={}, prefix={}, suffix={}",
                    raw.length(), abbreviate(raw, 400), abbreviate(raw.length() > 400
                            ? raw.substring(raw.length() - 400) : raw, 400));
            throw new BusinessException(50201, "解析 AI 返回的分镜数据失败: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
