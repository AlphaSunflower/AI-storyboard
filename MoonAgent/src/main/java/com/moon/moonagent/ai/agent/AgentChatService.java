package com.moon.moonagent.ai.agent;

import com.moon.moonagent.dto.request.AgentAttachmentRequest;
import com.moon.moonagent.dto.request.AgentReferenceRequest;
import com.moon.moonagent.dto.response.AgentSteerResponse;
import com.moon.moonagent.dto.response.MessagePageVO;
import com.moon.moonagent.dto.response.MessageSearchHitVO;
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

    /** 创建会话并同时锁定编排引擎与会话级审批模式。 */
    AgentConversation createConversation(String userId, String projectId, String title,
                                         String orchestrationMode, String approvalMode);

    /**
     * 校验会话归属，返回会话。
     * 会话不存在与无权访问统一返回 40401 + 同一文案（M4），防止 IDOR 枚举。
     */
    AgentConversation getOwnedConversation(String userId, String conversationId);

    /** 会话消息列表（created_at 正序） */
    List<AgentMessage> listMessages(String conversationId);

    /** Keyset 分页：before 为空取最新一页，records 仍按时间正序返回。 */
    MessagePageVO listMessagesPage(String userId, String conversationId, String before, int limit);

    /** 当前用户消息全文搜索，服务端限制扫描和返回数量。 */
    List<MessageSearchHitVO> searchMessages(String userId, String query, int limit);

    /**
     * 清空会话聊天记录（上下文重置）：
     * 删除该会话全部消息 —— 下一条消息 AI 不再记得任何历史。
     * 会话本身与生成资产保留；仅影响当前会话，其他会话不受影响。
     */
    void clearMessages(String userId, String conversationId);

    /**
     * 「/清空上下文」：把会话硬删除成「刚建出来的新对话」。
     *
     * <p>与 {@link #clearMessages} 的区别是「真删除 + 彻底重置」：
     * <ul>
     *   <li>真删该会话全部上下文行（消息、轮次与直播事件、待确认 checkpoint、画布会话与运行、
     *       文档索引、异步任务、时间线清单、评审、工作流运行、外部动作台账、资产组绑定）；
     *   <li>清空 AgentScope 运行时上下文与沙箱工作区；
     *   <li>标题重置为「新对话」，{@code agentscope_session_initialized_at} 置空，
     *       下一轮从零重建会话。
     * </ul>
     * 产出素材（{@code agent_assets}）保留。
     *
     * <p>执行期间持有会话锁：避免和进行中的 turn 互相踩踏。
     */
    ClearContextResult clearContext(String userId, String conversationId);


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

    /**
     * 更新会话级审批模式。运行中的 turn 持有会话锁时拒绝切换，
     * 保证新模式从下一轮开始生效。
     */
    AgentConversation updateApprovalMode(String userId, String conversationId, String approvalMode);

    /**
     * 删除整条资产（仅限归属本人会话的资产；未归属资产拒绝 40401）。
     *
     * <p>软删除：MinIO 对象进清理队列，数据库行保留并把 status 置为 deleted。
     * 提示词、参数和结果 URL 因此可以继续在「生成记录」里查阅，只是标记为已删除。
     */
    void deleteAsset(String userId, String assetId);

    /**
     * 删除一条资产中的某一张结果（按 {@code resultIndex}，索引口径与前端 {@code assetUrls} 一致）。
     *
     * <p>只清理这一张的 MinIO 对象并从结果里摘掉；还有剩图时资产保持原状态，
     * 摘到一张不剩时才整体转成 deleted。
     *
     * @throws BusinessException 40001 索引越界；40401 资产不存在或无权访问
     */
    void deleteAssetResult(String userId, String assetId, int resultIndex);

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
    /** Persist one uploaded conversation reference and return stable asset metadata. */
    AgentReferenceUpload uploadReferenceImage(String userId, String conversationId,
                                              MultipartFile file, String purpose, String kind);

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
     * 回合中插入一条用户消息（steer）：消息落库、走同一 turn 的有序事件通道，并在下一个
     * 模型步骤注入；不打断正在执行的工具。回合已结束时返回 {@code not_running}，
     * 由前端改走普通发送（自动开新一轮）。
     */
    AgentSteerResponse steerTurn(String userId,
                                 String conversationId,
                                 String turnId,
                                 String content,
                                 String clientMessageId);

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

    /** Same as {@link #streamAgentScopeTurn} with persisted attachment metadata. */
    void streamAgentScopeTurn(String userId, String conversationId, String content,
                              List<String> picUrls, List<String> assetIds,
                              String routingHint, String editMessageId,
                              CanvasSessionContext canvasContext,
                              List<String> referenceVideos, List<String> referenceAudios,
                              List<AgentAttachmentRequest> attachments,
                              SseEmitter emitter);

    /**
     * Same as above with the per-turn 引用资料清单（正文 {@code @名称} 的机器可读版本）。
     * 与 attachments 并行透传，覆盖新建轮 / HITL 续流 / 编辑重发三条路径。
     */
    void streamAgentScopeTurn(String userId, String conversationId, String content,
                              List<String> picUrls, List<String> assetIds,
                              String routingHint, String editMessageId,
                              CanvasSessionContext canvasContext,
                              List<String> referenceVideos, List<String> referenceAudios,
                              List<AgentAttachmentRequest> attachments,
                              List<AgentReferenceRequest> references,
                              SseEmitter emitter);

    void streamAgentScopeTurn(String userId, String conversationId, String content,
                              List<String> picUrls, List<String> assetIds,
                              String routingHint, String editMessageId,
                              CanvasSessionContext canvasContext,
                              List<String> referenceVideos, List<String> referenceAudios,
                              String clientRequestId,
                              List<AgentAttachmentRequest> attachments,
                              SseEmitter emitter);

    void streamAgentScopeTurn(String userId, String conversationId, String content,
                              List<String> picUrls, List<String> assetIds,
                              String routingHint, String editMessageId,
                              CanvasSessionContext canvasContext,
                              List<String> referenceVideos, List<String> referenceAudios,
                              String clientRequestId,
                              List<AgentAttachmentRequest> attachments,
                              List<AgentReferenceRequest> references,
                              SseEmitter emitter);

    /**
     * Same as above with per-turn generation preferences（生成偏好：自动 / 自定义比例清晰度模型 /
     * 忽略对话中的生成方案）。{@code null} = 自动且不忽略方案，行为与不带该字段时一致。
     */
    void streamAgentScopeTurn(String userId, String conversationId, String content,
                              List<String> picUrls, List<String> assetIds,
                              String routingHint, String editMessageId,
                              CanvasSessionContext canvasContext,
                              List<String> referenceVideos, List<String> referenceAudios,
                              String clientRequestId,
                              List<AgentAttachmentRequest> attachments,
                              AgentGenerationPreferences generationPreferences,
                              SseEmitter emitter);

    /**
     * Same as above with per-turn 引用资料清单。生产入口（{@code /turns/stream}）走这个重载。
     */
    void streamAgentScopeTurn(String userId, String conversationId, String content,
                              List<String> picUrls, List<String> assetIds,
                              String routingHint, String editMessageId,
                              CanvasSessionContext canvasContext,
                              List<String> referenceVideos, List<String> referenceAudios,
                              String clientRequestId,
                              List<AgentAttachmentRequest> attachments,
                              List<AgentReferenceRequest> references,
                              AgentGenerationPreferences generationPreferences,
                              SseEmitter emitter);

    void streamAgentScopeTurn(String userId, String conversationId, String content,
                              List<String> picUrls, List<String> assetIds,
                              String routingHint, String editMessageId,
                              CanvasSessionContext canvasContext,
                              List<String> referenceVideos, List<String> referenceAudios,
                              String clientRequestId,
                              SseEmitter emitter);

    /** Resume an AgentScope HITL checkpoint and continue the turn stream. */
    void resumeAgentScopeTurn(String userId, String conversationId, String turnId,
                              String formToken,
                              String action, String customText, Map<String, String> params,
                              List<String> assetIds, CanvasSessionContext canvasContext,
                              SseEmitter emitter);

    void resumeCanvasSession(String userId, String conversationId, String sessionId,
                             String selectedCanvasId, boolean createNew, SseEmitter emitter);

    /** Runtime status used by the new frontend transport. */
    Map<String, Object> getRuntimeStatus(String userId, String conversationId);

    /**
     * Create an independent temporary conversation that snapshots the source
     * conversation's stable AgentScope context.
     */
    AgentConversation createSideChat(String userId, String sourceConversationId);

    /** Close and delete a temporary side-chat conversation. */
    void closeSideChat(String userId, String sideChatId);

    /**
     * 手动压缩会话上下文（/chat 斜杠命令「压缩上下文」）。
     * 只压缩 AgentScope 运行时上下文，产品消息记录不变。
     *
     * @return {compacted, status, messagesBefore, messagesAfter, tokensBefore, tokensAfter}
     */
    Map<String, Object> compactContext(String userId, String conversationId);

    /** Replay persisted AgentScope turn events after the supplied sequence. */
    List<AgentTurnEventEnvelope> getTurnEvents(String userId, String conversationId,
                                               String turnId, int afterSequence);

    /**
     * 打开某个 turn 的事件续传流：先补 {@code afterSeq} 之后的历史，再持续接收新事件直到终态。
     *
     * <p>用于页面刷新 / 新开标签页接上<b>正在运行</b>的 turn（替代"全量重放 + 轮询"）。
     * 该端点<b>不是执行入口</b>：不重复执行 turn、不分配序号、不因订阅者断开而中断 turn；
     * turn 已终态或已收尾时，补完历史即关闭连接。
     *
     * @param afterSeq 客户端已收到的最大序号，从它之后开始补
     * @param emitter  订阅连接（由调用方创建，超时策略与 turn 主连接一致）
     */
    void openTurnEventStream(String userId, String conversationId, String turnId,
                             long afterSeq, SseEmitter emitter);
}
