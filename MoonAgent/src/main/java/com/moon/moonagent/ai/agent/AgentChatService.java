package com.moon.moonagent.ai.agent;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventEnvelope;
import com.moon.moonagent.ai.agent.AgentTurnResult;
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
 * <p>实现：{@link com.moon.moonagent.ai.agent.impl.AgentChatServiceImpl}。
 */
public interface AgentChatService {

    /** 创建会话：校验项目归属，返回会话 */
    AgentConversation createConversation(String userId, String projectId, String title);

    /** 创建会话并锁定编排引擎；已有会话不得中途切换。 */
    AgentConversation createConversation(String userId, String projectId, String title, String orchestrationMode);

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
     * 满意完成：清空会话图片上下文（原图片上下文语义）。
     *
     * @return true（语义与旧路径兼容：始终视为已完成）
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
     * @param purpose 用户填写的用途/说明（如「人物参考（锁脸）」，可空）
     * @param kind    素材类型：reference（默认）/ asset（带文字约束，无需整理）
     * @return 图片 URL（/api/files/images/xxx）
     */
    String uploadReferenceImage(String userId, String conversationId, MultipartFile file,
                                String purpose, String kind);

    /** Persist the assistant message produced by a background wakeup turn. */
    AgentMessage persistWakeupResult(AgentConversation conversation, AgentTurnResult turn);

    /**
     * 视频异步任务状态查询（前端轮询）：按 taskId 查 agent_assets 行（归属校验，未归属/无权 40401）。
     *
     * @return Map：{taskId, assetId, status(queued/running/completed/failed), url, error}
     */
    Map<String, Object> getVideoTaskStatus(String userId, String taskId);

    /**
     * 获取会话的待处理 checkpoint（页面刷新恢复 HITL 卡片用）。
     * 仅返回未过期、status=pending 的最新一条；无则返回 null。
     *
     * @return checkpoint 恢复数据（formToken/formContent/actions/expirationTime + 可选 models/assets）；null=无待处理
     */
    Map<String, Object> getPendingCheckpoint(String conversationId);

    /**
     * 跳过当前会话所有待处理 HITL checkpoint（Mark expired），防止刷新/切换会话后旧卡片恢复。
     * 归属校验与 {@link #getPendingCheckpoint} 一致。
     */
    void skipPendingCheckpoints(String userId, String conversationId);

    /** 清除会话级画布编辑模式；之后消息恢复全局意图识别。 */
    void exitCanvasMode(String userId, String conversationId);

    /**
     * 打断当前会话的生成（「停止生成」按钮）：校验归属 → 置位会话级 interrupt 标志。
     * 编排的流式循环/阶段边界检测到后协作式取消——中断打字机、跳过被截断回答的落库、提前释放会话锁。
     *
     * @return true（命中断开请求；无论是否有活跃编排均安全返回）
     */
    boolean interrupt(String userId, String conversationId);

    /**
     * AgentScope native turn stream. Emits sequenced `turn_started`, `agent_event`,
     * `turn_paused`/`turn_completed`, and `turn_failed` envelopes.
     */
    void streamAgentScopeTurn(String userId, String conversationId, String content,
                              List<String> picUrls, List<String> assetIds,
                              String routingHint, String editMessageId,
                              CanvasSessionContext canvasContext,
                              List<String> referenceVideos, List<String> referenceAudios,
                              SseEmitter emitter);

    /** Resume an AgentScope HITL checkpoint and continue the turn stream. */
    void resumeAgentScopeTurn(String userId, String conversationId, String turnId,
                              String formToken,
                              String action, String customText, Map<String, String> params,
                              List<String> assetIds, CanvasSessionContext canvasContext,
                              SseEmitter emitter);

    /** Runtime status used by the new frontend transport. */
    Map<String, Object> getRuntimeStatus(String userId, String conversationId);

    /** Replay persisted AgentScope turn events after the supplied sequence. */
    List<AgentTurnEventEnvelope> getTurnEvents(String userId, String conversationId,
                                               String turnId, int afterSequence);
}
