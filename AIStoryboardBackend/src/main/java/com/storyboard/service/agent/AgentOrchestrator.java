package com.storyboard.service.agent;

import com.storyboard.entity.AgentConversation;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * Agent 编排器 —— 应用层状态机（bounded loop）。
 *
 * 职责：意图识别 → 需求澄清（手动循环，逐轮确认）→ HITL checkpoint 落库 → 全自动执行
 * （自动模式工具循环）→ 结果推送。SSE 事件协议与前端约定不变：
 * message / workflow / human_input / message_end / confirm_result / video_plan / error。
 *
 * <p>实现：{@link com.storyboard.service.agent.impl.AgentOrchestratorImpl}。
 */
public interface AgentOrchestrator {

    /**
     * 编排一轮用户消息（流式入口，虚拟线程执行）。
     *
     * <p>流程：意图识别（IntentRecognitionService）→ 按意图路由（aisplit 分镜链 / pic 图片 /
     * video 视频 / other 回答）→ 需求澄清（手动循环）→ HITL checkpoint（如需人工确认，
     * 发 human_input 事件并 complete 等表单提交）→ 用户确认后自动执行（AgentTools）。
     *
     * @param conversation 会话（已校验归属）
     * @param content      用户消息内容
     * @param picUrl       本轮参考图 URL（可空）
     * @param emitter      SSE 输出
     * @return 本轮最后一条 message 内容（供调用方落库 assistant 消息；无消息返回空串）
     */
    String run(AgentConversation conversation, String content, String picUrl, SseEmitter emitter);

    /**
     * HITL 表单提交后续流：按 checkpoint 恢复对应 step 执行。
     *
     * @param conversation 会话（已校验归属）
     * @param formToken    checkpoint 一次性 token（前端 formToken / planToken）
     * @param action       用户确认动作（agree / disagree / generate_image / generate_video / refine / custom）
     * @param customText   自定义输入文本（action=custom 时必填，其余可空）
     * @param params       卡片参数选择器提交的生成参数（model/resolution/duration 等；空=未选择）
     * @param emitter      SSE 输出
     * @return 本轮最后一条 message 内容（供调用方落库 assistant 消息，与 {@link #run} 同契约；
     *         异步任务/失败分支返回空串）
     */
    String resume(AgentConversation conversation, String formToken, String action, String customText, Map<String, String> params, java.util.List<String> assetIds, SseEmitter emitter);
}
