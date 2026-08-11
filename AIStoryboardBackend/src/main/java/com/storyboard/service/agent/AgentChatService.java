package com.storyboard.service.agent;

import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.AgentMessage;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * Agent 对话服务 —— 会话/消息/资产 CRUD + 编排入口（Spring AI 编排驱动）。
 * 负责：会话校验、消息落库、SSE 流式转发、HITL checkpoint 提交续流。
 *
 * 事务边界说明（I1）：
 * - user 消息用独立事务（REQUIRES_NEW）保存并立即提交；
 * - 编排失败时 user 消息保留（独立事务已提交），assistant 消息不落库；
 * - 编排成功后，保存 assistant 消息在独立事务内完成。
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
     * 删除该会话全部消息 —— 下一条消息 AI 不再记得任何历史。
     * 会话本身与生成资产保留；仅影响当前会话，其他会话不受影响。
     */
    void clearMessages(String userId, String conversationId);

    /**
     * 满意完成：清空会话图片上下文（原 Dify storage_pic_talk 语义）。
     *
     * @return true（语义与旧 Dify 路径兼容：始终视为已完成）
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
     * 发送消息：落库 user 消息（独立事务）→ 编排回答 → 落库 assistant 消息。
     *
     * 事务语义（I1）：
     * - user 消息在独立事务（REQUIRES_NEW）中立即提交；
     * - 编排失败时 user 消息保留（独立事务已提交），assistant 消息不落库，抛业务异常；
     * - 编排成功后，保存 assistant 消息在独立事务内完成。
     *
     * @return assistant 消息
     */
    AgentMessage sendMessage(String userId, String conversationId, String content);

    /**
     * 流式发送消息：user 消息独立事务提交 → 编排（Spring AI 状态机）→ SSE 事件转发到 SseEmitter。
     * HITL 暂停 → 转发 human_input 事件 → 结束流（等表单提交 resume）。
     * message_end → 落库 assistant 消息 + 附带 sceneCount。
     */
    void streamMessage(String userId, String conversationId, String content, String picUrl, SseEmitter emitter);

    /**
     * 图生视频方案确认后生成（video_plan 事件「开始生成视频」触发，SSE）。
     * 流程：按 planToken 取方案快照（消费即移除，防重放）→ 确认动作落库 → 执行视频生成。
     */
    void generateVideoFromPlan(String userId, String conversationId, String planToken, SseEmitter emitter);

    /**
     * HITL 表单提交并续流：校验 checkpoint form_token → 一次性消费 → 编排 resume 恢复执行。
     */
    void submitFormAndResume(String userId, String conversationId, String formToken, String taskId, String action, SseEmitter emitter);

    /**
     * 视频异步任务状态查询（前端轮询）：按 taskId 查 agent_assets 行（归属校验，未归属/无权 40401）。
     *
     * @return Map：{taskId, assetId, status(queued/running/completed/failed), url, error}
     */
    Map<String, Object> getVideoTaskStatus(String userId, String taskId);
}