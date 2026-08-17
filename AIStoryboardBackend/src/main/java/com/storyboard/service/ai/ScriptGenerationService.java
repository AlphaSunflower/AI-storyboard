package com.storyboard.service.ai;

import java.util.List;
import java.util.Map;

/**
 * 分镜脚本生成：通过 Spring AI ChatClient 调用 LLM 网关（spring.ai.openai.base-url 已指向网关 /v1，
 * 即原手写 HttpClient 直连的 /v1/chat/completions），生成并解析分镜脚本。
 */
public interface ScriptGenerationService {

    /**
     * 生成分镜脚本：调用 LLM 生成 JSON 分镜列表并解析为 Map 列表。
     *
     * @param projectId          项目 ID
     * @param scriptText         剧本内容
     * @param creationType       创作类型（movie/short_video/ad/drama/documentary/custom）
     * @param customTypeDesc     自定义类型描述（creationType=custom 时使用）
     * @param aspectRatio        画幅
     * @param model              分镜模型名（null 时用默认视觉模型）
     * @param understandingModel 理解模型名（null 时用默认理解模型；仅 referenceImages 非空时调用）
     * @param referenceImages    参考图 base64 data URI 列表（可为 null/空）
     * @return 分镜 Map 列表（sceneNumber/scriptContent/imagePrompt/videoPrompt 等字段）
     */
    List<Map<String, Object>> generateScenes(String projectId, String scriptText,
                                             String creationType, String customTypeDesc,
                                             String aspectRatio, String model,
                                             String understandingModel, List<String> referenceImages);

    /**
     * 生成分镜脚本（资产子集注入版）：仅把 {@code assetIds} 指定资产的设定集注入 system prompt；
     * 其余语义与 {@link #generateScenes(String, String, String, String, String, String, String, List)} 一致。
     *
     * @param assetIds 注入的资产 ID 子集（null/空 = 注入项目全部可用资产，与旧版一致）
     */
    List<Map<String, Object>> generateScenes(String projectId, String scriptText,
                                             String creationType, String customTypeDesc,
                                             String aspectRatio, String model,
                                             String understandingModel, List<String> referenceImages,
                                             List<String> assetIds);

    /**
     * 生成分镜脚本并直接落库（generateScenes + 批量写 scenes 表）。
     *
     * <p>供 AIController /api/ai/generate-script 使用：Controller 只负责收参返回，
     * 生成 + 写库全部在 Service 层完成（原 AIController 内的 sceneMapper 循环写库逻辑下沉至此）。
     *
     * @return 结果 Map：{projectId, sceneCount}
     */
    Map<String, Object> generateAndSaveScenes(String projectId, String scriptText,
                                              String creationType, String customTypeDesc,
                                              String aspectRatio, String model,
                                              String understandingModel, List<String> referenceImages);
}
