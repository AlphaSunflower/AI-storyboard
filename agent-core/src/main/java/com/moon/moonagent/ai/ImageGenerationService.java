package com.moon.moonagent.ai;

import java.util.List;
import java.util.Map;

/**
 * 图片生成服务 —— 负责调用 Laozhang API 进行生图/改图。
 *
 * 路由逻辑：
 * 1. 有参考图（referenceImages 非空）或 mode="edit" → /v1/images/edits multipart（经 LLM 网关 /v1/images/edits）
 * 2. 其他 → /v1/images/generations JSON（纯文生图，统一走 LLM 网关；Gemini 模型由网关转原生格式）
 */
public interface ImageGenerationService {

    /**
     * 生成/编辑图片入口方法（旧签名，n 默认 1）。
     */
    String generateImage(String sceneId, String prompt, String model,
                         String size, String quality, String aspectRatio,
                         List<String> referenceImages,
                         String mode, String generatedImageUrl);

    /**
     * 生成/编辑图片入口方法。
     *
     * @param sceneId         分镜 ID
     * @param prompt          生图/改图提示词
     * @param model           AI 模型名
     * @param size            图片尺寸（仅 generations 模式使用）
     * @param aspectRatio     宽高比（Gemini 模式使用）
     * @param referenceImages 参考图列表（base64 data URL 数组）
     * @param mode            "edit" 或 null（null 视为 "generate"）
     * @param generatedImageUrl 当前已生成图片的 URL 路径（完善图片时提供，作为 edits 源图）
     * @param n               生成数量（null 或 <=0 时默认 1）
     */
    String generateImage(String sceneId, String prompt, String model,
                         String size, String quality, String aspectRatio,
                         List<String> referenceImages,
                         String mode, String generatedImageUrl, Integer n);

    /**
     * 生成/编辑图片入口方法，返回全部本地路径列表（n>1 时多张；edits 分支恒单张）。
     *
     * @param n 生成数量（null 或 <=0 时默认 1）
     */
    List<String> generateImages(String sceneId, String prompt, String model,
                                String size, String quality, String aspectRatio,
                                List<String> referenceImages,
                                String mode, String generatedImageUrl, Integer n);

    /**
     * 带蒙版的图生图入口。蒙版 PNG 中透明像素表示可编辑区域。
     * 该方法用于画布区域化重绘，不改动现有生图/图生图调用方。
     */
    List<String> generateImagesWithMask(String sceneId, String prompt, String model,
                                        String size, String quality, String aspectRatio,
                                        List<String> referenceImages,
                                        String generatedImageUrl, String maskImageUrl,
                                        Map<String, Object> editParams);
}
