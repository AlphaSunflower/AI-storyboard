package com.moon.moonagent.ai.storyboard.impl;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.storyboard.StoryboardPlanResponseMapper;
import com.moon.moonagent.ai.storyboard.StoryboardPlanningService;
import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.config.PromptConfig;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI implementation of structured storyboard planning.
 */
@Service
@RequiredArgsConstructor
public class SpringAiStoryboardPlanningService implements StoryboardPlanningService {

    private final ChatClient.Builder chatClientBuilder;
    private final GatewayModelService gatewayModelService;
    private final AgentAiConfigProperties agentConfig;
    private final StoryboardPlanResponseMapper responseMapper;
    private final PromptConfig promptConfig;
    private volatile ChatClient textChatClient;
    private volatile ChatClient visionChatClient;

    @Override
    public List<StoryboardShot> plan(String script) {
        return plan(script, List.of());
    }

    @Override
    public List<StoryboardShot> plan(String script, List<String> referenceImages) {
        if (script == null || script.isBlank()) {
            throw new BusinessException(40001, "script 不能为空");
        }
        List<String> references = referenceImages == null
                ? List.of()
                : referenceImages.stream().filter(value -> value != null && !value.isBlank()).toList();

        String raw;
        try {
            if (references.isEmpty()) {
                raw = textChatClient()
                        .prompt()
                        .system(systemPrompt())
                        .user(buildTextPlanningPrompt(script, "", 0))
                        .call()
                        .content();
            } else {
                String understanding = understandReferences(script, references);
                raw = textChatClient()
                        .prompt()
                        .system(systemPrompt())
                        .user(buildTextPlanningPrompt(script, understanding, references.size()))
                        .call()
                        .content();
            }
        } catch (Exception e) {
            throw new BusinessException(50201, "storyboard planner model call failed: " + rootMessage(e), e);
        }
        return responseMapper.toShots(raw);
    }

    private String understandReferences(String script, List<String> referenceImages) {
        UserMessage message = buildVisionUserMessage(script, referenceImages);
        String content = visionChatClient()
                .prompt()
                .system(imageUnderstandingPrompt())
                .messages(message)
                .call()
                .content();
        if (content == null || content.isBlank()) {
            throw new BusinessException(50201, "reference image understanding returned empty content");
        }
        return content.trim();
    }

    private String buildTextPlanningPrompt(String script, String understanding, int referenceCount) {
        String sharedUserPrompt = promptConfig.get("script/storyboard-user");
        StringBuilder prompt = new StringBuilder(sharedUserPrompt == null || sharedUserPrompt.isBlank()
                ? "请根据以下剧本生成中文分镜脚本。"
                : sharedUserPrompt);
        prompt.append("\n\n剧本内容：\n").append(script.trim());
        if (referenceCount > 0) {
            prompt.append("\n\n视觉模型对参考图的理解结果：\n")
                    .append(understanding.isBlank() ? "No structured analysis returned." : understanding);
            prompt.append("\n\n参考图编号如下：");
            for (int index = 0; index < referenceCount; index++) {
                prompt.append("\n@image#").append(index + 1);
            }
            prompt.append("\n请把剧本和视觉连续性分析结合，只在需要携带对应参考图的镜头 referenceHints 中使用这些标记。");
        }
        prompt.append("\n\n画布接口要求：只输出一个 JSON 对象，顶层字段为 shots。")
                .append("\n所有 sceneDescription、imagePrompt、videoPrompt、negativePrompt、cameraMovement、shotType 使用中文；模型名、参数名和 @image#N 除外。")
                .append("\n\n").append(responseMapper.responseFormat());
        return prompt.toString();
    }

    private String systemPrompt() {
        String shared = promptConfig.get("script/storyboard-system");
        String base = shared == null || shared.isBlank()
                ? "你是专业分镜师，请将剧本拆成可生成图片和视频的连续镜头，所有镜头提示词使用中文。"
                : shared;
        return base + "\n\n画布适配：最终只输出 JSON 对象 {\"shots\": [...]}，不要输出 JSON 数组或其他包装。";
    }

    private String imageUnderstandingPrompt() {
        String shared = promptConfig.get("script/visual-understanding");
        return shared == null || shared.isBlank()
                ? "请用中文分析参考图，提炼角色、服装、道具、环境、光线、风格和连续性约束。只输出 JSON。"
                : shared;
    }

    private UserMessage buildVisionUserMessage(String script, List<String> referenceImages) {
        var builder = UserMessage.builder().text(
                "Script context:\n" + script.trim()
                        + "\n\nAnalyze all numbered reference images for identity, costume, prop, environment and style continuity.");
        List<Media> media = new ArrayList<>();
        for (String image : referenceImages) {
            Media item = toMedia(image);
            if (item != null) media.add(item);
        }
        return builder.media(media).build();
    }

    private ChatClient textChatClient() {
        if (textChatClient == null) {
            synchronized (this) {
                if (textChatClient == null) {
                    textChatClient = chatClientBuilder
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .model(gatewayModelService.getDefaultTextModel())
                                    .maxRetries(0)
                                    .timeout(Duration.ofSeconds(
                                            Math.max(1, agentConfig.getPlanTimeoutSeconds()))))
                            .build();
                }
            }
        }
        return textChatClient;
    }

    private ChatClient visionChatClient() {
        if (visionChatClient == null) {
            synchronized (this) {
                if (visionChatClient == null) {
                    visionChatClient = chatClientBuilder
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .model(gatewayModelService.getDefaultVisionModel())
                                    .maxRetries(0)
                                    .timeout(Duration.ofSeconds(
                                            Math.max(1, agentConfig.getPlanTimeoutSeconds()))))
                            .build();
                }
            }
        }
        return visionChatClient;
    }

    private static Media toMedia(String value) {
        int comma = value.indexOf(',');
        int semicolon = value.indexOf(';');
        if (!value.startsWith("data:") || comma < 0 || semicolon < 0 || semicolon > comma) return null;
        String mimeType = value.substring(5, semicolon);
        if (!mimeType.startsWith("image/")) return null;
        try {
            return Media.builder()
                    .mimeType(MimeType.valueOf(mimeType))
                    .data(value)
                    .build();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
