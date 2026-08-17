package com.storyboard.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.Scene;
import com.storyboard.dto.response.AssetVO;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.AssetService;
import com.storyboard.service.ai.GatewayModelService;
import com.storyboard.service.ai.ScriptGenerationService;
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
    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final AssetService assetService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatClient.Builder chatClientBuilder;
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
        String systemPrompt = buildSystemPrompt(creationType, customTypeDesc, aspectRatio);

        // 资产库设定集注入：项目资产 + 用户全局资产 → 文字卡塞进 system prompt 约束分镜
        //（资产为可选增强，注入失败仅告警跳过，不影响分镜生成；
        //  assetIds 非 null 时只注入指定子集——空列表=不注入，null=全量注入旧行为）
        try {
            List<AssetVO> assets = assetService.projectAssets(projectId);
            if (assets != null && !assets.isEmpty()) {
                if (assetIds != null) {
                    assets = assets.stream().filter(a -> assetIds.contains(a.id())).toList();
                }
                if (!assets.isEmpty()) {
                    systemPrompt += assetService.buildSheetText(assets);
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
            sceneMapper.insert(scene);
        }
        return Map.of("projectId", projectId, "sceneCount", scenes.size());
    }

    /**
     * 懒加载获取 ChatClient：默认模型固定为网关默认视觉模型（getDefaultVisionModel），超时 120s
     * （与原 HttpClient timeout 一致）；双重检查锁保证线程安全。
     */
    private ChatClient chatClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    chatClient = chatClientBuilder
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .model(gatewayModelService.getDefaultVisionModel())
                                    .timeout(Duration.ofSeconds(120)))
                            .build();
                }
            }
        }
        return chatClient;
    }

    private String buildSystemPrompt(String creationType, String customTypeDesc, String aspectRatio) {
        String style = switch (creationType) {
            case "movie" -> "电影化叙事、氛围渲染、视觉对比";
            case "short_video" -> "快节奏、竖屏为主、3秒抓人";
            case "ad" -> "品牌调性、卖点突出、光影质感";
            case "drama" -> "情绪递进、角色刻画、叙事完整";
            case "documentary" -> "稳重、旁白驱动、信息密度高";
            case "custom" -> customTypeDesc != null ? customTypeDesc : "";
            default -> "电影化叙事";
        };
        return "你是一个专业的分镜师。创作风格：" + style + "。画幅：" + aspectRatio +
            "。请以 JSON 数组格式返回分镜列表，每个分镜包含：sceneNumber(整数), scriptContent, imagePrompt, videoPrompt, negativePrompt, cameraMovement, shotType, soundDesign。";
    }

    private String callLLM(String model, String systemPrompt, String userPrompt) {
        try {
            ChatClient.ChatClientRequestSpec spec = chatClient()
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt);
            if (model != null) {
                // 单次调用覆盖模型（默认模型已在 defaultOptions 固定）
                spec = spec.options(OpenAiChatOptions.builder().model(model));
            }
            return spec.call().content();
        } catch (Exception e) {
            throw new RuntimeException("AI 生成分镜脚本失败: " + e.getMessage(), e);
        }
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
                .system("你是分镜前期视觉理解助手，擅长提炼参考图的关键视觉要素。")
                .messages(msg)
                .options(OpenAiChatOptions.builder().model(effModel))
                .call()
                .content();
    }

    /** 组装分镜生成 user prompt：有理解描述时前置参考图视觉要素，否则仅剧本。 */
    private String buildUserPrompt(String scriptText, String understanding) {
        String base = "请根据以下剧本内容生成分镜脚本，每个分镜包含：镜头号、剧本内容、生图提示词（格式：【镜头构图】→【场景主体】→【环境细节/道具】→【光线与色彩】→【氛围情绪】→【画质/风格】）、生视频提示词、反向提示词、机位和运动、镜头类型、声音设计。\n\n";
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
            throw new RuntimeException("解析 AI 返回的分镜数据失败: " + e.getMessage(), e);
        }
    }
}
