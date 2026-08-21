package com.storyboard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 提示词管理器——硬编码版，不再从文件加载。
 * <p>
 * key = "子目录/文件名"，与原 prompts/ 下的 .txt 文件路径一致，调用方零改动。
 */
@Component
public class PromptConfig {

    private static final Logger log = LoggerFactory.getLogger(PromptConfig.class);

    /** 所有提示词硬编码在此 */
    private static final Map<String, String> PROMPTS = Map.of(
        "script/storyboard-system",
            "你是一个专业的分镜师。创作风格：{{style}}。画幅：{{aspectRatio}}。\n" +
            "请以 JSON 数组格式返回分镜列表，每个分镜包含：sceneNumber(整数), scriptContent, imagePrompt, videoPrompt, negativePrompt, cameraMovement, shotType, soundDesign。",
        "script/storyboard-user",
            "请根据以下剧本内容生成分镜脚本，每个分镜包含：镜头号、剧本内容、生图提示词（格式：【镜头构图】→【场景主体】→【环境细节/道具】→【光线与色彩】→【氛围情绪】→【画质/风格】）、生视频提示词、反向提示词、机位和运动、镜头类型、声音设计。\n",
        "script/visual-understanding",
            "你是分镜前期视觉理解助手，擅长提炼参考图的关键视觉要素。"
    );

    /**
     * 获取提示词内容。
     *
     * @param key 格式为 "子目录/文件名"，如 "script/storyboard-system"
     * @return 提示词文本；找不到时返回空字符串并打 warn
     */
    public String get(String key) {
        String val = PROMPTS.get(key);
        if (val == null) {
            log.warn("[PromptConfig] 提示词未找到: {}", key);
            return "";
        }
        return val;
    }
}
