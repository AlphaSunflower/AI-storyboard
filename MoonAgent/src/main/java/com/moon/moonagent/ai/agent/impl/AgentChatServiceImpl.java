package com.moon.moonagent.ai.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.RawValue;
import com.moon.moonagent.ai.agent.AgentSceneItem;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.storyboard.common.BusinessException;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.security.FileUrlSigner;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.AgentReferenceUpload;
import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.AgentApprovalMode;
import com.moon.moonagent.ai.agent.AgentGenerationPreferences;
import com.moon.moonagent.ai.agent.AgentInterruptException;
import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.dto.request.AgentAttachmentRequest;
import com.moon.moonagent.dto.response.AgentMessageVO;
import com.moon.moonagent.dto.response.MessagePageVO;
import com.moon.moonagent.dto.response.MessageSearchHitVO;
import com.moon.moonagent.ai.agent.agentscope.MoonAgentRuntime;
import com.moon.moonagent.ai.agent.agentscope.CompactOutcome;
import com.moon.moonagent.ai.agent.agentscope.AgentScopeAsyncTaskService;
import com.moon.moonagent.ai.agent.turn.AgentScopeEventMapper;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventEnvelope;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventPublisher;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventStore;
import com.moon.moonagent.ai.agent.recovery.CheckpointClaim;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeException;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeService;
import com.moon.moonagent.ai.agent.recovery.ResumeCommand;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.AgentContextWindowResolver;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.ConversationTitleService;
import com.moon.moonagent.orchestration.OrchestrationMode;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.ImageRefinePromptService;
import com.moon.moonagent.ai.VideoPlanService;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 对话服务 —— 代理 AI 编排（blocking + streaming）。
 * 负责：会话校验、消息落库、编排调用、conversation_id 回填、SSE 流式转发、HITL 表单提交续流。
 *
 * 事务边界说明（I1）：
 * - user 消息用独立事务（REQUIRES_NEW）保存并立即提交；
 * - 编排调用失败时 user 消息保留（独立事务已提交），assistant 消息不落库；
 * - 编排成功后，回填会话ID + 保存 assistant 消息在同一事务内完成。
 */
@Service
@RequiredArgsConstructor
public class AgentChatServiceImpl implements AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatServiceImpl.class);
    private static final String CANVAS_BIND_ACTION = "canvas-bind";

    private final AgentConversationMapper conversationMapper;
    private final AgentGenerationService generationService;
    private final AgentMessageMapper messageMapper;
    private final AgentAssetMapper assetMapper;
    private final ConversationTitleService titleService;
    private final StoryboardClient storyboardClient;
    private final FileStorageService fileStorageService;
    private final FileUrlSigner fileUrlSigner;
    private final AiConfigProperties config;
    private final com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport orchestratorSupport;
    private final com.moon.moonagent.mapper.AgentCheckpointMapper checkpointMapper;
    private final CheckpointResumeService checkpointResumeService;
    private final com.moon.moonagent.ai.agent.ConversationLock conversationLock;
    private final ProjectCreativeContextService creativeContext;
    private final com.moon.moonagent.ai.agent.agentscope.AgentCanvasSessionService canvasSessionService;
    private final com.moon.moonagent.ai.agent.agentscope.AgentCanvasRunService canvasRunService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 专用 executor（I6）：SSE 长连接任务不再占用 ForkJoinPool.commonPool，
     * 避免一条长流拖垮其他并行流。JDK 21 虚拟线程天然 daemon、无池占用，无需手动 shutdown。
     */
    private final ExecutorService agentExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private transient MoonAgentRuntime agentScopeMediaRuntime;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private transient AgentScopeAsyncTaskService agentScopeAsyncTaskService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private transient AgentTurnEventStore turnEventStore;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private transient AgentScopeEventMapper agentScopeEventMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private transient AgentContextWindowResolver agentContextWindowResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private transient com.moon.moonagent.service.StorageCleanupService storageCleanupService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private transient com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient sandboxClient;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private transient com.moon.moonagent.ai.agent.sandbox.SandboxSessionRegistry sandboxSessionRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private transient com.moon.moonagent.ai.agent.sandbox.SandboxOutputReconciler sandboxOutputReconciler;

    /**
     * 首条消息异步 AI 重命名标题：
     * - titleScheduled：并发去重，仅调度一次；任务结束（无论成败）移除，允许清空后重聊再次触发；
     * - renamedTitleByConversation：重命名落库成功的新标题暂存，随本轮 message_end 一次性下发前端
     *   （remove 取走即删，绝不重复推送；不做轮询/持续推送）。
     */
    private final Set<String> titleScheduled = ConcurrentHashMap.newKeySet();
    private final Map<String, String> renamedTitleByConversation = new ConcurrentHashMap<>();

    /**
     * 完善图片自动生成信号节点标题（工作流 answer 节点）。
     * 工作流已删除「完善图片设计方案」LLM 与 HITL 人工介入，改为：用户诉求 → user_finishing(code)
     * → 赋值 → 本 answer 节点（文案"结合用户输入理解图片优化提示词中..."）。后端监听到该节点
     * node_finished 即自动触发：视觉模型看图 + 用户诉求 → refined_prompt → 图生图 edits。
     * 必须与 Moon智能体.yml 中该 answer 节点的 title 完全一致。
     */
    private static final String AUTO_REFINE_SIGNAL_TITLE = "后端执行识别图片加人工介入流程";

    /**
     * 图生视频方案设计信号节点标题（工作流 answer 节点）。
     * 工作流「视频类型分流」判断携带参考图（conversation.picture 非空）→ 走本 answer 节点
     * （文案"结合你上传的参考图设计视频方案中..."）。后端监听到该节点 node_finished 即触发：
     * 视觉模型看图 + 用户诉求 → 视频方案（prompt + 时长）→ 推 video_plan 事件 →
     * 前端确认卡片 → 「开始生成视频」→ MiniMax 图生视频。
     * 必须与 Moon智能体.yml 中该 answer 节点的 title 完全一致。
     */
    private static final String VIDEO_PLAN_SIGNAL_TITLE = "后端执行图生视频方案设计";

    /**
     * 懒加载 TransactionTemplate（REQUIRES_NEW）：user 消息保存使用独立事务，即使外层存在事务也单独提交，
     * 保证编排调用失败时 user 消息不被回滚。构造器内构建逻辑迁移至此，统一 @RequiredArgsConstructor 注入。
     */
    private final PlatformTransactionManager transactionManager;
    private volatile TransactionTemplate transactionTemplate;

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            synchronized (this) {
                if (transactionTemplate == null) {
                    TransactionTemplate tt = new TransactionTemplate(transactionManager);
                    tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    transactionTemplate = tt;
                }
            }
        }
        return transactionTemplate;
    }

    /** 创建会话：校验项目归属，返回会话 */
    public AgentConversation createConversation(String userId, String projectId, String title) {
        return createConversation(userId, projectId, title, null);
    }

    @Override
    public AgentConversation createConversation(String userId, String projectId, String title,
                                                String orchestrationMode) {
        return createConversation(userId, projectId, title, orchestrationMode, null);
    }

    @Override
    public AgentConversation createConversation(String userId, String projectId, String title,
                                                String orchestrationMode, String approvalMode) {
        var project = storyboardClient.getProject(projectId);
        if (project == null) throw new BusinessException(40401, "项目不存在");
        if (!userId.equals(project.get("userId"))) throw new BusinessException(40301, "无权为该项目创建对话");

        AgentConversation conversation = new AgentConversation();
        conversation.setUserId(userId);
        conversation.setProjectId(projectId);
        conversation.setTitle(title != null && !title.isBlank() ? title : "新对话");
        conversation.setStatus("active");
        OrchestrationMode mode = OrchestrationMode.AGENTSCOPE;
        conversation.setOrchestrationMode(mode.name());
        conversation.setEngineVersion(mode.engineVersion());
        conversation.setDebugComparisonEnabled(false);
        conversation.setApprovalMode(AgentApprovalMode.from(approvalMode).name());
        conversation.setAgentscopeSessionInitializedAt(OffsetDateTime.now());
        conversationMapper.insert(conversation);
        return conversation;
    }

    /**
     * 校验会话归属，返回会话。
     * 会话不存在与无权访问统一返回 40401 + 同一文案（M4），防止 IDOR 枚举。
     */
    public AgentConversation getOwnedConversation(String userId, String conversationId) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())) {
            throw new BusinessException(40401, "会话不存在或无权访问");
        }
        return conversation;
    }

    private static void requireWritable(AgentConversation conversation) {
        if (conversation == null) return;
        if ("archived".equalsIgnoreCase(conversation.getStatus())) {
            throw new BusinessException(40903, "该会话已归档，只能查看历史记录，请新建会话后继续。");
        }
        if (OrchestrationMode.from(conversation.getOrchestrationMode())
                == OrchestrationMode.LEGACY) {
            throw new BusinessException(40903, "旧版会话已只读归档，请新建会话后继续。");
        }
    }

    /** 会话消息列表（created_at 正序） */
    public List<AgentMessage> listMessages(String conversationId) {
        List<AgentMessage> latest = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getConversationId, conversationId)
            .orderByDesc(AgentMessage::getCreatedAt)
            .orderByDesc(AgentMessage::getId)
            .last("LIMIT 500"));
        return latest.reversed();
    }

    @Override
    @Transactional(readOnly = true)
    public MessagePageVO listMessagesPage(String userId, String conversationId,
                                          String before, int limit) {
        getOwnedConversation(userId, conversationId);
        int safeLimit = Math.max(1, Math.min(100, limit));
        LambdaQueryWrapper<AgentMessage> query = new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId);
        MessageCursor cursor = decodeCursor(before);
        if (cursor != null) {
            query.and(wrapper -> wrapper
                    .lt(AgentMessage::getCreatedAt, cursor.createdAt())
                    .or(nested -> nested
                            .eq(AgentMessage::getCreatedAt, cursor.createdAt())
                            .lt(AgentMessage::getId, cursor.id())));
        }
        List<AgentMessage> rows = messageMapper.selectList(query
                .orderByDesc(AgentMessage::getCreatedAt)
                .orderByDesc(AgentMessage::getId)
                .last("LIMIT " + (safeLimit + 1)));
        boolean hasMore = rows.size() > safeLimit;
        List<AgentMessage> pageRows = hasMore ? rows.subList(0, safeLimit) : rows;
        String nextCursor = hasMore && !pageRows.isEmpty()
                ? encodeCursor(pageRows.getLast().getCreatedAt(), pageRows.getLast().getId())
                : null;
        List<AgentMessageVO> records = pageRows.reversed().stream()
                .map(this::messageVo)
                .toList();
        return new MessagePageVO(records, nextCursor, hasMore);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageSearchHitVO> searchMessages(String userId, String query, int limit) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) return List.of();
        List<AgentConversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getUserId, userId)
                        .ne(AgentConversation::getStatus, "temporary")
                        .orderByDesc(AgentConversation::getUpdatedAt)
                        .last("LIMIT 200"));
        if (conversations.isEmpty()) return List.of();
        Map<String, AgentConversation> byId = conversations.stream()
                .collect(java.util.stream.Collectors.toMap(
                        AgentConversation::getId, conversation -> conversation));
        int safeLimit = Math.max(1, Math.min(200, limit));
        return messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                        .in(AgentMessage::getConversationId, byId.keySet())
                        .like(AgentMessage::getContent, q)
                        .orderByDesc(AgentMessage::getCreatedAt)
                        .last("LIMIT " + safeLimit))
                .stream()
                .map(message -> {
                    AgentConversation conversation = byId.get(message.getConversationId());
                    return new MessageSearchHitVO(
                            message.getId(),
                            message.getConversationId(),
                            conversation == null ? "" : conversation.getTitle(),
                            message.getRole(),
                            message.getContent(),
                            message.getCreatedAt());
                })
                .toList();
    }

    private AgentMessageVO messageVo(AgentMessage message) {
        String content = message.getContent();
        String signedContent = fileUrlSigner.signEmbeddedUrls(content);
        Map<String, Object> metadata = message.getMetadata() == null ? Map.of() : message.getMetadata();
        Map<String, Object> signedMetadata = fileUrlSigner.signMetadata(metadata);
        if (signedMetadata == null || (signedMetadata.isEmpty() && !metadata.isEmpty())) {
            signedMetadata = metadata;
        }
        return new AgentMessageVO(
                message.getId(),
                message.getConversationId(),
                message.getRole(),
                signedContent == null ? content : signedContent,
                signedMetadata,
                message.getCreatedAt());
    }

    private static String encodeCursor(OffsetDateTime createdAt, String id) {
        String value = (createdAt == null ? "" : createdAt.toString()) + "|" + (id == null ? "" : id);
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static MessageCursor decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            String value = new String(java.util.Base64.getUrlDecoder().decode(encoded),
                    java.nio.charset.StandardCharsets.UTF_8);
            int split = value.lastIndexOf('|');
            if (split <= 0 || split >= value.length() - 1) return null;
            return new MessageCursor(
                    OffsetDateTime.parse(value.substring(0, split)),
                    value.substring(split + 1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private record MessageCursor(OffsetDateTime createdAt, String id) {
    }

    /**
     * 清空会话聊天记录（上下文重置）：
     * 删除该会话全部消息 + 清空会话ID —— 下一条消息会开启全新的会话，
     * AI 不再记得任何历史。会话本身与生成资产保留；仅影响当前会话，其他会话不受影响。
     */
    public void clearMessages(String userId, String conversationId) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        requireWritable(conversation);
        transactionTemplate().executeWithoutResult(tx -> {
            messageMapper.delete(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId));
            conversationMapper.updateById(conversation); // 触发 updatedAt fill
        });
        if (agentScopeMediaRuntime != null) {
            agentScopeMediaRuntime.clearSession(userId, conversationId);
        }
        cleanupSandbox(conversationId);
        log.info("已清空会话聊天记录: conversationId={}, userId={}", conversationId, userId);
    }

    /**
     * 满意完成：清空会话图片上下文（原图片上下文语义）。
     *
     * 触发点：前端 confirm_result 卡片「满意完成」；图片方案状态重置，
     * 下次图片需求走全新设计（而非继续完善）。
     *
     * @return true（语义与旧路径兼容：始终视为已完成）
     */
    public boolean confirmImageDone(String userId, String conversationId) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        // 满意完成：清空会话图片上下文（原图片上下文语义）——
        // 图片方案状态重置，下次图片需求走全新设计（而非继续完善）
        log.info("满意完成：已清空会话图片上下文: conversationId={}", conversationId);
        return true;
    }

    /**
     * 首条消息异步 AI 重命名标题：
     * 判定「该消息是会话第一条消息 + 标题仍为默认值 + 并发去重成功」三条件，
     * 满足则向 agentExecutor 提交异步任务（不阻塞编排主流程，失败仅日志）；
     * 落库成功的新标题暂存供 message_end 一次性推送，失败静默降级。
     *
     * ⚠ 调用时机硬性要求：必须在本次 user 消息【落库前】调用——落库后
     * selectCount 已 +1，"首条"判定（count==0）永远不成立（线上实测踩坑）。
     */
    private void maybeScheduleTitleRename(AgentConversation conversation, String content) {
        if ("temporary".equalsIgnoreCase(conversation.getStatus())) return;
        try {
            long msgCount = messageMapper.selectCount(new LambdaQueryWrapper<AgentMessage>()
                    .eq(AgentMessage::getConversationId, conversation.getId()));
            boolean defaultTitle = conversation.getTitle() == null
                    || conversation.getTitle().isBlank()
                    || "新对话".equals(conversation.getTitle());
            if (msgCount == 0 && defaultTitle && titleScheduled.add(conversation.getId())) {
                CompletableFuture.runAsync(() -> {
                    try {
                        titleService.renameOnFirstMessage(conversation.getId(), content);
                        // 落库成功 → 暂存新标题供 message_end 一次性推送；失败则 map 无值，静默降级
                        AgentConversation fresh = conversationMapper.selectById(conversation.getId());
                        String t = fresh != null ? fresh.getTitle() : null;
                        if (t != null && !t.isBlank() && !"新对话".equals(t)) {
                            renamedTitleByConversation.put(conversation.getId(), t);
                        }
                    } finally {
                        titleScheduled.remove(conversation.getId());
                    }
                }, agentExecutor);
            }
        } catch (Exception e) {
            log.debug("标题重命名调度失败(忽略): conversationId={}, error={}", conversation.getId(), e.getMessage());
        }
    }

    @Override
    public List<AgentConversation> listConversations(String userId, String projectId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getUserId, userId)
                .eq(AgentConversation::getProjectId, projectId)
                .ne(AgentConversation::getStatus, "temporary")
                .orderByDesc(AgentConversation::getUpdatedAt));
    }

    @Override
    public void deleteConversation(String userId, String conversationId) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        // 清理内存态（防泄漏）
        titleScheduled.remove(conversationId);
        renamedTitleByConversation.remove(conversationId);
        orchestratorSupport.cleanupOnDelete(conversationId);
        if (agentScopeMediaRuntime != null) {
            agentScopeMediaRuntime.clearSession(userId, conversationId);
        }
        if (storageCleanupService != null) {
            List<AgentAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<AgentAsset>()
                    .eq(AgentAsset::getConversationId, conversationId));
            assets.forEach(asset -> {
                storageCleanupService.enqueue(asset.getUrl());
                storageCleanupService.enqueueAll(resultUrls(asset));
            });
        }
        conversationLock.releaseAndRemove(conversationId);
        // 消息/资产由 DB 外键 ON DELETE CASCADE 级联删除
        conversationMapper.deleteById(conversation.getId());
    }

    @Override
    public AgentConversation updateConversation(String userId, String conversationId, String title, String status) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        if (title != null && !title.isBlank()) {
            conversation.setTitle(title.trim());
        }
        if (status != null && !status.isBlank()) {
            if (!"active".equals(status) && !"archived".equals(status)) {
                throw new BusinessException(40001, "会话状态非法");
            }
            conversation.setStatus(status);
        }
        // PATCH 后手动刷新 updatedAt：MyBatis-Plus strictUpdateFill 仅在字段为 null 时填充，
        // 实体加载后 updatedAt 非空会写回旧值，导致列表按 updated_at 倒序时重命名/归档不置顶
        conversation.setUpdatedAt(OffsetDateTime.now());
        conversationMapper.updateById(conversation);
        return conversation;
    }

    @Override
    public AgentConversation updateApprovalMode(String userId, String conversationId,
                                                String approvalMode) {
        AgentApprovalMode mode = AgentApprovalMode.require(approvalMode);
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        requireWritable(conversation);
        if (!conversationLock.tryAcquire(conversationId)) {
            throw new BusinessException(40901, "当前对话正在处理中，暂时不能切换审批模式");
        }
        try {
            conversation.setApprovalMode(mode.name());
            conversation.setUpdatedAt(OffsetDateTime.now());
            conversationMapper.updateById(conversation);
            return conversation;
        } finally {
            conversationLock.release(conversationId);
        }
    }

    @Override
    public void deleteAsset(String userId, String assetId) {
        AgentAsset asset = assetMapper.selectById(assetId);
        if (asset == null || asset.getConversationId() == null || asset.getConversationId().isBlank()) {
            throw new BusinessException(40401, "资产不存在或无权访问");
        }
        // 校验资产归属的会话属于本人（防 IDOR）
        getOwnedConversation(userId, asset.getConversationId());
        if (storageCleanupService != null) {
            storageCleanupService.enqueue(asset.getUrl());
            storageCleanupService.enqueueAll(resultUrls(asset));
        }
        assetMapper.deleteById(assetId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listAssets(String conversationId, int page, int size) {
        // 分页安全钳制（原 Controller 逻辑下沉：page 下限 1、size 1~50）
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, size));
        // 产出素材 = 生成结果（image/video），排除用户自己上传的参考图（type=reference，不算产出）
        Page<AgentAsset> resultPage = assetMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<AgentAsset>()
                        .eq(AgentAsset::getConversationId, conversationId)
                        .ne(AgentAsset::getType, "reference")
                        .orderByDesc(AgentAsset::getCreatedAt));
        List<AgentAsset> records = resultPage.getRecords() == null
                ? List.of() : resultPage.getRecords();
        records.forEach(this::signAssetUrls);
        long total = resultPage.getTotal();
        long imageTotal = countAssets(conversationId, "image");
        long videoTotal = countAssets(conversationId, "video");
        long documentTotal = countAssets(conversationId, "document");
        return Map.of(
                "records", records,
                "total", total,
                "imageTotal", imageTotal,
                "videoTotal", videoTotal,
                "documentTotal", documentTotal,
                "mediaTotal", imageTotal + videoTotal,
                "page", safePage,
                "size", safeSize);
    }

    private long countAssets(String conversationId, String type) {
        return assetMapper.selectCount(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getConversationId, conversationId)
                .eq(AgentAsset::getType, type));
    }

    private void signAssetUrls(AgentAsset asset) {
        asset.setUrl(fileUrlSigner.signIfLocal(asset.getUrl()));
        asset.setResultUrls(fileUrlSigner.signJsonArray(asset.getResultUrls()));
    }

    /** 统计某一类型下所有生成结果 URL；一次生成含多图时全部计入。 */
    private int mediaCount(List<AgentAsset> assets, String type) {
        return assets.stream()
                .filter(asset -> type.equals(asset.getType()))
                .mapToInt(asset -> resultUrls(asset).size())
                .sum();
    }

    /** result_urls 支持字符串数组与历史对象数组；无结果时回退单 url。 */
    private List<String> resultUrls(AgentAsset asset) {
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        if (asset.getResultUrls() != null && !asset.getResultUrls().isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(asset.getResultUrls());
                if (root.isArray()) {
                    for (JsonNode item : root) {
                        String url = item.isTextual() ? item.asText("") : item.path("url").asText("");
                        if (!url.isBlank()) urls.add(url);
                    }
                }
            } catch (Exception ignored) {
                // 坏数据回退 url 单字段
            }
        }
        if (urls.isEmpty() && asset.getUrl() != null && !asset.getUrl().isBlank()) {
            urls.add(asset.getUrl());
        }
        return List.copyOf(urls);
    }

    @Override
    public AgentReferenceUpload uploadReferenceImage(String userId, String conversationId,
                                                     MultipartFile file, String purpose, String kind) {
        // 校验会话归属（传了 conversationId 时）
        AgentConversation conversation = null;
        if (conversationId != null && !conversationId.isBlank()) {
            conversation = getOwnedConversation(userId, conversationId);
            requireWritable(conversation);
        }
        String mediaType = referenceMediaType(file.getContentType());
        String url = fileStorageService.saveUploadedReference(mediaType, file);

        // 落库 agent_assets（type=reference）
        AgentAsset asset = new AgentAsset();
        asset.setConversationId(conversationId != null && !conversationId.isBlank() ? conversationId : null);
        asset.setUserId(userId);
        asset.setProjectId(conversation == null ? null : conversation.getProjectId());
        asset.setType("reference");
        asset.setUrl(url);
        asset.setOriginalName(file.getOriginalFilename());
        asset.setMimeType(file.getContentType());
        asset.setPurpose(purpose == null || purpose.isBlank() ? null : purpose.trim());
        asset.setKind((kind == null || kind.isBlank()) ? mediaType : kind.trim());
        asset.setStatus("completed");
        assetMapper.insert(asset);
        log.info("Agent 参考图已落库: assetId={}, url={}", asset.getId(), url);

        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "未命名文件" : file.getOriginalFilename();
        String mimeType = file.getContentType() == null ? "" : file.getContentType();
        return new AgentReferenceUpload(asset.getId(), url, name, mimeType, file.getSize());
    }

    private CanvasSessionContext resolveCanvasContext(AgentConversation conversation,
                                                      CanvasSessionContext incoming) {
        if (incoming != null && incoming.isCanvasEditMode()) {
            try {
                String encoded = incoming.toJson();
                if (!encoded.equals(conversation.getCanvasContext())) {
                    conversation.setCanvasContext(encoded);
                    conversationMapper.updateById(conversation);
                }
            } catch (Exception e) {
                log.warn("保存会话画布上下文失败: conversationId={}, error={}", conversation.getId(), e.getMessage());
            }
            return incoming;
        }
        String stored = conversation.getCanvasContext();
        if (stored == null || stored.isBlank()) return null;
        try {
            CanvasSessionContext context = CanvasSessionContext.fromJson(stored);
            return context.isCanvasEditMode() ? context : null;
        } catch (Exception e) {
            log.warn("恢复会话画布上下文失败: conversationId={}, error={}", conversation.getId(), e.getMessage());
            return null;
        }
    }

    @Override
    public void exitCanvasMode(String userId, String conversationId) {
        getOwnedConversation(userId, conversationId);
        conversationMapper.update(null, new UpdateWrapper<AgentConversation>()
                .eq("id", conversationId)
                .set("canvas_context", null)
                .set("updated_at", OffsetDateTime.now()));
        log.info("已离开画布编辑模式: conversationId={}", conversationId);
    }

    static String referenceMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) return "file";
        if (contentType.startsWith("image/")) return "image";
        if (contentType.startsWith("video/") || contentType.contains("quicktime")) return "video";
        if (contentType.startsWith("audio/")) return "audio";
        return "file";
    }

    @Override
    public AgentMessage persistWakeupResult(AgentConversation conversation, AgentTurnResult turn) {
        return persistAssistant(conversation, turn);
    }

    private EditedTurn prepareEditedTurn(String userId, AgentConversation conversation,
                                         String messageId, String content,
                                         List<String> requestedPicUrls, List<String> requestedAssetIds,
                                         String requestedRoutingHint,
                                         List<String> requestedReferenceVideos,
                                         List<String> requestedReferenceAudios,
                                         List<AgentAttachmentRequest> requestedAttachments) {
        List<AgentMessage> messages = listMessages(conversation.getId());
        int targetIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).getId())) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0 || !"user".equals(messages.get(targetIndex).getRole())) {
            throw new BusinessException(40001, "只能编辑最后一条用户消息");
        }
        for (int i = targetIndex + 1; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).getRole())) {
                throw new BusinessException(40001, "只能编辑最后一条用户消息");
            }
        }

        AgentMessage target = messages.get(targetIndex);
        Map<String, Object> previousInput = messageInput(target);
        List<String> picUrls = requestedPicUrls == null || requestedPicUrls.isEmpty()
                ? stringList(previousInput.get("picUrls")) : requestedPicUrls;
        List<String> assetIds = requestedAssetIds == null || requestedAssetIds.isEmpty()
                ? stringList(previousInput.get("assetIds")) : requestedAssetIds;
        List<String> referenceVideos =
                requestedReferenceVideos == null || requestedReferenceVideos.isEmpty()
                        ? stringList(previousInput.get("referenceVideos"))
                        : requestedReferenceVideos;
        List<String> referenceAudios =
                requestedReferenceAudios == null || requestedReferenceAudios.isEmpty()
                        ? stringList(previousInput.get("referenceAudios"))
                        : requestedReferenceAudios;
        List<AgentAttachmentRequest> attachments =
                requestedAttachments == null || requestedAttachments.isEmpty()
                        ? attachmentList(previousInput.get("attachments"))
                        : requestedAttachments;
        String routingHint = requestedRoutingHint == null || requestedRoutingHint.isBlank()
                ? (previousInput.get("routingHint") == null
                        ? null : String.valueOf(previousInput.get("routingHint")))
                : requestedRoutingHint;

        bindUploadedAttachments(conversation, attachments);
        checkpointResumeService.expirePending(conversation.getId());
        if (agentScopeMediaRuntime != null) {
            agentScopeMediaRuntime.rewindLastTurn(
                    userId, conversation.getId(), target.getContent());
        }
        List<String> laterMessageIds = messages.subList(targetIndex + 1, messages.size()).stream()
                .map(AgentMessage::getId)
                .toList();
        transactionTemplate().executeWithoutResult(tx -> {
            target.setContent(content);
            target.setMetadata(messageInputMetadata(
                    picUrls, assetIds, routingHint, referenceVideos, referenceAudios, attachments));
            messageMapper.updateById(target);
            if (!laterMessageIds.isEmpty()) {
                messageMapper.deleteBatchIds(laterMessageIds);
            }
            conversationMapper.updateById(conversation);
        });
        return new EditedTurn(content, picUrls, assetIds, referenceVideos, referenceAudios,
                routingHint);
    }

    private record EditedTurn(String content, List<String> picUrls, List<String> assetIds,
                              List<String> referenceVideos, List<String> referenceAudios,
                              String routingHint) {
    }

    private Map<String, Object> messageInputMetadata(List<String> picUrls, List<String> assetIds,
                                                     String routingHint, List<String> referenceVideos,
                                                     List<String> referenceAudios,
                                                     List<AgentAttachmentRequest> attachments) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (picUrls != null && !picUrls.isEmpty()) input.put("picUrls", picUrls);
        if (assetIds != null && !assetIds.isEmpty()) input.put("assetIds", assetIds);
        if (referenceVideos != null && !referenceVideos.isEmpty()) input.put("referenceVideos", referenceVideos);
        if (referenceAudios != null && !referenceAudios.isEmpty()) input.put("referenceAudios", referenceAudios);
        if (attachments != null && !attachments.isEmpty()) input.put("attachments", attachments);
        if (routingHint != null && !routingHint.isBlank()) input.put("routingHint", routingHint);
        return Map.of("messageInput", input);
    }

    private void persistUserMessage(AgentConversation conversation, String content,
                                    List<String> picUrls, List<String> assetIds,
                                    String routingHint, List<String> referenceVideos,
                                    List<String> referenceAudios,
                                    List<AgentAttachmentRequest> attachments) {
        AgentMessage userMessage = new AgentMessage();
        userMessage.setConversationId(conversation.getId());
        userMessage.setRole("user");
        userMessage.setContent(content);
        userMessage.setMetadata(messageInputMetadata(picUrls, assetIds, routingHint,
                referenceVideos, referenceAudios, attachments));
        bindUploadedAttachments(conversation, attachments);
        maybeScheduleTitleRename(conversation, content);
        transactionTemplate().executeWithoutResult(tx -> messageMapper.insert(userMessage));
    }

    private void bindUploadedAttachments(AgentConversation conversation,
                                         List<AgentAttachmentRequest> attachments) {
        if (conversation == null || attachments == null || attachments.isEmpty()) return;
        List<String> assetIds = attachments.stream()
                .filter(java.util.Objects::nonNull)
                .map(AgentAttachmentRequest::assetId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (assetIds.isEmpty()) return;
        int bound = assetMapper.bindConversation(
                assetIds, conversation.getId(), conversation.getUserId(),
                conversation.getProjectId());
        if (bound > 0) {
            log.info("已绑定上传附件到会话: conversationId={}, assetIds={}, count={}",
                    conversation.getId(), assetIds, bound);
        }
    }

    private Map<String, Object> messageInput(AgentMessage message) {
        if (message.getMetadata() == null) return Map.of();
        Object raw = message.getMetadata().get("messageInput");
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private List<AgentAttachmentRequest> attachmentList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> {
                    Map<?, ?> map = (Map<?, ?>) item;
                    Object size = map.get("sizeBytes");
                    Long sizeBytes = size instanceof Number number ? number.longValue() : null;
                    return new AgentAttachmentRequest(
                            attachmentString(map, "url", ""),
                            attachmentString(map, "name", "未命名文件"),
                            attachmentString(map, "mimeType", ""),
                            sizeBytes,
                            attachmentString(map, "kind", "file"),
                            attachmentString(map, "source", "upload"),
                            attachmentString(map, "assetId", null),
                            attachmentString(map, "mediaId", null));
                })
                .filter(item -> item.url() != null && !item.url().isBlank())
                .toList();
    }

    private String attachmentString(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || String.valueOf(value).isBlank()
                ? fallback : String.valueOf(value);
    }


    /** Recent product messages used only to reconstruct a missing AgentScope session. */
    private List<AgentMessage> loadRecentHistory(String conversationId) {
        List<AgentMessage> list = messageMapper.selectList(
                new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getConversationId, conversationId)
                        .orderByDesc(AgentMessage::getCreatedAt)
                        .last("LIMIT 20"));
        return list.reversed(); // Java 21 List.reversed()：倒序视图（最新在最后），零拷贝
    }

    private void bootstrapAgentScopeSession(String userId, AgentConversation conversation) {
        if (agentScopeMediaRuntime == null || !agentScopeMediaRuntime.enabled()) return;
        if (conversation.getAgentscopeSessionInitializedAt() != null) return;
        List<Msg> history = loadRecentHistory(conversation.getId()).stream()
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .map(message -> "assistant".equals(message.getRole())
                        ? (Msg) new AssistantMessage(message.getContent())
                        : (Msg) new UserMessage(message.getContent()))
                .toList();
        MoonAgentRuntime.SessionBootstrapResult result =
                agentScopeMediaRuntime.ensureSessionFromHistory(
                        userId, conversation.getId(), history);
        if (result == MoonAgentRuntime.SessionBootstrapResult.SEEDED
                || result == MoonAgentRuntime.SessionBootstrapResult.ALREADY_INITIALIZED) {
            OffsetDateTime initializedAt = OffsetDateTime.now();
            conversation.setAgentscopeSessionInitializedAt(initializedAt);
            conversationMapper.update(null, new UpdateWrapper<AgentConversation>()
                    .eq("id", conversation.getId())
                    .isNull("agentscope_session_initialized_at")
                    .set("agentscope_session_initialized_at", initializedAt));
        }
    }

    /** SseEmitter 事件发送（捕获 IOException 忽略——前端已断开） */
    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport.sseJson(data)));
        } catch (Exception e) {
            log.debug("SseEmitter 发送失败（前端可能已断开）: event={}", eventName);
        }
    }

    /**
     * Terminal barrier: cancellation can arrive after the orchestration returns but before its
     * terminal event and assistant message are committed. Re-check here so a stopped turn cannot
     * reappear in conversation history.
     */
    private void throwIfInterrupted(String conversationId) {
        if (orchestratorSupport.isInterrupted(conversationId)) {
            throw new AgentInterruptException();
        }
    }

    /**
     * 构造 message_end 负载并附带一次性标题：首条消息异步重命名落库成功的新标题，
     * 随本轮 message_end 推送一次（remove 取走即删，绝不重复推送，不做轮询/持续推送）。
     * 标题尚未生成完成时 map 无值，该轮不推送，前端下次拉取会话列表自然可见。
     */
    private Map<String, Object> messageEndPayload(AgentConversation conversation, String messageId,
                                                  long sceneCount, String localized) {
        Map<String, Object> payload = new HashMap<>(Map.of(
                "messageId", messageId, "sceneCount", sceneCount, "content", localized));
        String renamed = renamedTitleByConversation.remove(conversation.getId());
        if (renamed != null) payload.put("title", renamed);
        return payload;
    }


    /**
     * 落库 assistant 消息（事务内原子完成：回填会话ID + 保存消息 + 刷新会话 updatedAt）。
     * Z1：会话ID 回填必须与 assistant 落库同处一个事务——若分两段，回填事务失败时
     * assistant 已落库而 conversation_id 未更新，下一条消息会错误地开启新的会话。
     *
     * I2：HITL 续流消息合并——若该会话最后一条 assistant 消息是 HITL 暂停时落库的"未完成"消息
     * （消息ID 为 null），则把续流新内容追加到它上面并回填 messageId，避免同一轮
     * HITL 对话在数据库里碎成两条记录；否则（上一条已完成/无消息）正常 insert。
     */
    private void persistAssistant(AgentConversation conversation, String content) {
        if (content == null || content.isBlank()) return;
        transactionTemplate().executeWithoutResult(tx -> {
            AgentMessage assistantMessage = new AgentMessage();
            assistantMessage.setConversationId(conversation.getId());
            assistantMessage.setRole("assistant");
            assistantMessage.setContent(content);
            messageMapper.insert(assistantMessage);
            conversationMapper.updateById(conversation); // 触发 updatedAt fill
        });
    }

    /**
     * 落库 assistant 消息带结构化 metadata（trace/reasoning/suggestedActions/hitl 等）。
     */
    private AgentMessage persistAssistant(AgentConversation conversation, String content, Map<String, Object> metadata) {
        boolean hasMetadata = metadata != null && !metadata.isEmpty();
        if ((content == null || content.isBlank()) && !hasMetadata) return null;
        return transactionTemplate().execute(tx -> {
            AgentMessage assistantMessage = new AgentMessage();
            assistantMessage.setConversationId(conversation.getId());
            assistantMessage.setRole("assistant");
            assistantMessage.setContent(content == null ? "" : content);
            if (metadata != null && !metadata.isEmpty()) assistantMessage.setMetadata(metadata);
            messageMapper.insert(assistantMessage);
            conversationMapper.updateById(conversation); // 触发 updatedAt fill
            return assistantMessage;
        });
    }

    /**
     * 按 AgentTurnResult 落库 assistant 消息：content + metadata（含 trace/reasoning/suggestedActions/hitl）。
     * hitlPaused=true 时由 runHITLStage 已落库，调用方应在调用前跳过（本方法防御性跳过）。
     */
    private AgentMessage persistAssistant(AgentConversation conversation, AgentTurnResult turn) {
        if (turn == null) return null;
        if (turn.hitlPaused()) {
            log.info("persistAssistant 跳过 HITL 暂停消息（runHITLStage 已落库）: conversationId={}", conversation.getId());
            return null;
        }
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (turn.metadata() != null) metadata.putAll(turn.metadata());
        metadata.remove("_terminalEventSent");
        if (turn.agentTrace() != null && !turn.agentTrace().isEmpty()) metadata.put("agentTrace", turn.agentTrace());
        if (turn.reasoning() != null) metadata.put("reasoning", turn.reasoning());
        if (turn.suggestedActions() != null && !turn.suggestedActions().isEmpty()) metadata.put("suggestedActions", turn.suggestedActions());
        AgentMessage saved = persistAssistant(conversation, turn.content(), metadata);
        if (saved != null && metadata.get("taskCards") instanceof List<?> cards) {
            List<Map<String, Object>> taskCards = cards.stream()
                    .filter(Map.class::isInstance)
                    .map(card -> {
                        Map<?, ?> raw = (Map<?, ?>) card;
                        Map<String, Object> normalized = new java.util.LinkedHashMap<>();
                        raw.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                        return normalized;
                    })
                    .toList();
            agentScopeAsyncTaskService.bindAnchorMessages(saved.getId(), taskCards);
        }
        return saved;
    }

    /** HITL 自定义输入落一条 user 消息（内容=自定义原文，不带「确认：」前缀）。 */
    private void persistUserText(AgentConversation conversation, String text) {
        if (text == null || text.isBlank()) return;
        transactionTemplate().executeWithoutResult(tx -> {
            AgentMessage userMessage = new AgentMessage();
            userMessage.setConversationId(conversation.getId());
            userMessage.setRole("user");
            userMessage.setContent(text);
            messageMapper.insert(userMessage);
        });
        log.info("HITL 自定义输入已落库: conversationId={}, text={}", conversation.getId(), text);
    }


    @Override
    public void streamAgentScopeTurn(String userId, String conversationId, String content,
                                     List<String> picUrls, List<String> assetIds,
                                     String routingHint, String editMessageId,
                                     CanvasSessionContext canvasContext,
                                     List<String> referenceVideos, List<String> referenceAudios,
                                     SseEmitter emitter) {
        streamAgentScopeTurn(userId, conversationId, content, picUrls, assetIds,
                routingHint, editMessageId, canvasContext, referenceVideos, referenceAudios,
                null, List.of(), emitter);
    }

    @Override
    public void streamAgentScopeTurn(String userId, String conversationId, String content,
                                     List<String> picUrls, List<String> assetIds,
                                     String routingHint, String editMessageId,
                                     CanvasSessionContext canvasContext,
                                     List<String> referenceVideos, List<String> referenceAudios,
                                     List<AgentAttachmentRequest> attachments,
                                     SseEmitter emitter) {
        streamAgentScopeTurnInternal(userId, conversationId, content, picUrls, assetIds,
                routingHint, editMessageId, canvasContext, referenceVideos, referenceAudios,
                null, attachments, null, true, emitter);
    }

    @Override
    public void streamAgentScopeTurn(String userId, String conversationId, String content,
                                     List<String> picUrls, List<String> assetIds,
                                     String routingHint, String editMessageId,
                                     CanvasSessionContext canvasContext,
                                     List<String> referenceVideos, List<String> referenceAudios,
                                     String clientRequestId,
                                     SseEmitter emitter) {
        streamAgentScopeTurn(userId, conversationId, content, picUrls, assetIds,
                routingHint, editMessageId, canvasContext, referenceVideos, referenceAudios,
                clientRequestId, List.of(), emitter);
    }

    @Override
    public void streamAgentScopeTurn(String userId, String conversationId, String content,
                                     List<String> picUrls, List<String> assetIds,
                                     String routingHint, String editMessageId,
                                     CanvasSessionContext canvasContext,
                                     List<String> referenceVideos, List<String> referenceAudios,
                                     String clientRequestId,
                                     List<AgentAttachmentRequest> attachments,
                                     SseEmitter emitter) {
        streamAgentScopeTurn(userId, conversationId, content, picUrls, assetIds,
                routingHint, editMessageId, canvasContext, referenceVideos, referenceAudios,
                clientRequestId, attachments, null, emitter);
    }

    @Override
    public void streamAgentScopeTurn(String userId, String conversationId, String content,
                                     List<String> picUrls, List<String> assetIds,
                                     String routingHint, String editMessageId,
                                     CanvasSessionContext canvasContext,
                                     List<String> referenceVideos, List<String> referenceAudios,
                                     String clientRequestId,
                                     List<AgentAttachmentRequest> attachments,
                                     AgentGenerationPreferences generationPreferences,
                                     SseEmitter emitter) {
        streamAgentScopeTurnInternal(userId, conversationId, content, picUrls, assetIds,
                routingHint, editMessageId, canvasContext, referenceVideos, referenceAudios,
                clientRequestId, attachments, generationPreferences, true, emitter);
    }

    private void streamAgentScopeTurnInternal(String userId, String conversationId, String content,
                                              List<String> picUrls, List<String> assetIds,
                                              String routingHint, String editMessageId,
                                              CanvasSessionContext canvasContext,
                                              List<String> referenceVideos, List<String> referenceAudios,
                                              String clientRequestId,
                                              List<AgentAttachmentRequest> attachments,
                                              AgentGenerationPreferences generationPreferences,
                                              boolean persistUser, SseEmitter emitter) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        requireWritable(conversation);
        if (!requireAgentScope(emitter)) {
            emitter.complete();
            return;
        }
        ConversationLock.LockLease lease = conversationLock.acquire(conversationId);
        if (lease == null) {
            sendRuntimeError(emitter, "40901", "当前对话正在处理中，请稍候");
            emitter.complete();
            return;
        }
        orchestratorSupport.clearInterrupt(conversationId);
        configureEmitter(emitter);

        CompletableFuture.runAsync(() -> {
            com.moon.moonagent.entity.AgentTurnEntity turn = null;
            try {
                bootstrapAgentScopeSession(userId, conversation);
                String idempotencyKey = clientRequestId == null || clientRequestId.isBlank()
                        ? java.util.UUID.randomUUID().toString()
                        : clientRequestId.trim();
                com.moon.moonagent.entity.AgentTurnEntity existing =
                        turnEventStore.findByClientRequestId(conversationId, idempotencyKey);
                if (existing != null) {
                    replayTurnEvents(existing.getId(), emitter);
                    return;
                }
                turn = turnEventStore.start(conversation, content, idempotencyKey);
                String effectiveContent = content;
                List<String> effectivePicUrls = picUrls == null ? List.of() : picUrls;
                List<String> effectiveAssetIds = assetIds == null ? List.of() : assetIds;
                List<String> effectiveReferenceVideos =
                        referenceVideos == null ? List.of() : referenceVideos;
                List<String> effectiveReferenceAudios =
                        referenceAudios == null ? List.of() : referenceAudios;
                List<AgentAttachmentRequest> effectiveAttachments =
                        attachments == null ? List.of() : attachments;
                String effectiveRoutingHint = routingHint;
                if (editMessageId != null && !editMessageId.isBlank()) {
                    EditedTurn edited = prepareEditedTurn(
                            userId, conversation, editMessageId, content, effectivePicUrls, assetIds,
                            routingHint, effectiveReferenceVideos, effectiveReferenceAudios,
                            effectiveAttachments);
                    effectiveContent = edited.content();
                    effectivePicUrls = edited.picUrls();
                    effectiveAssetIds = edited.assetIds();
                    effectiveReferenceVideos = edited.referenceVideos();
                    effectiveReferenceAudios = edited.referenceAudios();
                    effectiveRoutingHint = edited.routingHint();
                } else if (persistUser) {
                    persistUserMessage(conversation, content, effectivePicUrls, effectiveAssetIds,
                            effectiveRoutingHint, effectiveReferenceVideos, effectiveReferenceAudios,
                            effectiveAttachments);
                }
                Map<String, Object> started = new LinkedHashMap<>();
                started.put("turnId", turn.getId());
                started.put("conversationId", conversationId);
                started.put("runtime", turn.getRuntimeEngine() == null
                        ? "agentscope-java-2.0.4" : turn.getRuntimeEngine());
                // 随 turn_started 提前下发模型上下文窗口，前端首轮即可展示正确分母（无需等 usage 事件）
                started.put("contextWindowTokens", resolveAgentContextWindow());
                sendRuntimeEnvelope(emitter,
                        turnEventStore.append(turn.getId(), "turn_started", started));

                OrchestrationRequest request = new OrchestrationRequest(
                        conversation, effectiveContent, effectivePicUrls, emitter);
                request.setTurnId(turn.getId());
                request.setAssetIds(effectiveAssetIds);
                request.setRoutingHint(effectiveRoutingHint);
                request.setCanvasContext(canvasContext);
                request.setNativeEventsOnly(true);
                request.setReferenceVideos(effectiveReferenceVideos);
                request.setReferenceAudios(effectiveReferenceAudios);
                request.setAttachments(effectiveAttachments);
                request.setGenerationPreferences(generationPreferences);
                ConstraintLedger ledger = conversation.getProjectId() == null
                        || conversation.getProjectId().isBlank()
                        ? null
                        : creativeContext.prepareTurn(
                                userId, conversation.getProjectId(), effectiveContent);
                if (ledger == null) {
                    ledger = new ConstraintLedger(
                            com.moon.moonagent.ai.agent.creative.CreativeMode.BALANCED,
                            List.of(), List.of(), List.of());
                }
                final ConstraintLedger turnLedger = ledger;
                if (ledger.strict() || !ledger.unresolved().isEmpty()) {
                    sendRuntimeEnvelope(emitter, turnEventStore.append(
                            turn.getId(), "constraint_flagged", Map.of(
                                    "mode", turnLedger.mode().name(),
                                    "hardConstraints", turnLedger.hard(),
                                    "softConstraints", turnLedger.soft(),
                                    "unresolved", turnLedger.unresolved())));
                }
                AgentTraceAccumulator trace = new AgentTraceAccumulator(
                        request, resolveAgentContextWindow());
                request.setTrace(trace);

                AgentTurnResult result;
                try (AgentTurnEventPublisher eventPublisher = eventPublisher(
                        turn.getId(), emitter, trace.contextWindowTokens())) {
                    AgentTurnEventPublisher orderedPublisher = eventPublisher;
                    String orderedTurnId = turn.getId();
                    result = withTraceStreaming(trace, orderedPublisher, () ->
                            agentScopeMediaRuntime.runTurn(
                                    conversation, request, trace,
                                    eventObserver(trace, orderedPublisher, orderedTurnId, emitter,
                                            trace.contextWindowTokens())));
                }
                throwIfInterrupted(conversationId);
                result = reconcileSandboxOutputs(conversation, turn.getId(), result);
                finishAgentScopeTurn(conversation, turn, result, emitter);
            } catch (AgentInterruptException e) {
                if (turn != null) {
                    var canvasRun = canvasRunService.activeByTurn(turn.getId());
                    if (canvasRun != null) {
                        canvasRunService.fail(canvasRun.runId(), "用户停止");
                        sendRuntimeEnvelope(emitter, turnEventStore.append(
                                turn.getId(), "canvas_run_failed",
                                Map.of("canvasRunId", canvasRun.runId(), "message", "用户停止")));
                    }
                    turnEventStore.finish(turn.getId(), "cancelled", "用户停止", OffsetDateTime.now());
                    sendRuntimeEnvelope(emitter, turnEventStore.append(turn.getId(), "turn_failed",
                            Map.of("code", "40904", "message", "生成已停止")));
                }
                log.info("AgentScope turn 被用户打断: conversationId={}", conversationId);
            } catch (Exception e) {
                if (turn != null) {
                    var canvasRun = canvasRunService.activeByTurn(turn.getId());
                    if (canvasRun != null) {
                        canvasRunService.fail(canvasRun.runId(), friendlyRuntimeError(e));
                        sendRuntimeEnvelope(emitter, turnEventStore.append(
                                turn.getId(), "canvas_run_failed",
                                Map.of("canvasRunId", canvasRun.runId(),
                                        "message", friendlyRuntimeError(e))));
                    }
                    turnEventStore.finish(turn.getId(), "failed", e.getMessage(), OffsetDateTime.now());
                    sendRuntimeEnvelope(emitter, turnEventStore.append(turn.getId(), "turn_failed",
                            Map.of("code", "50001", "message", friendlyRuntimeError(e))));
                } else {
                    sendRuntimeError(emitter, "50001", friendlyRuntimeError(e));
                }
                log.error("AgentScope turn 失败: conversationId={}, error={}",
                        conversationId, e.getMessage(), e);
            } finally {
                lease.close();
                try { emitter.complete(); } catch (Exception ignore) { }
            }
        }, agentExecutor);
    }

    @Override
    public void resumeAgentScopeTurn(String userId, String conversationId, String turnId,
                                     String formToken,
                                     String action, String customText, Map<String, String> params,
                                     List<String> assetIds, CanvasSessionContext canvasContext,
                                     SseEmitter emitter) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        requireWritable(conversation);
        if (!requireAgentScope(emitter)) {
            emitter.complete();
            return;
        }
        com.moon.moonagent.entity.AgentTurnEntity priorTurn = turnEventStore.find(turnId);
        if (priorTurn == null || !conversationId.equals(priorTurn.getConversationId())
                || !"waiting_user".equals(priorTurn.getStatus())) {
            sendRuntimeError(emitter, "40902", "待确认轮次不存在、已提交或已过期");
            emitter.complete();
            return;
        }
        ConversationLock.LockLease lease = conversationLock.acquire(conversationId);
        if (lease == null) {
            sendRuntimeError(emitter, "40901", "当前对话正在处理中，请稍候");
            emitter.complete();
            return;
        }
        orchestratorSupport.clearInterrupt(conversationId);
        configureEmitter(emitter);

        CompletableFuture.runAsync(() -> {
            com.moon.moonagent.entity.AgentTurnEntity turn = null;
            try {
                ResumeCommand command = ResumeCommand.of(
                        action, customText, params, assetIds, "", canvasContext);
                CheckpointClaim claim = checkpointResumeService.claim(
                        conversationId, formToken, command);
                if ("custom".equals(action) && customText != null && !customText.isBlank()) {
                    persistUserText(conversation, customText);
                }
                orchestratorSupport.markHitlCardSubmitted(
                        conversationId, formToken, action, customText, params, assetIds);
                turn = turnEventStore.resume(turnId);
                if (turn == null) {
                    throw new BusinessException(40902, "待确认轮次不存在或已过期");
                }
                Map<String, Object> started = new LinkedHashMap<>();
                started.put("turnId", turn.getId());
                started.put("conversationId", conversationId);
                started.put("resume", true);
                started.put("runtime", turn.getRuntimeEngine());
                sendRuntimeEnvelope(emitter,
                        turnEventStore.append(turn.getId(), "turn_started", started));

                if ("reject".equalsIgnoreCase(action)) {
                    checkpointResumeService.complete(claim);
                    finishAgentScopeTurn(conversation, turn,
                            AgentTurnResult.done("已取消，本次操作未执行。"), emitter);
                    return;
                }

                String turnInput = priorTurn.getInputText() == null
                        ? "" : priorTurn.getInputText();
                OrchestrationRequest request = new OrchestrationRequest(
                        conversation, turnInput, List.of(), emitter);
                request.setTurnId(turn.getId());
                request.setAction(action == null ? "" : action);
                request.setCustomText(customText == null ? "" : customText);
                request.setParams(params == null ? Map.of() : params);
                request.setAssetIds(assetIds == null ? List.of() : assetIds);
                request.setCanvasContext(canvasContext);
                request.setNativeEventsOnly(true);
                request.setCheckpointAction(claim.checkpoint().getAction());
                request.setCheckpointPlan(claim.checkpoint().getPlan());
                AgentTraceAccumulator trace = new AgentTraceAccumulator(
                        request, resolveAgentContextWindow());
                request.setTrace(trace);

                AgentTurnResult result;
                try (AgentTurnEventPublisher eventPublisher = eventPublisher(
                        turn.getId(), emitter, trace.contextWindowTokens())) {
                    AgentTurnEventPublisher orderedPublisher = eventPublisher;
                    String orderedTurnId = turn.getId();
                    result = withTraceStreaming(trace, orderedPublisher, () ->
                            agentScopeMediaRuntime.resumeTurn(
                                    conversation, claim.checkpoint(), request, trace,
                                    action, customText,
                                    eventObserver(trace, orderedPublisher, orderedTurnId, emitter,
                                            trace.contextWindowTokens())));
                }
                throwIfInterrupted(conversationId);
                result = reconcileSandboxOutputs(conversation, turn.getId(), result);
                checkpointResumeService.complete(claim);
                finishAgentScopeTurn(conversation, turn, result, emitter);
            } catch (AgentInterruptException e) {
                if (turn != null) {
                    turnEventStore.finish(turn.getId(), "cancelled", "用户停止", OffsetDateTime.now());
                    sendRuntimeEnvelope(emitter, turnEventStore.append(turn.getId(), "turn_failed",
                            Map.of("code", "40904", "message", "生成已停止")));
                }
            } catch (Exception e) {
                if (turn != null) {
                    turnEventStore.finish(turn.getId(), "failed", e.getMessage(), OffsetDateTime.now());
                    sendRuntimeEnvelope(emitter, turnEventStore.append(turn.getId(), "turn_failed",
                            Map.of("code", "50001", "message", friendlyRuntimeError(e))));
                } else {
                    sendRuntimeError(emitter, "50001", friendlyRuntimeError(e));
                }
                log.error("AgentScope resume 失败: conversationId={}, error={}",
                        conversationId, e.getMessage(), e);
            } finally {
                lease.close();
                try { emitter.complete(); } catch (Exception ignore) { }
            }
        }, agentExecutor);
    }

    @Override
    public void resumeCanvasSession(String userId, String conversationId, String sessionId,
                                    String selectedCanvasId, boolean createNew,
                                    SseEmitter emitter) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        requireWritable(conversation);
        if (!requireAgentScope(emitter)) {
            emitter.complete();
            return;
        }
        com.moon.moonagent.ai.agent.agentscope.AgentCanvasSessionService.CanvasSessionView session;
        try {
            session = canvasSessionService.ready(
                    userId, conversationId, sessionId, selectedCanvasId, createNew);
        } catch (Exception firstFailure) {
            sendRuntimeError(emitter, "40902", friendlyRuntimeError(firstFailure));
            emitter.complete();
            return;
        }
        String turnId = session.turnId();
        com.moon.moonagent.entity.AgentTurnEntity priorTurn = turnEventStore.find(turnId);
        if (priorTurn == null || !conversationId.equals(priorTurn.getConversationId())
                || !"waiting_user".equals(priorTurn.getStatus())) {
            sendRuntimeError(emitter, "40902", "画布轮次不存在、已提交或已过期");
            emitter.complete();
            return;
        }
        ConversationLock.LockLease lease = conversationLock.acquire(conversationId);
        if (lease == null) {
            sendRuntimeError(emitter, "40901", "当前对话正在处理中，请稍候");
            emitter.complete();
            return;
        }
        orchestratorSupport.clearInterrupt(conversationId);
        configureEmitter(emitter);
        CompletableFuture.runAsync(() -> {
            com.moon.moonagent.entity.AgentTurnEntity turn = null;
            try {
                turn = turnEventStore.resume(turnId);
                if (turn == null) throw new BusinessException(40902, "画布轮次不存在或已过期");
                Map<String, Object> started = new LinkedHashMap<>();
                started.put("turnId", turn.getId());
                started.put("conversationId", conversationId);
                started.put("resume", true);
                started.put("canvasSessionId", session.sessionId());
                started.put("runtime", turn.getRuntimeEngine());
                sendRuntimeEnvelope(emitter,
                        turnEventStore.append(turn.getId(), "turn_started", started));

                OrchestrationRequest request = new OrchestrationRequest(
                        conversation,
                        priorTurn.getInputText() == null ? "" : priorTurn.getInputText(),
                        List.of(),
                        emitter);
                request.setTurnId(turn.getId());
                request.setCanvasContext(new CanvasSessionContext(
                        CanvasSessionContext.EDIT_MODE,
                        session.projectId(),
                        session.canvasId(),
                        List.of()));
                request.setNativeEventsOnly(true);
                AgentTraceAccumulator trace = new AgentTraceAccumulator(
                        request, agentContextWindowResolver == null
                        ? AgentContextWindowResolver.DEFAULT_WINDOW_TOKENS
                        : agentContextWindowResolver.resolve(agentScopeMediaRuntime.primaryModelName()));
                request.setTrace(trace);

                AgentTurnResult result;
                try (AgentTurnEventPublisher eventPublisher = eventPublisher(
                        turn.getId(), emitter, trace.contextWindowTokens())) {
                    AgentTurnEventPublisher orderedPublisher = eventPublisher;
                    String orderedTurnId = turn.getId();
                    result = withTraceStreaming(trace, orderedPublisher, () ->
                            agentScopeMediaRuntime.resumeCanvasTurn(
                                    conversation, session, request, trace,
                                    eventObserver(trace, orderedPublisher, orderedTurnId, emitter,
                                            trace.contextWindowTokens())));
                }
                throwIfInterrupted(conversationId);
                result = reconcileSandboxOutputs(conversation, turn.getId(), result);
                canvasSessionService.markResumed(session.sessionId());
                finishAgentScopeTurn(conversation, turn, result, emitter);
            } catch (AgentInterruptException e) {
                canvasSessionService.markFailed(session.sessionId(), "用户停止");
                if (turn != null) {
                    turnEventStore.finish(turn.getId(), "cancelled", "用户停止", OffsetDateTime.now());
                    sendRuntimeEnvelope(emitter, turnEventStore.append(
                            turn.getId(), "turn_failed",
                            Map.of("code", "40904", "message", "生成已停止")));
                }
            } catch (Exception e) {
                canvasSessionService.markRetryable(session.sessionId(), e.getMessage());
                if (turn != null) {
                    turnEventStore.markWaitingUser(turn.getId());
                    sendRuntimeEnvelope(emitter, turnEventStore.append(
                            turn.getId(), "turn_failed",
                            Map.of("code", "50001", "message", friendlyRuntimeError(e))));
                } else {
                    sendRuntimeError(emitter, "50001", friendlyRuntimeError(e));
                }
                log.error("Canvas session resume 失败: conversationId={}, sessionId={}",
                        conversationId, sessionId, e);
            } finally {
                lease.close();
                try {
                    emitter.complete();
                } catch (Exception ignore) {
                }
            }
        }, agentExecutor);
    }

    @Override
    public Map<String, Object> getRuntimeStatus(String userId, String conversationId) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("conversationId", conversationId);
        status.put("engineVersion", conversation.getEngineVersion());
        status.put("agentscopeEnabled", agentScopeMediaRuntime != null
                && agentScopeMediaRuntime.enabled());
        status.put("runtime", agentScopeMediaRuntime == null
                ? "classpath" : agentScopeMediaRuntime.assetStatus());
        status.put("approvalMode", AgentApprovalMode.from(conversation.getApprovalMode()).name());
        status.put("turnProtocol", "agentscope-envelope-v1");
        if (agentScopeMediaRuntime != null && agentScopeMediaRuntime.enabled()) {
            status.put("assets", agentScopeMediaRuntime.assetStatus());
        }
        boolean locked = conversationLock.isLocked(conversationId);
        status.put("locked", locked);
        if (turnEventStore != null) {
            com.moon.moonagent.entity.AgentTurnEntity activeTurn =
                    turnEventStore.findActive(conversationId);
            if (activeTurn != null) {
                Map<String, Object> turn = new LinkedHashMap<>();
                turn.put("turnId", activeTurn.getId());
                turn.put("status", activeTurn.getStatus());
                turn.put("lastSequence", activeTurn.getLastSequence());
                turn.put("inputText", activeTurn.getInputText());
                turn.put("startedAt", activeTurn.getStartedAt());
                status.put("activeTurn", turn);
            }
        }
        return status;
    }

    @Override
    public Map<String, Object> compactContext(String userId, String conversationId) {
        getOwnedConversation(userId, conversationId); // 归属校验：不存在/无权统一 40401
        if (agentScopeMediaRuntime == null || !agentScopeMediaRuntime.enabled()) {
            throw new BusinessException(40001, "当前会话暂不支持上下文压缩");
        }
        if (!conversationLock.tryAcquire(conversationId)) {
            throw new BusinessException(40901, "当前对话正在处理中，暂时不能压缩上下文");
        }
        try {
            CompactOutcome outcome = agentScopeMediaRuntime.compactSession(userId, conversationId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("compacted", outcome.compacted());
            result.put("status", outcome.status());
            result.put("messagesBefore", outcome.messagesBefore());
            result.put("messagesAfter", outcome.messagesAfter());
            result.put("tokensBefore", outcome.tokensBefore());
            result.put("tokensAfter", outcome.tokensAfter());
            return result;
        } finally {
            conversationLock.release(conversationId);
        }
    }

    @Override
    public AgentConversation createSideChat(String userId, String sourceConversationId) {
        AgentConversation source = getOwnedConversation(userId, sourceConversationId);
        requireWritable(source);
        if ("temporary".equalsIgnoreCase(source.getStatus())) {
            throw new BusinessException(40001, "临时侧边不能再次创建侧边");
        }
        if (agentScopeMediaRuntime == null || !agentScopeMediaRuntime.enabled()) {
            throw new BusinessException(40001, "当前会话暂不支持侧边");
        }
        long messageCount = messageMapper.selectCount(
                new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getConversationId, sourceConversationId));
        if (messageCount <= 0) {
            throw new BusinessException(40001, "先完成一轮对话后再开启侧边");
        }

        bootstrapAgentScopeSession(userId, source);
        List<Msg> fallbackHistory = loadRecentHistory(sourceConversationId).stream()
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .map(message -> "assistant".equals(message.getRole())
                        ? (Msg) new AssistantMessage(message.getContent())
                        : (Msg) new UserMessage(message.getContent()))
                .toList();

        AgentConversation side = new AgentConversation();
        side.setUserId(userId);
        side.setProjectId(source.getProjectId());
        side.setTitle("侧边 · " + (source.getTitle() == null ? "临时对话" : source.getTitle()));
        side.setStatus("temporary");
        side.setOrchestrationMode(OrchestrationMode.AGENTSCOPE.name());
        side.setEngineVersion(source.getEngineVersion());
        side.setDebugComparisonEnabled(false);
        side.setApprovalMode(AgentApprovalMode.from(source.getApprovalMode()).name());
        side.setAgentscopeSessionInitializedAt(OffsetDateTime.now());
        conversationMapper.insert(side);
        try {
            agentScopeMediaRuntime.cloneSessionState(
                    userId, sourceConversationId, side.getId(), fallbackHistory);
        } catch (Exception exception) {
            conversationMapper.deleteById(side.getId());
            throw new BusinessException(50001, "侧边临时会话创建失败");
        }
        return side;
    }

    @Override
    public void closeSideChat(String userId, String sideChatId) {
        AgentConversation side = getOwnedConversation(userId, sideChatId);
        if (!"temporary".equalsIgnoreCase(side.getStatus())) {
            throw new BusinessException(40001, "仅临时侧边可以关闭");
        }
        if (conversationLock.isLocked(sideChatId)) {
            orchestratorSupport.interrupt(sideChatId);
        } else {
            conversationLock.releaseAndRemove(sideChatId);
        }
        if (agentScopeMediaRuntime != null) {
            agentScopeMediaRuntime.clearSession(userId, sideChatId);
        }
        cleanupSandbox(sideChatId);
        orchestratorSupport.cleanupOnDelete(sideChatId);
        conversationMapper.deleteById(sideChatId);
        log.info("Agent 临时侧边已关闭: conversationId={}, userId={}", sideChatId, userId);
    }

    private void cleanupSandbox(String conversationId) {
        if (sandboxClient != null && sandboxClient.enabled()) {
            try {
                sandboxClient.cleanup(conversationId);
            } catch (RuntimeException exception) {
                log.warn("清理 Agent 沙箱失败(忽略): conversationId={}, error={}",
                        conversationId, exception.getMessage());
            }
        }
        if (sandboxSessionRegistry != null) {
            sandboxSessionRegistry.markCleaned(conversationId);
        }
    }

    /** 清理刷新/崩溃后遗留的临时侧边，不触发已产出文件的删除。 */
    @Scheduled(fixedDelay = 15 * 60 * 1000L)
    public void cleanupExpiredSideChats() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(2);
        List<AgentConversation> expired = conversationMapper.selectList(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getStatus, "temporary")
                        .lt(AgentConversation::getUpdatedAt, cutoff)
                        .last("LIMIT 100"));
        for (AgentConversation side : expired) {
            if (conversationLock.isLocked(side.getId())) continue;
            try {
                closeSideChat(side.getUserId(), side.getId());
            } catch (Exception exception) {
                log.warn("清理过期临时侧边失败: conversationId={}, error={}",
                        side.getId(), exception.getMessage());
            }
        }
    }

    @Override
    public List<AgentTurnEventEnvelope> getTurnEvents(String userId, String conversationId,
                                                      String turnId, int afterSequence) {
        getOwnedConversation(userId, conversationId);
        if (turnEventStore == null || turnId == null || turnId.isBlank()) return List.of();
        com.moon.moonagent.entity.AgentTurnEntity turn = turnEventStore.find(turnId);
        if (turn == null || !conversationId.equals(turn.getConversationId())) return List.of();
        return turnEventStore.eventsAfter(turnId, afterSequence);
    }

    private AgentTurnEventPublisher eventPublisher(
            String turnId, SseEmitter emitter, long contextWindowTokens) {
        return new AgentTurnEventPublisher(
                turnEventStore, agentScopeEventMapper, turnId, contextWindowTokens,
                envelope -> sendRuntimeEnvelope(emitter, envelope));
    }

    private <T> T withTraceStreaming(
            AgentTraceAccumulator trace,
            AgentTurnEventPublisher eventPublisher,
            Supplier<T> runtimeCall) {
        trace.setEventSink(traceEvent -> eventPublisher.publishRaw(
                "agent_event", Map.of(
                        "eventType", "AGENT_TRACE",
                        "data", Map.of("traceEvent", traceEvent))));
        try {
            return runtimeCall.get();
        } finally {
            trace.setEventSink(null);
        }
    }

    private java.util.function.Consumer<AgentEvent> eventObserver(
            AgentTraceAccumulator trace,
            AgentTurnEventPublisher eventPublisher, String turnId, SseEmitter emitter,
            long contextWindowTokens) {
        return event -> {
            if (event instanceof ThinkingBlockStartEvent) {
                trace.recordThinkingStart();
            } else if (event instanceof ThinkingBlockDeltaEvent thinking) {
                trace.recordThinkingDelta(thinking.getDelta());
            } else if (event instanceof ThinkingBlockEndEvent) {
                trace.recordThinkingEnd();
            }
            eventPublisher.accept(event);
            if (event instanceof ToolResultEndEvent result) {
                eventPublisher.flush();
                String toolName = result.getToolCallName() == null ? "" : result.getToolCallName();
                if ("canvas_create_run".equals(toolName) || toolName.startsWith("canvas_graph_")) {
                    var run = canvasRunService.activeByTurn(turnId);
                    if (run != null) {
                        String eventType = "canvas_create_run".equals(toolName)
                                ? "canvas_run_started" : "canvas_run_updated";
                        sendRuntimeEnvelope(emitter,
                                turnEventStore.append(turnId, eventType, run.toMap()));
                    }
                }
                String special = switch (result.getToolCallName() == null
                        ? "" : result.getToolCallName()) {
                    case "plan_enter", "plan_write", "plan_exit", "todo_write" -> "plan_updated";
                    case "review_generated_media" -> "media_critique";
                    case "save_timeline_manifest", "merge_storyboard_videos" -> "assembly_progress";
                    case "generate_storyboard_image", "generate_storyboard_images",
                            "generate_storyboard_video", "generate_storyboard_videos" -> "shot_progress";
                    default -> "";
                };
                if (!special.isBlank()) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("eventType", agentScopeEventMapper.type(event));
                    payload.put("data", agentScopeEventMapper.payload(event, contextWindowTokens));
                    sendRuntimeEnvelope(emitter, turnEventStore.append(
                            turnId, special, payload));
                }
            }
        };
    }

    /** 本轮模型上下文窗口优先取自 AgentScope Model，旧运行时测试再回退网关解析。 */
    private int resolveAgentContextWindow() {
        if (agentScopeMediaRuntime != null) {
            int resolved = agentScopeMediaRuntime.contextWindowTokens();
            if (resolved > 0) return resolved;
        }
        if (agentContextWindowResolver == null) {
            return AgentContextWindowResolver.DEFAULT_WINDOW_TOKENS;
        }
        if (agentScopeMediaRuntime == null) {
            return AgentContextWindowResolver.DEFAULT_WINDOW_TOKENS;
        }
        try {
            return agentContextWindowResolver.resolve(agentScopeMediaRuntime.primaryModelName());
        } catch (Exception e) {
            return AgentContextWindowResolver.DEFAULT_WINDOW_TOKENS;
        }
    }

    private void replayTurnEvents(String turnId, SseEmitter emitter) {
        for (AgentTurnEventEnvelope envelope : turnEventStore.eventsAfter(turnId, 0)) {
            sendRuntimeEnvelope(emitter, envelope);
        }
    }

    private void finishAgentScopeTurn(AgentConversation conversation,
                                      com.moon.moonagent.entity.AgentTurnEntity turn,
                                      AgentTurnResult result, SseEmitter emitter) {
        if (result == null) {
            turnEventStore.finish(turn.getId(), "failed", "runtime returned no result", OffsetDateTime.now());
            sendRuntimeEnvelope(emitter, turnEventStore.append(turn.getId(), "turn_failed",
                    Map.of("code", "50001", "message", "AgentScope 运行时没有返回结果")));
            return;
        }
        Object canvasSession = result.metadata() == null
                ? null : result.metadata().get("canvasSession");
        if (canvasSession != null) {
            turnEventStore.markWaitingUser(turn.getId());
            sendRuntimeEnvelope(emitter,
                    turnEventStore.append(turn.getId(), "canvas_session_required", canvasSession));
            return;
        }
        if (result.hitlPaused()) {
            updateHitlMessageContent(result.messageId(), result.content());
            mergeHitlMessageThinking(result.messageId(), result);
            Map<String, Object> paused = new LinkedHashMap<>();
            paused.put("turnId", turn.getId());
            paused.put("messageId", result.messageId() == null ? "" : result.messageId());
            paused.put("content", result.content() == null ? "" : result.content());
            paused.put("metadata", result.metadata() == null ? Map.of() : result.metadata());
            Map<String, Object> pending = getPendingCheckpoint(conversation.getId());
            if (pending != null && !pending.isEmpty()) {
                paused.put("hitl", pending);
                turnEventStore.attachCheckpoint(
                        turn.getId(), String.valueOf(pending.get("formToken")));
            }
            sendRuntimeEnvelope(emitter,
                    turnEventStore.append(turn.getId(), "turn_paused", paused));
            return;
        }
        boolean hasSandboxAttachments = result.metadata() != null
                && result.metadata().get("sandboxAttachments") instanceof List<?> list
                && !list.isEmpty();
        if ((result.content() != null && !result.content().isBlank()) || hasSandboxAttachments) {
            persistAssistant(conversation, result);
        }
        var canvasRun = canvasRunService.activeByTurn(turn.getId());
        if (canvasRun != null
                && com.moon.moonagent.ai.agent.agentscope.AgentCanvasRunService.RUNNING
                .equals(canvasRun.status())) {
            canvasRunService.complete(canvasRun.runId(), Map.of());
            sendRuntimeEnvelope(emitter,
                    turnEventStore.append(turn.getId(), "canvas_run_completed",
                            canvasRunService.activeByTurn(turn.getId()).toMap()));
        }
        turnEventStore.finish(turn.getId(), "completed", "", OffsetDateTime.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("turnId", turn.getId());
        payload.put("status", "completed");
        payload.put("content", result.content() == null ? "" : result.content());
        payload.put("metadata", result.metadata() == null ? Map.of() : result.metadata());
        sendRuntimeEnvelope(emitter,
                turnEventStore.append(turn.getId(), "turn_completed", payload));
    }

    @SuppressWarnings("unchecked")
    private AgentTurnResult reconcileSandboxOutputs(
            AgentConversation conversation, String turnId, AgentTurnResult result) {
        if (sandboxOutputReconciler == null || result == null) return result;
        var published = sandboxOutputReconciler.reconcile(conversation, turnId);
        return published.assetIds().isEmpty()
                ? result
                : withSandboxAttachments(result, published);
    }

    @SuppressWarnings("unchecked")
    private AgentTurnResult withSandboxAttachments(
            AgentTurnResult result,
            com.moon.moonagent.ai.agent.sandbox.SandboxArtifactPublisher.PublishedArtifacts published) {
        List<Map<String, Object>> attachments = published.assetIds().stream()
                .map(assetMapper::selectById)
                .filter(java.util.Objects::nonNull)
                .map(asset -> {
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("assetId", asset.getId());
                    item.put("url", asset.getUrl() == null ? "" : asset.getUrl());
                    item.put("name", asset.getOriginalName() == null
                            ? "生成文件" : asset.getOriginalName());
                    item.put("mimeType", asset.getMimeType() == null
                            ? "application/octet-stream" : asset.getMimeType());
                    item.put("kind", com.moon.moonagent.ai.agent.sandbox
                            .SandboxArtifactPublisher.attachmentKind(asset.getMimeType()));
                    item.put("source", "upload");
                    return item;
                })
                .toList();
        if (attachments.isEmpty()) return result;
        Map<String, Object> metadata = new LinkedHashMap<>(
                result.metadata() == null ? Map.of() : result.metadata());
        Object existing = metadata.get("sandboxAttachments");
        if (existing instanceof List<?> current) {
            List<Map<String, Object>> merged = new java.util.ArrayList<>();
            java.util.Set<String> seenAssetIds = new java.util.HashSet<>();
            current.stream().filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .forEach(item -> {
                        String assetId = item.get("assetId") == null
                                ? null : String.valueOf(item.get("assetId"));
                        if (assetId != null && !seenAssetIds.add(assetId)) return;
                        merged.add(item);
                    });
            for (Map<String, Object> item : attachments) {
                String assetId = item.get("assetId") == null
                        ? null : String.valueOf(item.get("assetId"));
                if (assetId != null && !seenAssetIds.add(assetId)) continue;
                merged.add(item);
            }
            metadata.put("sandboxAttachments", merged);
        } else {
            metadata.put("sandboxAttachments", attachments);
        }
        return new AgentTurnResult(
                result.content(), metadata, result.agentTrace(), result.reasoning(),
                result.suggestedActions(), result.hitlPaused(), result.messageId());
    }

    /** HITL 卡片落库时先写的是卡片标题，暂停时用本轮完整助手正文补齐，刷新后仍能看到方案。 */
    void updateHitlMessageContent(String messageId, String content) {
        if (messageId == null || messageId.isBlank() || content == null || content.isBlank()) return;
        AgentMessage update = new AgentMessage();
        update.setId(messageId);
        update.setContent(content);
        messageMapper.updateById(update);
    }

    /** HITL 暂停时把本轮思考链合并到已落库卡片，确保刷新后仍可恢复。 */
    void mergeHitlMessageThinking(String messageId, AgentTurnResult result) {
        if (messageId == null || messageId.isBlank() || result == null) return;
        Map<String, Object> additions = new LinkedHashMap<>();
        if (result.reasoning() != null) additions.put("reasoning", result.reasoning());
        if (result.agentTrace() != null && !result.agentTrace().isEmpty()) {
            additions.put("agentTrace", result.agentTrace());
        }
        if (result.metadata() != null && result.metadata().get("metrics") != null) {
            additions.put("metrics", result.metadata().get("metrics"));
        }
        if (additions.isEmpty()) return;

        AgentMessage persisted = messageMapper.selectById(messageId);
        if (persisted == null) return;
        Map<String, Object> merged = persisted.getMetadata() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(persisted.getMetadata());
        merged.putAll(additions);
        AgentMessage update = new AgentMessage();
        update.setId(messageId);
        update.setMetadata(merged);
        messageMapper.updateById(update);
    }

    private boolean requireAgentScope(SseEmitter emitter) {
        if (agentScopeMediaRuntime == null || !agentScopeMediaRuntime.enabled()
                || turnEventStore == null || agentScopeEventMapper == null) {
            sendRuntimeError(emitter, "50301", "AgentScope 运行时未就绪");
            return false;
        }
        return true;
    }

    private void configureEmitter(SseEmitter emitter) {
        AtomicBoolean cancel = new AtomicBoolean(false);
        Runnable interruptTrap = () -> cancel.set(true);
        emitter.onCompletion(interruptTrap);
        emitter.onTimeout(interruptTrap);
        emitter.onError(ignored -> interruptTrap.run());
    }

    private void sendRuntimeEnvelope(SseEmitter emitter, AgentTurnEventEnvelope envelope) {
        if (envelope.payloadJson() != null && !envelope.payloadJson().isBlank()) {
            var node = objectMapper.createObjectNode();
            node.put("turnId", envelope.turnId());
            node.put("seq", envelope.seq());
            node.put("type", envelope.type());
            node.putRawValue("payload", new RawValue(envelope.payloadJson()));
            sendEventRaw(emitter, envelope.type(), node.toString());
            return;
        }
        sendEvent(emitter, envelope.type(), Map.of(
                "turnId", envelope.turnId(),
                "seq", envelope.seq(),
                "type", envelope.type(),
                "payload", envelope.payload()));
    }

    private void sendEventRaw(SseEmitter emitter, String eventName, String json) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (Exception e) {
            log.debug("SseEmitter 发送失败（前端可能已断开）: event={}", eventName);
        }
    }

    private void sendRuntimeError(SseEmitter emitter, String code, String message) {
        sendEvent(emitter, "turn_failed", Map.of(
                "turnId", "",
                "seq", 0,
                "type", "turn_failed",
                "payload", Map.of("code", code, "message", message)));
    }

    private String friendlyRuntimeError(Exception error) {
        String raw = error == null ? "" : error.getMessage();
        return orchestratorSupport.friendlyErrorText(raw, "服务暂时出了点问题，请稍后重试。");
    }

    /**
     * 视频异步任务状态查询（前端轮询）：按 taskId 查 agent_assets 行，归属校验防 IDOR。
     * 资产未归属（conversationId 空）或会话无权访问 → 统一 40401。
     */
    public Map<String, Object> getVideoTaskStatus(String userId, String taskId) {
        if (agentScopeAsyncTaskService != null) {
            Map<String, Object> asyncStatus = agentScopeAsyncTaskService.getStatus(userId, taskId);
            if (!asyncStatus.isEmpty()) return asyncStatus;
        }
        AgentAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getTaskId, taskId).last("LIMIT 1"));
        if (asset == null || asset.getConversationId() == null || asset.getConversationId().isBlank()) {
            throw new BusinessException(40401, "任务不存在");
        }
        AgentConversation conv = conversationMapper.selectById(asset.getConversationId());
        if (conv == null || !userId.equals(conv.getUserId())) {
            throw new BusinessException(40401, "任务不存在");
        }
        return Map.of(
                "taskId", taskId,
                "kind", "video",
                "assetId", asset.getId(),
                "assetIds", List.of(asset.getId()),
                "status", asset.getStatus() == null ? "unknown" : asset.getStatus(),
                "url", asset.getUrl() == null ? "" : asset.getUrl(),
                "resultUrls", asset.getUrl() == null ? List.of() : List.of(asset.getUrl()),
                "error", asset.getError() == null ? "" : asset.getError());
    }

    @Override
    public Map<String, Object> getPendingCheckpoint(String conversationId) {
        // 查最新一条 pending + 未过期的 checkpoint
        var cp = checkpointMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.moon.moonagent.entity.AgentCheckpoint>()
                        .eq(com.moon.moonagent.entity.AgentCheckpoint::getConversationId, conversationId)
                        .in(com.moon.moonagent.entity.AgentCheckpoint::getStatus, "pending", "claimed")
                        .gt(com.moon.moonagent.entity.AgentCheckpoint::getExpirationTime, java.time.OffsetDateTime.now())
                        .orderByDesc(com.moon.moonagent.entity.AgentCheckpoint::getCreatedAt)
                        .last("LIMIT 1"));
        if (cp == null) return null;
        if ("claimed".equals(cp.getStatus())
                && checkpointResumeService.hasActiveClaim(cp)) {
            return null;
        }
        try {
            JsonNode plan = objectMapper.readTree(cp.getPlan());
            if (isSubmittedHitlCheckpoint(cp, plan)) return null;
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("formToken", cp.getFormToken());
            result.put("taskId", "");
            result.put("expirationTime", cp.getExpirationTime().toEpochSecond());
            result.put("hitlType", cp.getAction() == null ? "" : cp.getAction());
            com.moon.moonagent.entity.AgentTurnEntity waitingTurn = turnEventStore == null
                    ? null : turnEventStore.findByCheckpoint(cp.getFormToken());
            if (waitingTurn != null && waitingTurn.getId() != null) {
                result.put("turnId", waitingTurn.getId());
            }
            if (CANVAS_BIND_ACTION.equals(cp.getAction())) {
                AgentConversation conversation = conversationMapper.selectById(conversationId);
                result.put("kind", CANVAS_BIND_ACTION);
                result.put("projectId", conversation == null ? "" : conversation.getProjectId());
                result.put("canvasId", orchestratorSupport.planField(plan, "canvasId"));
                result.put("canvasTitle", orchestratorSupport.planField(plan, "canvasTitle"));
                result.put("requestedIntent", orchestratorSupport.planField(plan, "intent"));
                List<String> picUrls = orchestratorSupport.planPicUrls(cp.getPlan());
                if (!picUrls.isEmpty()) result.put("picUrls", picUrls);
                List<String> assetIds = new java.util.ArrayList<>();
                var items = plan.path("items");
                if (items.isArray() && !items.isEmpty() && items.get(0).path("assetIds").isArray()) {
                    for (JsonNode assetId : items.get(0).path("assetIds")) {
                        if (assetId.isTextual() && !assetId.asText().isBlank()) {
                            assetIds.add(assetId.asText());
                        }
                    }
                }
                if (!assetIds.isEmpty()) result.put("assetIds", assetIds);
                List<Map<String, Object>> mediaRefs =
                        orchestratorSupport.planListField(plan, "mediaRefs");
                if (!mediaRefs.isEmpty()) result.put("mediaRefs", mediaRefs);
            }
            if ("generate_video".equals(cp.getAction())) {
                result.put("kind", "video-plan");
                result.put("message", orchestratorSupport.planField(plan, "message"));
                String duration = orchestratorSupport.planField(plan, "duration");
                if (duration != null && !duration.isBlank()) {
                    try { result.put("duration", Integer.parseInt(duration.trim())); }
                    catch (NumberFormatException ignored) { result.put("duration", 8); }
                } else {
                    result.put("duration", 8);
                }
                var picUrls = orchestratorSupport.planPicUrls(cp.getPlan());
                result.put("picUrl", picUrls == null || picUrls.isEmpty() ? "" : picUrls.getFirst());
            }
            // 从 plan 的 _ 前缀字段恢复（新 checkpoint 格式）
            result.put("formContent", plan.has("_formContent") ? plan.get("_formContent").asText() : "");
            if (plan.has("_actions")) {
                result.put("actions", objectMapper.convertValue(plan.get("_actions"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}));
            } else {
                result.put("actions", List.of());
            }
            if (plan.has("_models") && !plan.get("_models").isEmpty()) {
                result.put("models", objectMapper.convertValue(plan.get("_models"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}));
            }
            if (plan.has("_recommended") && !plan.get("_recommended").isEmpty()) {
                result.put("recommended", objectMapper.convertValue(plan.get("_recommended"),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}));
            }
            if (plan.has("_reasons") && !plan.get("_reasons").isEmpty()) {
                result.put("reasons", objectMapper.convertValue(plan.get("_reasons"),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}));
            }
            if (plan.has("_imageModels") && !plan.get("_imageModels").isEmpty()) {
                result.put("imageModels", objectMapper.convertValue(plan.get("_imageModels"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}));
            }
            if (plan.has("_videoModels") && !plan.get("_videoModels").isEmpty()) {
                result.put("videoModels", objectMapper.convertValue(plan.get("_videoModels"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}));
            }
            if (plan.has("_assets") && !plan.get("_assets").isEmpty()) {
                result.put("assets", objectMapper.convertValue(plan.get("_assets"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}));
            }
            return result;
        } catch (Exception e) {
            log.warn("解析 pending checkpoint 失败: conversationId={}, error={}", conversationId, e.getMessage());
            return null;
        }
    }

    private boolean isSubmittedHitlCheckpoint(
            com.moon.moonagent.entity.AgentCheckpoint checkpoint, JsonNode plan) {
        String messageId = plan.path("_hitlMessageId").asText("");
        if (messageId.isBlank()) return false;
        AgentMessage message = messageMapper.selectById(messageId);
        if (message == null || message.getMetadata() == null) return false;
        Object rawHitl = message.getMetadata().get("hitl");
        if (!(rawHitl instanceof Map<?, ?> hitl)) return false;
        return java.util.Objects.equals(checkpoint.getFormToken(), hitl.get("formToken"))
                && Boolean.FALSE.equals(hitl.get("pending"));
    }

    @Override
    public void skipPendingCheckpoints(String userId, String conversationId) {
        getOwnedConversation(userId, conversationId);
        int updated = checkpointResumeService.expirePending(conversationId);
        log.info("Agent 跳过待处理确认: conversationId={}, expiredCount={}", conversationId, updated);
    }

    @Override
    public boolean interrupt(String userId, String conversationId) {
        // 归属校验（40401 同文案，防 IDOR 枚举）
        getOwnedConversation(userId, conversationId);
        if (agentScopeMediaRuntime != null) {
            agentScopeMediaRuntime.interruptTurn(userId, conversationId);
        }
        // Legacy compatibility for any non-AgentScope path still sharing the support registry.
        orchestratorSupport.interrupt(conversationId);
        log.info("Agent 对话打断请求已登记: conversationId={}, userId={}", conversationId, userId);
        return true;
    }
}



