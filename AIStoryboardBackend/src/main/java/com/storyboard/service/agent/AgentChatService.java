package com.storyboard.service.agent;

import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.AgentMessage;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * Agent 对话服务 —— 代理 Dify /v1/chat-messages（blocking + streaming）。
 * 负责：会话校验、消息落库、Dify 调用、conversation_id 回填、SSE 流式转发、HITL 表单提交续流。
 *
 * 事务边界说明（I1）：
 * - user 消息用独立事务（REQUIRES_NEW）保存并立即提交；
 * - Dify 调用失败时 user 消息保留（独立事务已提交），assistant 消息不落库；
 * - Dify 成功后，回填 difyConversationId + 保存 assistant 消息在同一事务内完成。
 *
 * <p>实现：{@link com.storyboard.service.agent.impl.AgentChatServiceImpl}。
 */
public interface AgentChatService {

    /** 创建会话：校验项目归属，返回会话 */
    AgentConversation createConversation(String userId, String projectId, String title);

    /**
     * 校验会话归属，返回会话。
     * 会话不存在与无权访问统一返回 40401 + 同一文案（M4），防止 IDOR 枚举。
     */
    AgentConversation getOwnedConversation(String userId, String conversationId);

    /** 会话消息列表（created_at 正序） */
    List<AgentMessage> listMessages(String conversationId);

    /**
     * 清空会话聊天记录（上下文重置）：
     * 删除该会话全部消息 + 清空 difyConversationId —— 下一条消息会开启全新的 Dify 会话，
     * AI 不再记得任何历史。会话本身与生成资产保留；仅影响当前会话，其他会话不受影响。
     */
    void clearMessages(String userId, String conversationId);

    /**
     * 满意完成：清空 Dify 会话的 storage_pic_talk 变量（图片方案状态重置）。
     *
     * 触发点：前端 confirm_result 卡片「满意完成」→ 本方法；生成后的人工介入由后端驱动，
     * 清空动作也必须在后端做（而非 Dify 工作流 generate_image 分支）——点「生成图片」只是开始
     * 生成，未确认满意，变量必须保留供「继续完善」走完善路径；确认满意后才清空，下次图片
     * 需求才走全新设计。
     *
     * @return true=已清空（含 Dify 变量不存在等视为已完成）；false=Dify 会话未建立（无可清空）
     */
    boolean confirmImageDone(String userId, String conversationId);

    /** 当前用户的项目会话列表（updated_at 倒序）。 */
    List<AgentConversation> listConversations(String userId, String projectId);

    /** 删除会话（校验归属；消息/资产由 DB 外键级联删除）。 */
    void deleteConversation(String userId, String conversationId);

    /**
     * 重命名 / 归档会话（title/status 非空才更新；status 仅允许 active|archived；
     * 更新后手动刷新 updatedAt 保证列表置顶）。
     */
    AgentConversation updateConversation(String userId, String conversationId, String title, String status);

    /** 删除资产（仅限归属本人会话的资产；未归属资产拒绝 40401）。 */
    void deleteAsset(String userId, String assetId);

    /**
     * 会话资产列表（分页）。
     *
     * @return Map：{records, total, page, size}
     */
    Map<String, Object> listAssets(String conversationId, int page, int size);

    /**
     * 上传参考图：校验会话归属（可选）→ 保存图片 → 落库 agent_assets(type=reference) →
     * 参考图作为 user 消息落库（conversationId 非空时，前端对话窗口可见）。
     *
     * @return 图片 URL（/api/files/images/xxx）
     */
    String uploadReferenceImage(String userId, String conversationId, MultipartFile file);

    /**
     * 发送消息：落库 user 消息（独立事务）→ 调 Dify chat-messages → 回填 + 落库 assistant 消息。
     *
     * 事务语义（I1）：
     * - user 消息在独立事务（REQUIRES_NEW）中立即提交；
     * - Dify 失败时 user 消息保留（独立事务已提交），assistant 消息不落库，抛业务异常；
     * - Dify 成功后，"回填 difyConversationId + 保存 assistant 消息"在同一事务内完成。
     *
     * @return assistant 消息
     */
    AgentMessage sendMessage(String userId, String conversationId, String content);

    /**
     * 流式发送消息：user 消息独立事务提交 → 代理 Dify streaming → 事件裁剪转发到 SseEmitter。
     * 收到 human_input_required → 转发 human_input 事件 → 结束流（Dify 侧 pause 自动关闭）。
     * message_end → 落库 assistant 消息 + 回填 dify_conversation_id + 附带 sceneCount。
     */
    void streamMessage(String userId, String conversationId, String content, String picUrl, SseEmitter emitter);

    /**
     * 图生视频方案确认后生成（video_plan 事件「开始生成视频」触发，SSE）。
     * 流程：按 planToken 取方案快照（消费即移除，防重放）→ 确认动作落库 → 执行视频生成。
     */
    void generateVideoFromPlan(String userId, String conversationId, String planToken, SseEmitter emitter);

    /**
     * HITL 表单提交并续流：
     * 1. POST {base}/v1/form/human_input/{formToken}（body {action}）
     * 2. 成功 → GET {base}/v1/workflow/{taskId}/events?user={userId} 续传 SSE（复用 forwardDifySse）
     */
    void submitFormAndResume(String userId, String conversationId, String formToken, String taskId, String action, SseEmitter emitter);
}
