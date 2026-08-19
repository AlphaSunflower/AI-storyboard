package com.moon.moonagent.service.ai;

/**
 * 图片完善提示词增强服务 —— 图生图前先用视觉模型"看图"。
 *
 * <p>背景：Dify 工作流的 DeepSeek 无视觉能力，之前「完善图片设计方案」节点只能基于
 * 用户口述 + 上次文字 prompt 盲猜改图方案，与真实图片脱节。本服务把视觉理解放到后端：
 * 视觉模型（默认 gemini-3-flash-preview）直接看源图 + 用户诉求，输出结构化改图提示词
 * （图片现状 / 修改点 / 改后效果），再投喂给图生图 edits 接口——保证改图提示词与真实图片对齐。
 *
 * <p>调用链（Agent 对话完善图片路径）：Dify 工作流删掉了「完善图片设计方案」LLM 与 HITL 确认，
 * 由信号节点触发后端 → 本服务生成 refined_prompt → {@link ImageGenerationService}（mode=edit）图生图。
 *
 * <p>设计要点：
 * <ul>
 *   <li>模型固定网关默认视觉模型（{@link GatewayModelService#getDefaultVisionModel()}，gemini-3-flash-preview，
 *       支持视觉分析；不传 thinking_level——实测老张网关对 preview 系不透传思考参数）；</li>
 *   <li>源图从本地 uploads 读取转 base64 data URI 内联（参照 MiniMax 图生视频做法，无需上传公网）；</li>
 *   <li>输出结构化为 JSON {@code {image_analysis, modifications, refined_prompt}}，
 *       {@code refined_prompt} 直接投喂图生图；可排查"模型理解了什么、决定改什么"；</li>
 *   <li>超时 120s（视觉理解 + 大图 base64 传输，给足余量）。</li>
 *   <li>已换 Spring AI ChatClient 多模态（Media + UserMessage），替代原手写 JDK HttpClient 调用。</li>
 * </ul>
 */
public interface ImageRefinePromptService {

    /**
     * 生成图生图改图提示词：视觉模型看图 + 用户诉求 → refined_prompt。
     *
     * @param imagePath  源图路径（/api/files/images/xxx.png 或完整 URL），从本地 uploads 读取
     * @param userRequest 用户完善诉求（如"太暗了，改亮一点"）
     * @return refined_prompt 改图提示词文本
     */
    String buildRefinedPrompt(String imagePath, String userRequest);
}
