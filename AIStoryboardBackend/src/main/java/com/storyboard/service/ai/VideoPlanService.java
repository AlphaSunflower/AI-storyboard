package com.storyboard.service.ai;

import java.util.Map;

/**
 * 图生视频方案设计服务 —— 视频生成前先用视觉模型"看图"。
 *
 * <p>背景：Dify 工作流的 DeepSeek 无视觉能力，文生视频方案设计节点只能基于用户口述写
 * prompt；而图生视频的首帧是用户上传的参考图，画面主体/构图/环境都以图为准，盲写 prompt
 * 会与真实画面脱节。本服务把视觉理解放到后端：视觉模型（默认 gemini-3-flash-preview）
 * 直接看源图 + 用户诉求，输出结构化视频方案（动态 prompt + 时长），再投喂给 MiniMax
 * 图生视频——保证方案与参考图对齐。
 *
 * <p>调用链（Agent 对话图生视频路径）：Dify 工作流「视频类型分流」判断携带参考图 →
 * 信号 answer 节点「后端执行图生视频方案设计」→ 后端 {@code triggerAutoVideoPlan} →
 * 本服务生成视频方案 → SSE 推 {@code video_plan} 事件 → 前端确认卡片 →
 * 「开始生成视频」→ MiniMax 图生视频。
 *
 * <p>设计要点（与 {@link ImageRefinePromptService} 对齐）：
 * <ul>
 *   <li>模型固定 {@link AiConfigProperties#getDefaultVisionModel()}（gemini-3-flash-preview，
 *       支持视觉分析；不传 thinking_level——实测老张网关对 preview 系不透传思考参数）；</li>
 *   <li>源图从本地 uploads 读取转 base64 data URI 内联（参照 MiniMax 图生视频做法，无需上传公网）；</li>
 *   <li>输出结构化为 JSON {@code {message, duration}}，{@code message} 直接作为图生视频
 *       prompt（首帧语义：画面主体/构图以首帧图为准，prompt 专注动态动作、运镜、光线氛围）；</li>
 *   <li>超时 120s（视觉理解 + 大图 base64 传输，给足余量）。</li>
 *   <li>已换 Spring AI ChatClient 多模态（Media + UserMessage），替代原手写 JDK HttpClient 调用。</li>
 * </ul>
 */
public interface VideoPlanService {

    /** 图生视频方案：message=视频 prompt（直接投喂生成），duration=时长（秒），params/reasons=LLM 推荐的生成参数与理由（可空） */
    record VideoPlan(String message, Integer duration,
                     Map<String, String> params, Map<String, String> reasons) {
        /** 兼容构造器：无推荐参数 */
        public VideoPlan(String message, Integer duration) {
            this(message, duration, Map.of(), Map.of());
        }
        /** 校验合法性：message 非空、duration 在 4~15 区间；非法返回 null 由调用方降级 */
        public boolean isValid() {
            if (message == null || message.isBlank()) return false;
            return duration != null && duration >= 4 && duration <= 15;
        }
    }

    /**
     * 生成图生视频方案：视觉模型看图 + 用户诉求 → 视频 prompt + 时长（+ 推荐生成参数）。
     *
     * @param imagePath        源图路径（/api/files/images/xxx.png 或完整 URL），从本地 uploads 读取
     * @param userRequest      用户视频创作诉求（如"让画面动起来，镜头缓缓推近"）
     * @param modelOptionsText 可选的模型与参数枚举文本（空=不要求 LLM 选参）
     * @return 视频方案；视觉理解失败或输出非法时抛 RuntimeException（调用方转业务错误）
     */
    VideoPlan buildVideoPlan(String imagePath, String userRequest, String modelOptionsText);
}
