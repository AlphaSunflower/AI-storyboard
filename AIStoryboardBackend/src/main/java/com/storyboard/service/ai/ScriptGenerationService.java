package com.storyboard.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

@Service
public class ScriptGenerationService {

    private final AiConfigProperties config;
    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

    public ScriptGenerationService(AiConfigProperties config, ProjectMapper projectMapper, SceneMapper sceneMapper) {
        this.config = config;
        this.projectMapper = projectMapper;
        this.sceneMapper = sceneMapper;
    }

    public List<Map<String, Object>> generateScenes(String projectId, String scriptText,
                                                      String creationType, String customTypeDesc,
                                                      String aspectRatio, String model) {
        String systemPrompt = buildSystemPrompt(creationType, customTypeDesc, aspectRatio);
        String userPrompt = "请根据以下剧本内容生成分镜脚本，每个分镜包含：镜头号、剧本内容、生图提示词（格式：【镜头构图】→【场景主体】→【环境细节/道具】→【光线与色彩】→【氛围情绪】→【画质/风格】）、生视频提示词、反向提示词、机位和运动、镜头类型、声音设计。\n\n剧本：\n" + scriptText;

        String response = callVisionApi(model, systemPrompt, userPrompt);
        return parseScenes(response, projectId);
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

    private String callVisionApi(String model, String systemPrompt, String userPrompt) {
        try {
            String effectiveModel = model != null ? model : config.getDefaultVisionModel();
            Map<String, Object> body = new HashMap<>();
            body.put("model", effectiveModel);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt));
            body.put("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                // chat 调用统一走 LLM 网关（/v1/chat/completions），Authorization 换网关 Key
                .uri(URI.create(config.getGatewayBaseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(120))
            .header("Authorization", "Bearer " + config.getGatewayApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Vision API returned " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = objectMapper.readTree(resp.body());
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new RuntimeException("AI 生成分镜脚本失败: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> parseScenes(String response, String projectId) {
        try {
            // 提取 JSON 数组（AI 可能在前后包裹 markdown 代码块）
            String json = response;
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
