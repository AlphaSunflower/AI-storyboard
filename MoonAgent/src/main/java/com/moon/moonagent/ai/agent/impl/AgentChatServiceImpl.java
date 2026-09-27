package com.moon.moonagent.ai.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.AgentSceneItem;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.storyboard.common.BusinessException;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.AgentInterruptException;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.agentscope.MoonAgentRuntime;
import com.moon.moonagent.ai.agent.agentscope.AgentScopeAsyncTaskService;
import com.moon.moonagent.ai.agent.turn.AgentScopeEventMapper;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventEnvelope;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventStore;
import com.moon.moonagent.ai.agent.recovery.CheckpointClaim;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeException;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeService;
import com.moon.moonagent.ai.agent.recovery.ResumeCommand;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.ConversationTitleService;
import com.moon.moonagent.orchestration.OrchestrationMode;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.ImageRefinePromptService;
import com.moon.moonagent.ai.VideoPlanService;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    private final AiConfigProperties config;
    private final com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport orchestratorSupport;
    private final com.moon.moonagent.mapper.AgentCheckpointMapper checkpointMapper;
    private final CheckpointResumeService checkpointResumeService;
    private final com.moon.moonagent.ai.agent.ConversationLock conversationLock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

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
        return messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getConversationId, conversationId)
            .orderByAsc(AgentMessage::getCreatedAt));
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
    public void deleteAsset(String userId, String assetId) {
        AgentAsset asset = assetMapper.selectById(assetId);
        if (asset == null || asset.getConversationId() == null || asset.getConversationId().isBlank()) {
            throw new BusinessException(40401, "资产不存在或无权访问");
        }
        // 校验资产归属的会话属于本人（防 IDOR）
        getOwnedConversation(userId, asset.getConversationId());
        assetMapper.deleteById(assetId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listAssets(String conversationId, int page, int size) {
        // 分页安全钳制（原 Controller 逻辑下沉：page 下限 1、size 1~50）
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, size));
        // 产出素材 = 生成结果（image/video），排除用户自己上传的参考图（type=reference，不算产出）
        List<AgentAsset> all = assetMapper.selectList(
                new LambdaQueryWrapper<AgentAsset>()
                        .eq(AgentAsset::getConversationId, conversationId)
                        .ne(AgentAsset::getType, "reference")
                        .orderByDesc(AgentAsset::getCreatedAt));
        all = all == null ? List.of() : all;
        int total = all.size();
        int from = (int) Math.min(total, (long) (safePage - 1) * safeSize);
        int to = Math.min(total, from + safeSize);
        List<AgentAsset> records = from >= total ? List.of() : all.subList(from, to);
        int imageTotal = mediaCount(all, "image");
        int videoTotal = mediaCount(all, "video");
        return Map.of(
                "records", records,
                "total", total,
                "imageTotal", imageTotal,
                "videoTotal", videoTotal,
                "mediaTotal", imageTotal + videoTotal,
                "page", safePage,
                "size", safeSize);
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
    public String uploadReferenceImage(String userId, String conversationId, MultipartFile file,
                                       String purpose, String kind) {
        // 校验会话归属（传了 conversationId 时）
        if (conversationId != null && !conversationId.isBlank()) {
            requireWritable(getOwnedConversation(userId, conversationId));
        }
        String mediaType = referenceMediaType(file.getContentType());
        if (mediaType.isBlank()) {
            throw new com.storyboard.common.BusinessException(40001, "仅支持上传图片、视频或音频文件");
        }
        String url = fileStorageService.saveUploadedReference(mediaType, file);

        // 落库 agent_assets（type=reference）
        AgentAsset asset = new AgentAsset();
        asset.setConversationId(conversationId != null && !conversationId.isBlank() ? conversationId : null);
        asset.setType("reference");
        asset.setUrl(url);
        asset.setPurpose(purpose == null || purpose.isBlank() ? null : purpose.trim());
        asset.setKind((kind == null || kind.isBlank()) ? mediaType : kind.trim());
        asset.setStatus("completed");
        assetMapper.insert(asset);
        log.info("Agent 参考图已落库: assetId={}, url={}", asset.getId(), url);

        // 参考图消息落库（conversationId 非空时）：上传的参考图作为 user 消息进入对话记录，
        // 前端对话窗口可见（MessageBubble 渲染层支持裸图片 URL），刷新后记录仍在
        if (conversationId != null && !conversationId.isBlank()) {
            AgentMessage refMsg = new AgentMessage();
            refMsg.setConversationId(conversationId);
            refMsg.setRole("user");
            refMsg.setContent(url);
            messageMapper.insert(refMsg);
        }
        return url;
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
        if (contentType == null) return "";
        if (contentType.startsWith("image/")) return "image";
        if (contentType.startsWith("video/") || contentType.contains("quicktime")) return "video";
        if (contentType.startsWith("audio/")) return "audio";
        return "";
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
                                         List<String> requestedReferenceAudios) {
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
        String routingHint = requestedRoutingHint == null || requestedRoutingHint.isBlank()
                ? (previousInput.get("routingHint") == null
                        ? null : String.valueOf(previousInput.get("routingHint")))
                : requestedRoutingHint;

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
                    picUrls, assetIds, routingHint, referenceVideos, referenceAudios));
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
                                                     List<String> referenceAudios) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (picUrls != null && !picUrls.isEmpty()) input.put("picUrls", picUrls);
        if (assetIds != null && !assetIds.isEmpty()) input.put("assetIds", assetIds);
        if (referenceVideos != null && !referenceVideos.isEmpty()) input.put("referenceVideos", referenceVideos);
        if (referenceAudios != null && !referenceAudios.isEmpty()) input.put("referenceAudios", referenceAudios);
        if (routingHint != null && !routingHint.isBlank()) input.put("routingHint", routingHint);
        return Map.of("messageInput", input);
    }

    private void persistUserMessage(AgentConversation conversation, String content,
                                    List<String> picUrls, List<String> assetIds,
                                    String routingHint, List<String> referenceVideos,
                                    List<String> referenceAudios) {
        AgentMessage userMessage = new AgentMessage();
        userMessage.setConversationId(conversation.getId());
        userMessage.setRole("user");
        userMessage.setContent(content);
        userMessage.setMetadata(messageInputMetadata(picUrls, assetIds, routingHint,
                referenceVideos, referenceAudios));
        maybeScheduleTitleRename(conversation, content);
        transactionTemplate().executeWithoutResult(tx -> messageMapper.insert(userMessage));
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
        List<Msg> history = loadRecentHistory(conversation.getId()).stream()
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .map(message -> "assistant".equals(message.getRole())
                        ? (Msg) new AssistantMessage(message.getContent())
                        : (Msg) new UserMessage(message.getContent()))
                .toList();
        agentScopeMediaRuntime.ensureSessionFromHistory(
                userId, conversation.getId(), history);
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
        if (content == null || content.isBlank()) return null;
        return transactionTemplate().execute(tx -> {
            AgentMessage assistantMessage = new AgentMessage();
            assistantMessage.setConversationId(conversation.getId());
            assistantMessage.setRole("assistant");
            assistantMessage.setContent(content);
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
        streamAgentScopeTurnInternal(userId, conversationId, content, picUrls, assetIds,
                routingHint, editMessageId, canvasContext, referenceVideos, referenceAudios,
                true, emitter);
    }

    private void streamAgentScopeTurnInternal(String userId, String conversationId, String content,
                                              List<String> picUrls, List<String> assetIds,
                                              String routingHint, String editMessageId,
                                              CanvasSessionContext canvasContext,
                                              List<String> referenceVideos, List<String> referenceAudios,
                                              boolean persistUser, SseEmitter emitter) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        if (!requireAgentScope(emitter)) {
            emitter.complete();
            return;
        }
        if (!conversationLock.tryAcquire(conversationId)) {
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
                turn = turnEventStore.start(conversation, content);
                String effectiveContent = content;
                List<String> effectivePicUrls = picUrls == null ? List.of() : picUrls;
                List<String> effectiveAssetIds = assetIds == null ? List.of() : assetIds;
                List<String> effectiveReferenceVideos =
                        referenceVideos == null ? List.of() : referenceVideos;
                List<String> effectiveReferenceAudios =
                        referenceAudios == null ? List.of() : referenceAudios;
                String effectiveRoutingHint = routingHint;
                if (editMessageId != null && !editMessageId.isBlank()) {
                    EditedTurn edited = prepareEditedTurn(
                            userId, conversation, editMessageId, content, effectivePicUrls, assetIds,
                            routingHint, effectiveReferenceVideos, effectiveReferenceAudios);
                    effectiveContent = edited.content();
                    effectivePicUrls = edited.picUrls();
                    effectiveAssetIds = edited.assetIds();
                    effectiveReferenceVideos = edited.referenceVideos();
                    effectiveReferenceAudios = edited.referenceAudios();
                    effectiveRoutingHint = edited.routingHint();
                } else if (persistUser) {
                    persistUserMessage(conversation, content, effectivePicUrls, effectiveAssetIds,
                            effectiveRoutingHint, effectiveReferenceVideos, effectiveReferenceAudios);
                }
                Map<String, Object> started = new LinkedHashMap<>();
                started.put("turnId", turn.getId());
                started.put("conversationId", conversationId);
                started.put("runtime", turn.getRuntimeEngine() == null
                        ? "agentscope-java-2.0.3" : turn.getRuntimeEngine());
                sendRuntimeEnvelope(emitter,
                        turnEventStore.append(turn.getId(), "turn_started", started));

                OrchestrationRequest request = new OrchestrationRequest(
                        conversation, effectiveContent, effectivePicUrls, emitter);
                request.setAssetIds(effectiveAssetIds);
                request.setRoutingHint(effectiveRoutingHint);
                request.setCanvasContext(canvasContext);
                request.setNativeEventsOnly(true);
                request.setReferenceVideos(effectiveReferenceVideos);
                request.setReferenceAudios(effectiveReferenceAudios);
                AgentTraceAccumulator trace = new AgentTraceAccumulator(request);
                request.setTrace(trace);

                AgentTurnResult result = agentScopeMediaRuntime.runTurn(
                        conversation, request, trace, eventObserver(turn.getId(), emitter));
                throwIfInterrupted(conversationId);
                finishAgentScopeTurn(conversation, turn, result, emitter);
            } catch (AgentInterruptException e) {
                if (turn != null) {
                    turnEventStore.finish(turn.getId(), "cancelled", "用户停止", OffsetDateTime.now());
                    sendRuntimeEnvelope(emitter, turnEventStore.append(turn.getId(), "turn_failed",
                            Map.of("code", "40904", "message", "生成已停止")));
                }
                log.info("AgentScope turn 被用户打断: conversationId={}", conversationId);
            } catch (Exception e) {
                if (turn != null) {
                    turnEventStore.finish(turn.getId(), "failed", e.getMessage(), OffsetDateTime.now());
                    sendRuntimeEnvelope(emitter, turnEventStore.append(turn.getId(), "turn_failed",
                            Map.of("code", "50001", "message", friendlyRuntimeError(e))));
                } else {
                    sendRuntimeError(emitter, "50001", friendlyRuntimeError(e));
                }
                log.error("AgentScope turn 失败: conversationId={}, error={}",
                        conversationId, e.getMessage(), e);
            } finally {
                conversationLock.release(conversationId);
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
        if (!conversationLock.tryAcquire(conversationId)) {
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

                OrchestrationRequest request = new OrchestrationRequest(
                        conversation, "", List.of(), emitter);
                request.setAction(action == null ? "" : action);
                request.setCustomText(customText == null ? "" : customText);
                request.setParams(params == null ? Map.of() : params);
                request.setAssetIds(assetIds == null ? List.of() : assetIds);
                request.setCanvasContext(canvasContext);
                request.setNativeEventsOnly(true);
                request.setCheckpointAction(claim.checkpoint().getAction());
                request.setCheckpointPlan(claim.checkpoint().getPlan());
                AgentTraceAccumulator trace = new AgentTraceAccumulator(request);
                request.setTrace(trace);

                AgentTurnResult result = agentScopeMediaRuntime.resumeTurn(
                        conversation, claim.checkpoint(), request, trace,
                        action, customText, eventObserver(turn.getId(), emitter));
                throwIfInterrupted(conversationId);
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
                conversationLock.release(conversationId);
                try { emitter.complete(); } catch (Exception ignore) { }
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
        status.put("turnProtocol", "agentscope-envelope-v1");
        if (agentScopeMediaRuntime != null && agentScopeMediaRuntime.enabled()) {
            status.put("assets", agentScopeMediaRuntime.assetStatus());
        }
        boolean locked = !conversationLock.tryAcquire(conversationId);
        status.put("locked", locked);
        if (!locked) conversationLock.release(conversationId);
        return status;
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

    private java.util.function.Consumer<AgentEvent> eventObserver(String turnId, SseEmitter emitter) {
        return event -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", agentScopeEventMapper.type(event));
            payload.put("data", agentScopeEventMapper.payload(event));
            AgentTurnEventEnvelope envelope = turnEventStore.append(
                    turnId, "agent_event", payload);
            sendRuntimeEnvelope(emitter, envelope);
        };
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
        if (result.hitlPaused()) {
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
        if (result.content() != null && !result.content().isBlank()) {
            persistAssistant(conversation, result);
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
        sendEvent(emitter, envelope.type(), Map.of(
                "turnId", envelope.turnId(),
                "seq", envelope.seq(),
                "type", envelope.type(),
                "payload", envelope.payload()));
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
                        .eq(com.moon.moonagent.entity.AgentCheckpoint::getStatus, "pending")
                        .gt(com.moon.moonagent.entity.AgentCheckpoint::getExpirationTime, java.time.OffsetDateTime.now())
                        .orderByDesc(com.moon.moonagent.entity.AgentCheckpoint::getCreatedAt)
                        .last("LIMIT 1"));
        if (cp == null) return null;
        try {
            JsonNode plan = objectMapper.readTree(cp.getPlan());
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("formToken", cp.getFormToken());
            result.put("taskId", "");
            result.put("expirationTime", cp.getExpirationTime().toEpochSecond());
            result.put("hitlType", cp.getAction() == null ? "" : cp.getAction());
            if (CANVAS_BIND_ACTION.equals(cp.getAction())) {
                AgentConversation conversation = conversationMapper.selectById(conversationId);
                result.put("kind", CANVAS_BIND_ACTION);
                result.put("projectId", conversation == null ? "" : conversation.getProjectId());
                result.put("requestedIntent", orchestratorSupport.planField(cp.getPlan(), "intent"));
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
            }
            if ("generate_video".equals(cp.getAction())) {
                result.put("kind", "video-plan");
                result.put("message", orchestratorSupport.planField(cp.getPlan(), "message"));
                String duration = orchestratorSupport.planField(cp.getPlan(), "duration");
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
        // 置位会话级打断标志：编排的流式循环/阶段边界检测到后协作式取消
        orchestratorSupport.interrupt(conversationId);
        log.info("Agent 对话打断请求已登记: conversationId={}, userId={}", conversationId, userId);
        return true;
    }
}



