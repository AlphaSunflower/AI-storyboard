package com.storyboard.service.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.service.agent.AgentSceneItem;
import com.storyboard.entity.AgentAsset;
import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.AgentMessage;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.AgentAssetMapper;
import com.storyboard.mapper.AgentConversationMapper;
import com.storyboard.mapper.AgentMessageMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.agent.AgentChatService;
import com.storyboard.service.agent.AgentGenerationService;
import com.storyboard.service.agent.ConversationTitleService;
import com.storyboard.service.agent.IntentRecognitionService;
import com.storyboard.service.FileStorageService;
import com.storyboard.service.ai.AiConfigProperties;
import com.storyboard.service.ai.ImageRefinePromptService;
import com.storyboard.service.ai.VideoPlanService;
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
 * Agent 对话服务 —— 代理 Dify /v1/chat-messages（blocking + streaming）。
 * 负责：会话校验、消息落库、Dify 调用、conversation_id 回填、SSE 流式转发、HITL 表单提交续流。
 *
 * 事务边界说明（I1）：
 * - user 消息用独立事务（REQUIRES_NEW）保存并立即提交；
 * - Dify 调用失败时 user 消息保留（独立事务已提交），assistant 消息不落库；
 * - Dify 成功后，回填 difyConversationId + 保存 assistant 消息在同一事务内完成。
 */
@Service
@RequiredArgsConstructor
public class AgentChatServiceImpl implements AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatServiceImpl.class);

    private final AgentConversationMapper conversationMapper;
    private final AgentGenerationService generationService;
    private final AgentMessageMapper messageMapper;
    private final AgentAssetMapper assetMapper;
    private final ConversationTitleService titleService;
    private final IntentRecognitionService intentRecognitionService;
    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final FileStorageService fileStorageService;
    private final AiConfigProperties config;
    private final ImageRefinePromptService imageRefinePromptService;
    private final VideoPlanService videoPlanService;
    private final com.storyboard.service.agent.AgentOrchestrator orchestrator;
    private final com.storyboard.mapper.AgentCheckpointMapper checkpointMapper;
    private final com.storyboard.service.agent.ConversationLock conversationLock;
    private final com.storyboard.service.agent.AgentAnswerService answerService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 专用 executor（I6）：SSE 长连接任务不再占用 ForkJoinPool.commonPool，
     * 避免一条长流拖垮其他并行流。JDK 21 虚拟线程天然 daemon、无池占用，无需手动 shutdown。
     */
    private final ExecutorService agentExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 首条消息异步 AI 重命名标题：
     * - titleScheduled：并发去重，仅调度一次；任务结束（无论成败）移除，允许清空后重聊再次触发；
     * - renamedTitleByConversation：重命名落库成功的新标题暂存，随本轮 message_end 一次性下发前端
     *   （remove 取走即删，绝不重复推送；不做轮询/持续推送）。
     */
    private final Set<String> titleScheduled = ConcurrentHashMap.newKeySet();
    private final Map<String, String> renamedTitleByConversation = new ConcurrentHashMap<>();

    /**
     * 匹配 Dify 工具文件 URL：http(s)://host/files/tools/xxx.ext?签名，或裸 /files/tools/xxx.ext?签名。
     * 字符集限定 RFC 3986 子集，避免 markdown 语法字符（) ] !）与中文标点被吞入。
     */
    private static final Pattern DIFY_TOOLS_URL_PATTERN = Pattern.compile(
            "(?:https?://[A-Za-z0-9\\-._~:]+)?/files/tools/[A-Za-z0-9\\-._~:/?&=+%]+");

    /**
     * 完善图片自动生成信号节点标题（Dify 工作流 answer 节点）。
     * 工作流已删除「完善图片设计方案」LLM 与 HITL 人工介入，改为：用户诉求 → user_finishing(code)
     * → 赋值 → 本 answer 节点（文案"结合用户输入理解图片优化提示词中..."）。后端监听到该节点
     * node_finished 即自动触发：视觉模型看图 + 用户诉求 → refined_prompt → 图生图 edits。
     * 必须与 Moon智能体.yml 中该 answer 节点的 title 完全一致。
     */
    private static final String AUTO_REFINE_SIGNAL_TITLE = "后端执行识别图片加人工介入流程";

    /**
     * 图生视频方案设计信号节点标题（Dify 工作流 answer 节点）。
     * 工作流「视频类型分流」判断携带参考图（conversation.picture 非空）→ 走本 answer 节点
     * （文案"结合你上传的参考图设计视频方案中..."）。后端监听到该节点 node_finished 即触发：
     * 视觉模型看图 + 用户诉求 → 视频方案（prompt + 时长）→ 推 video_plan 事件 →
     * 前端确认卡片 → 「开始生成视频」→ MiniMax 图生视频。
     * 必须与 Moon智能体.yml 中该 answer 节点的 title 完全一致。
     */
    private static final String VIDEO_PLAN_SIGNAL_TITLE = "后端执行图生视频方案设计";

    /**
     * 懒加载 TransactionTemplate（REQUIRES_NEW）：user 消息保存使用独立事务，即使外层存在事务也单独提交，
     * 保证 Dify 调用失败时 user 消息不被回滚。构造器内构建逻辑迁移至此，统一 @RequiredArgsConstructor 注入。
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
        var project = projectMapper.selectById(projectId);
        if (project == null) throw new BusinessException(40401, "项目不存在");
        if (!userId.equals(project.getUserId())) throw new BusinessException(40301, "无权为该项目创建对话");

        AgentConversation conversation = new AgentConversation();
        conversation.setUserId(userId);
        conversation.setProjectId(projectId);
        conversation.setTitle(title != null && !title.isBlank() ? title : "新对话");
        conversation.setStatus("active");
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

    /** 会话消息列表（created_at 正序） */
    public List<AgentMessage> listMessages(String conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getConversationId, conversationId)
            .orderByAsc(AgentMessage::getCreatedAt));
    }

    /**
     * 清空会话聊天记录（上下文重置）：
     * 删除该会话全部消息 + 清空 difyConversationId —— 下一条消息会开启全新的 Dify 会话，
     * AI 不再记得任何历史。会话本身与生成资产保留；仅影响当前会话，其他会话不受影响。
     */
    public void clearMessages(String userId, String conversationId) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        transactionTemplate().executeWithoutResult(tx -> {
            messageMapper.delete(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId));
            conversationMapper.updateById(conversation); // 触发 updatedAt fill
        });
        log.info("已清空会话聊天记录: conversationId={}, userId={}", conversationId, userId);
    }

    /**
     * 满意完成：清空会话图片上下文（原 Dify storage_pic_talk 语义）。
     *
     * 触发点：前端 confirm_result 卡片「满意完成」；图片方案状态重置，
     * 下次图片需求走全新设计（而非继续完善）。
     *
     * @return true（语义与旧 Dify 路径兼容：始终视为已完成）
     */
    public boolean confirmImageDone(String userId, String conversationId) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        // 满意完成：清空会话图片上下文（原 Dify storage_pic_talk 语义）——
        // 图片方案状态重置，下次图片需求走全新设计（而非继续完善）
        lastPicUrlByConversation.remove(conversationId);
        log.info("满意完成：已清空会话图片上下文: conversationId={}", conversationId);
        return true;
    }

    /**
     * 首条消息异步 AI 重命名标题：
     * 判定「该消息是会话第一条消息 + 标题仍为默认值 + 并发去重成功」三条件，
     * 满足则向 agentExecutor 提交异步任务（不阻塞 Dify 主流程，失败仅日志）；
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
        long total = assetMapper.selectCount(
                new LambdaQueryWrapper<AgentAsset>().eq(AgentAsset::getConversationId, conversationId));
        List<AgentAsset> records = assetMapper.selectList(
                new LambdaQueryWrapper<AgentAsset>()
                        .eq(AgentAsset::getConversationId, conversationId)
                        .orderByDesc(AgentAsset::getCreatedAt)
                        // 先转 long 再乘，避免 (safePage - 1) * safeSize 在 int 域溢出（OFFSET 超出 21 亿行时）
                        .last("LIMIT " + safeSize + " OFFSET " + ((long) (safePage - 1) * safeSize)));
        return Map.of("records", records, "total", total, "page", safePage, "size", safeSize);
    }

    @Override
    public String uploadReferenceImage(String userId, String conversationId, MultipartFile file) {
        // 校验会话归属（传了 conversationId 时）
        if (conversationId != null && !conversationId.isBlank()) {
            getOwnedConversation(userId, conversationId);
        }
        String url = fileStorageService.saveUploadedImage(file);

        // 落库 agent_assets（type=reference）
        AgentAsset asset = new AgentAsset();
        asset.setConversationId(conversationId != null && !conversationId.isBlank() ? conversationId : null);
        asset.setType("reference");
        asset.setUrl(url);
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
    public AgentMessage sendMessage(String userId, String conversationId, String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(40001, "消息内容不能为空");
        }
        AgentConversation conversation = getOwnedConversation(userId, conversationId);

        // 会话级互斥：同一会话只允许一个活跃编排实例（blocking 同步执行，锁持整个编排期）
        if (!conversationLock.tryAcquire(conversationId)) {
            throw new BusinessException(40901, "当前对话正在处理中，请稍候");
        }
        try {
            // 1. 保存 user 消息 —— 独立事务（REQUIRES_NEW），失败时保留
            AgentMessage userMessage = new AgentMessage();
            userMessage.setConversationId(conversationId);
            userMessage.setRole("user");
            userMessage.setContent(content);

            // 1.5 首条消息异步 AI 重命名标题（不阻塞编排；blocking 无 SSE 通道，靠前端下次拉取可见）。
            // 注意：必须在 user 消息落库【前】判定"首条"——落库后 selectCount 已 +1，判定永远不成立
            maybeScheduleTitleRename(conversation, content);

            transactionTemplate().executeWithoutResult(tx -> messageMapper.insert(userMessage));

            // 2. 走编排（blocking：用内存 SseEmitter 收集 message 事件，取最后一条回答）
            String answer = runBlocking(conversation, content);

            // 3. 成功：事务性保存 assistant 消息 + 刷新 updatedAt
            return transactionTemplate().execute(tx -> {
                conversationMapper.updateById(conversation);
                AgentMessage assistantMessage = new AgentMessage();
                assistantMessage.setConversationId(conversationId);
                assistantMessage.setRole("assistant");
                assistantMessage.setContent(answer);
                messageMapper.insert(assistantMessage);
                return assistantMessage;
            });
        } finally {
            conversationLock.release(conversationId);
        }
    }

    /** blocking 编排：直接走主回答服务（intent-other 语义；前端实际走 streamMessage SSE，此路为通用入口） */
    private String runBlocking(AgentConversation conversation, String content) {
        try {
            return answerService.answer(conversation, content, new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L));
        } catch (Exception e) {
            log.error("blocking 编排失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            return "服务异常，请稍后重试";
        }
    }


    /**
     * 流式发送消息：user 消息独立事务提交 → 代理 Dify streaming → 事件裁剪转发到 SseEmitter。
     * 收到 human_input_required → 转发 human_input 事件 → 结束流（Dify 侧 pause 自动关闭）。
     * message_end → 落库 assistant 消息 + 回填 dify_conversation_id + 附带 sceneCount。
     */
    public void streamMessage(String userId, String conversationId, String content, String picUrl, SseEmitter emitter) {
        if (content == null || content.isBlank()) {
            sendEvent(emitter, "error", Map.of("code", "40001", "message", "消息内容不能为空"));
            emitter.complete();
            return;
        }
        AgentConversation conversation = getOwnedConversation(userId, conversationId);

        // 会话级互斥：同一会话只允许一个活跃编排实例（同步获取，防双实例并发启动）
        if (!conversationLock.tryAcquire(conversationId)) {
            sendEvent(emitter, "error", Map.of("code", "40901", "message", "当前对话正在处理中，请稍候"));
            emitter.complete();
            return;
        }

        // 图改图参考图兜底：按会话记录最近一次 PicUrl（本条带图则更新，不带图则清空，
        // 严格限定在"带图的当轮"内生效，防跨轮误用）
        if (picUrl != null && !picUrl.isBlank()) {
            lastPicUrlByConversation.put(conversationId, picUrl);
        } else {
            lastPicUrlByConversation.remove(conversationId);
        }

        // I1：注册 SseEmitter 断开/超时/异常回调——客户端断开即置取消标志
        AtomicBoolean cancel = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancel.set(true));
        emitter.onTimeout(() -> cancel.set(true));
        emitter.onError(ignored -> cancel.set(true));

        // 1. user 消息独立事务立即提交
        AgentMessage userMessage = new AgentMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(content);

        // 1.5 首条消息异步 AI 重命名标题（不阻塞主流程；结果随本轮 message_end 一次性推送）。
        // 注意：必须在 user 消息落库【前】判定"首条"——落库后 selectCount 已 +1，判定永远不成立
        maybeScheduleTitleRename(conversation, content);

        transactionTemplate().executeWithoutResult(tx -> messageMapper.insert(userMessage));

        // 2. 异步走编排（Spring AI 应用层状态机；SseEmitter 需异步写，否则阻塞 Controller 返回）
        CompletableFuture.runAsync(() -> {
            try {
                String answer = orchestrator.run(conversation, content, picUrl, emitter);
                // 编排产生的最后一条 message 落库 assistant 消息（刷新后历史可见）
                if (answer != null && !answer.isBlank()) {
                    persistAssistant(conversation, answer);
                }
            } catch (Exception e) {
                // I1：客户端已断开时不再补发 error/complete（emitter 已被容器关闭）
                if (cancel.get()) {
                    log.debug("SSE 已取消，忽略编排异常: conversationId={}", conversationId);
                    return;
                }
                log.error("Agent 编排失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "服务异常，请稍后重试"));
                emitter.complete();
            } finally {
                // 会话锁释放（无论成败/断开，保证下一条消息可进入）
                conversationLock.release(conversationId);
            }
        }, agentExecutor);
    }


    /** 查最近 {@link IntentRecognitionService#HISTORY_LIMIT} 条消息（时间升序，供意图识别历史上下文） */
    private List<AgentMessage> loadRecentHistory(String conversationId) {
        List<AgentMessage> list = messageMapper.selectList(
                new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getConversationId, conversationId)
                        .orderByDesc(AgentMessage::getCreatedAt)
                        .last("LIMIT " + IntentRecognitionService.HISTORY_LIMIT));
        return list.reversed(); // Java 21 List.reversed()：倒序视图（最新在最后），零拷贝
    }

    /** SseEmitter 事件发送（捕获 IOException 忽略——前端已断开） */
    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            log.debug("SseEmitter 发送失败（前端可能已断开）: event={}", eventName);
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
     * 落库 assistant 消息（事务内原子完成：回填 difyConversationId + 保存消息 + 刷新会话 updatedAt）。
     * Z1：difyConversationId 回填必须与 assistant 落库同处一个事务——若分两段，回填事务失败时
     * assistant 已落库而 conversation_id 未更新，下一条消息会错误地开启新的 Dify 会话。
     *
     * I2：HITL 续流消息合并——若该会话最后一条 assistant 消息是 HITL 暂停时落库的"未完成"消息
     * （difyMessageId 为 null），则把续流新内容追加到它上面并回填 messageId，避免同一轮
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


    // ============ 智能体生成后端化：HITL 方案快照缓存 ============

    /** conversationId → 最近一次消息携带的 PicUrl（图改图兜底：node_finished 不带 code 节点 outputs，
     *  plan 无 picture；本字段在 streamMessage 按轮更新/清空，仅当轮生效） */
    private final Map<String, String> lastPicUrlByConversation = new ConcurrentHashMap<>();



    /** 生成中/完成的工作流进度事件（title 供前端展示生成阶段） */
    private static final Map<String, String> GENERATION_STAGE_LABELS = Map.of(
        "script", "正在生成分镜…",
        "image", "正在生成图片…",
        "video", "正在生成视频…"
    );

    /** 推送生成结果：image 完成推图消息 + 看图确认卡片；script 已由调用方推。
     *  §4.3：推给前端的生成结果消息同步落库（conversation 由调用方传入，会话已删时为 null，判空跳过） */
    private void pushGenerationResult(SseEmitter emitter, String type, String url,
                                      String assetId, int sceneCount, boolean withConfirmCard,
                                      AgentConversation conversation) {
        if (url == null || url.isBlank()) {
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "生成失败，请稍后重试"));
            return;
        }
        String content;
        if ("image".equals(type)) {
            content = "![生成图片](" + url + ")";
        } else {
            content = url;
        }
        sendEvent(emitter, "message", Map.of("content", content));
        // §4.3：生成结果消息落库（刷新后历史消息里生成结果不消失）；会话已删则跳过
        if (conversation != null) persistAssistant(conversation, content);
        if (withConfirmCard) {
            sendEvent(emitter, "confirm_result", Map.of(
                "kind", type, "url", url, "assetId", assetId == null ? "" : assetId,
                "sceneCount", sceneCount,
                "actions", List.of(
                    Map.of("id", "refine", "title", "继续完善"),
                    Map.of("id", "done", "title", "满意完成"))));
        }
    }

    /**
     * 执行视频生成（HITL generate_video 与图生视频方案确认两路共用）：
     * 发送生成进度事件 → 创建 MiniMax 视频任务 → 清空 Dify 会话 picture 变量（生成后）
     * → 同步轮询直至终态 → 推视频结果 + 确认卡片。
     *
     * @param duration 时长字符串（秒，可 null 用默认）；aspectRatio 画幅（可 null；图生视频恒 adaptive）
     */
    private void executeVideoGeneration(AgentConversation conv, String prompt, String duration,
                                        String aspectRatio, String source, SseEmitter emitter) {
        sendEvent(emitter, "workflow", Map.of("title", GENERATION_STAGE_LABELS.get("video"), "status", "node_started"));
        String taskId = generationService.createVideoTask(
            conv, null, prompt, null, null, null, aspectRatio,
            duration, null, null, source);
        // 同步轮询直至终态（运行在虚拟线程，阻塞安全；轮询完成即 SSE 关闭前推送完成）
        pollVideoAndPush(taskId, emitter, conv);
    }

    /**
     * 图生视频方案确认后生成（video_plan 事件「开始生成视频」触发，SSE）。
     *
     * 流程：按 planToken 取方案快照（消费即移除，防重放）→ 确认动作落库 →
     * {@link #executeVideoGeneration}（源图=方案快照 source，prompt=视觉模型设计的 message）。
     */
    public void generateVideoFromPlan(String userId, String conversationId, String planToken, SseEmitter emitter) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        // 会话级互斥：同一会话只允许一个活跃编排实例（同步获取）
        if (!conversationLock.tryAcquire(conversationId)) {
            sendEvent(emitter, "error", Map.of("code", "40901", "message", "当前对话正在处理中，请稍候"));
            emitter.complete();
            return;
        }
        // I1：注册 SseEmitter 断开/超时/异常回调（同 streamMessage / submitFormAndResume）
        AtomicBoolean cancel = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancel.set(true));
        emitter.onTimeout(() -> cancel.set(true));
        emitter.onError(ignored -> cancel.set(true));
        CompletableFuture.runAsync(() -> {
            try {
                // checkpoint 驱动（替代内存 videoPlanSnapshots）：planToken 即 checkpoint formToken，
                // plan JSON 存 {message, duration, source}；校验归属/未过期后一次性消费
                com.storyboard.entity.AgentCheckpoint cp = checkpointMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.storyboard.entity.AgentCheckpoint>()
                        .eq(com.storyboard.entity.AgentCheckpoint::getFormToken, planToken)
                        .eq(com.storyboard.entity.AgentCheckpoint::getConversationId, conversationId)
                        .last("LIMIT 1"));
                if (cp == null || !"pending".equals(cp.getStatus()) || cp.getExpirationTime() == null
                        || cp.getExpirationTime().isBefore(OffsetDateTime.now())) {
                    log.warn("图生视频方案 checkpoint 无效或已过期: conversationId={}, planToken={}", conversationId, planToken);
                    sendEvent(emitter, "error", Map.of("code", "40001", "message", "视频方案已过期，请重新上传图片生成"));
                    return;
                }
                // 一次性消费（status pending→used 原子条件，防重放）
                int updated = checkpointMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.storyboard.entity.AgentCheckpoint>()
                        .eq(com.storyboard.entity.AgentCheckpoint::getId, cp.getId())
                        .eq(com.storyboard.entity.AgentCheckpoint::getStatus, "pending")
                        .set(com.storyboard.entity.AgentCheckpoint::getStatus, "used"));
                if (updated == 0) {
                    sendEvent(emitter, "error", Map.of("code", "40001", "message", "视频方案已被使用，请重新发起"));
                    return;
                }
                // 解析 plan JSON：{message, duration, source}（兼容 items 数组包裹：{items:[{...}]}）
                String message = "";
                String duration = null;
                String source = null;
                if (cp.getPlan() != null && !cp.getPlan().isBlank()) {
                    try {
                        JsonNode plan = objectMapper.readTree(cp.getPlan());
                        JsonNode node = plan;
                        JsonNode items = plan.path("items");
                        if (items.isArray() && !items.isEmpty()) {
                            node = items.get(0);
                        }
                        message = node.path("message").asText("");
                        duration = node.hasNonNull("duration") ? node.path("duration").asText() : null;
                        source = node.hasNonNull("source") ? node.path("source").asText(null) : null;
                    } catch (Exception e) {
                        log.warn("图生视频 checkpoint plan 解析失败: {}", e.getMessage());
                    }
                }
                if (message.isBlank()) {
                    sendEvent(emitter, "error", Map.of("code", "40001", "message", "视频方案内容缺失，请重新上传图片生成"));
                    return;
                }
                // 确认动作落库为用户消息（独立事务立即提交，刷新/历史可见）
                persistUserConfirmation(conversation, "开始生成视频", null);
                executeVideoGeneration(conversation, message, duration,
                        null, source, emitter);
                log.info("图生视频生成完成: conversationId={}, duration={}", conversationId, duration);
            } catch (Exception e) {
                // I1：客户端已断开时不再补发 error/complete（emitter 已被容器关闭）
                if (cancel.get()) return;
                log.error("图生视频生成失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "视频生成失败，请稍后重试"));
            } finally {
                if (!cancel.get()) emitter.complete();
                conversationLock.release(conversationId);
            }
        }, agentExecutor);
    }


    /** 轮询视频任务直至终态（复用 service 重试逻辑），终态推结果与确认卡片 */
    private void pollVideoAndPush(String taskId, SseEmitter emitter, AgentConversation conversation) {
        try {
            for (int i = 0; i < 90; i++) { // 90 * 5s ≈ 7.5min 上限
                if (Thread.currentThread().isInterrupted()) return;
                Map<String, String> result = generationService.pollVideoTask(taskId);
                String status = result.get("status");
                // 轮询可见性：每 5s 一次状态查询打 info 日志（含次数/状态/进度），
                // 便于观察生成进度；约 90 条/任务，视频生成本来就是低频长任务，噪音可接受
                String progress = result.get("progress");
                log.info("视频生成轮询: taskId={}, 第 {}/90 次, status={}{}", taskId, i + 1, status,
                        progress != null && !progress.isBlank() ? ", progress=" + progress : "");
                if ("completed".equals(status)) {
                    pushGenerationResult(emitter, "video", result.get("videoUrl"), null, 0, true, conversation);
                    return;
                }
                if ("failed".equals(status)) {
                    sendEvent(emitter, "error", Map.of("code", "50202",
                        "message", "视频生成失败：" + result.getOrDefault("error", "未知错误")));
                    return;
                }
                Thread.sleep(5000);
            }
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "视频生成超时，请重试"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("视频轮询失败: taskId={}, error={}", taskId, e.getMessage(), e);
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "视频生成失败，请稍后重试"));
        }
    }


    /** 快照 plan 取值辅助：null 安全 + String 化 */


    /**
     * HITL 表单提交并续流（编排 checkpoint 驱动，替代 Dify form API）：
     * 校验 form_token 归属/未过期 → 一次性消费（status pending→used）→ orchestrator.resume 恢复对应 step。
     */
    public void submitFormAndResume(String userId, String conversationId, String formToken, String taskId, String action, SseEmitter emitter) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        // 会话级互斥：同一会话只允许一个活跃编排实例（同步获取）
        if (!conversationLock.tryAcquire(conversationId)) {
            sendEvent(emitter, "error", Map.of("code", "40901", "message", "当前对话正在处理中，请稍候"));
            emitter.complete();
            return;
        }
        // I1：注册 SseEmitter 断开/超时/异常回调（同 streamMessage，语义见上）
        AtomicBoolean cancel = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancel.set(true));
        emitter.onTimeout(() -> cancel.set(true));
        emitter.onError(ignored -> cancel.set(true));
        CompletableFuture.runAsync(() -> {
            try {
                // 生成后端化：HITL 人工确认动作落库为用户消息（独立事务立即提交，刷新/历史可见）
                persistUserConfirmation(conversation, action, null);
                // checkpoint 校验 + 消费 + 恢复执行（校验失败/过期/重放在 orchestrator.resume 内处理）
                orchestrator.resume(conversation, formToken, action, emitter);
            } catch (Exception e) {
                // I1：客户端已断开时不再补发 error/complete（emitter 已被容器关闭）
                if (cancel.get()) {
                    log.debug("SSE 已取消，忽略 HITL 提交异常: conversationId={}", conversationId);
                    return;
                }
                log.error("HITL 提交失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "服务异常，请稍后重试"));
                emitter.complete();
            } finally {
                conversationLock.release(conversationId);
            }
        }, agentExecutor);
    }

    /**
     * HITL 人工确认动作落库为用户消息（如「确认：开始生成视频」）。
     * 与 sendMessage 的 user 消息同语义：独立事务（REQUIRES_NEW）立即提交，
     * 不随后续编排/生成结果成败而回滚，刷新/历史列表始终可见。
     * title 为确认动作可读文案（如「开始生成视频」），缺省回退 action id 原文。
     */
    private void persistUserConfirmation(AgentConversation conversation, String action, String title) {
        if (title == null || title.isBlank()) title = action;
        final String content = "确认：" + title;
        transactionTemplate().executeWithoutResult(tx -> {
            AgentMessage userMessage = new AgentMessage();
            userMessage.setConversationId(conversation.getId());
            userMessage.setRole("user");
            userMessage.setContent(content);
            messageMapper.insert(userMessage);
        });
        log.info("HITL 确认动作已落库: conversationId={}, action={}, title={}",
                conversation.getId(), action, title);
    }
}