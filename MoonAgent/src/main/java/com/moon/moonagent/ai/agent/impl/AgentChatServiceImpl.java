package com.moon.moonagent.ai.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.ConversationTitleService;
import com.moon.moonagent.ai.agent.IntentRecognitionService;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.ImageRefinePromptService;
import com.moon.moonagent.ai.VideoPlanService;
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
    private final StoryboardClient storyboardClient;
    private final FileStorageService fileStorageService;
    private final AiConfigProperties config;
    private final com.moon.moonagent.ai.agent.AgentOrchestrator orchestrator;
    private final com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport orchestratorSupport;
    private final com.moon.moonagent.mapper.AgentCheckpointMapper checkpointMapper;
    private final com.moon.moonagent.ai.agent.ConversationLock conversationLock;
    private final com.moon.moonagent.ai.agent.AgentAnswerService answerService;
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
        var project = storyboardClient.getProject(projectId);
        if (project == null) throw new BusinessException(40401, "项目不存在");
        if (!userId.equals(project.get("userId"))) throw new BusinessException(40301, "无权为该项目创建对话");

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
        // 清理内存态（防泄漏）
        titleScheduled.remove(conversationId);
        renamedTitleByConversation.remove(conversationId);
        lastPicUrlByConversation.remove(conversationId);
        orchestratorSupport.cleanupOnDelete(conversationId);
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
        long total = assetMapper.selectCount(
                new LambdaQueryWrapper<AgentAsset>()
                        .eq(AgentAsset::getConversationId, conversationId)
                        .ne(AgentAsset::getType, "reference"));
        List<AgentAsset> records = assetMapper.selectList(
                new LambdaQueryWrapper<AgentAsset>()
                        .eq(AgentAsset::getConversationId, conversationId)
                        .ne(AgentAsset::getType, "reference")
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
            // 友好化：返回给用户的文案由 LLM 翻译，不直接展示英文报错
            return orchestratorSupport.friendlyErrorText(e.getMessage(), "服务暂时出了点问题，请稍后重试。");
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
                sendFriendlyError(emitter, e.getMessage(), "服务暂时出了点问题，请稍后重试或换个说法再问我一次。");
                emitter.complete();
            } finally {
                // 先释放会话锁再 complete：前端收到 EOF 时锁已释放，防下一条消息立即撞锁（竞态 40901）
                conversationLock.release(conversationId);
                try { emitter.complete(); } catch (Exception ignore) { }
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

    /** 兜底友好错误：LLM 翻译原始错误 → message + message_end 正常收尾（不露英文报错） */
    private void sendFriendlyError(SseEmitter emitter, String rawError, String fallback) {
        String friendly = orchestratorSupport.friendlyErrorText(rawError, fallback);
        sendEvent(emitter, "message", Map.of("content", friendly));
        sendEvent(emitter, "message_end", Map.of("messageId", "", "sceneCount", -1L, "content", friendly));
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



    /**
     * 图生视频方案确认后生成（video_plan 事件「开始生成视频」触发，SSE）。
     *
     * <p>兼容壳：前端已统一走 {@code /form/submit}（action=generate_video → VideoIntentHandler.resume），
     * 本端点保留以兼容旧前端——checkpoint 校验/消费逻辑一致，执行改走异步
     * {@link AgentOrchestratorSupport#startVideoGenerationAsync}（task_accepted + 后台轮询）。
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
                com.moon.moonagent.entity.AgentCheckpoint cp = checkpointMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.moon.moonagent.entity.AgentCheckpoint>()
                        .eq(com.moon.moonagent.entity.AgentCheckpoint::getFormToken, planToken)
                        .eq(com.moon.moonagent.entity.AgentCheckpoint::getConversationId, conversationId)
                        .last("LIMIT 1"));
                if (cp == null || !"pending".equals(cp.getStatus()) || cp.getExpirationTime() == null
                        || cp.getExpirationTime().isBefore(OffsetDateTime.now())) {
                    log.warn("图生视频方案 checkpoint 无效或已过期: conversationId={}, planToken={}", conversationId, planToken);
                    sendEvent(emitter, "error", Map.of("code", "40001", "message", "视频方案已过期，请重新上传图片生成"));
                    return;
                }
                // 一次性消费（status pending→used 原子条件，防重放）
                int updated = checkpointMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.moon.moonagent.entity.AgentCheckpoint>()
                        .eq(com.moon.moonagent.entity.AgentCheckpoint::getId, cp.getId())
                        .eq(com.moon.moonagent.entity.AgentCheckpoint::getStatus, "pending")
                        .set(com.moon.moonagent.entity.AgentCheckpoint::getStatus, "used"));
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
                // 异步视频生成：创建任务 → task_accepted → 本轮 SSE 结束，前端轮询状态端点取结果
                orchestratorSupport.startVideoGenerationAsync(
                        new com.moon.moonagent.ai.agent.handler.OrchestrationRequest(
                                conversation, "", null, emitter),
                        message, duration, null, source);
                log.info("图生视频任务已受理: conversationId={}, duration={}", conversationId, duration);
            } catch (Exception e) {
                // I1：客户端已断开时不再补发 error/complete（emitter 已被容器关闭）
                if (cancel.get()) return;
                log.error("图生视频生成失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
                sendFriendlyError(emitter, e.getMessage(), "视频生成暂时失败了，请稍后重试。");
            } finally {
                // 先释放会话锁再 complete（防 EOF 竞态：前端收到 task_accepted 后立即轮询/再发消息）
                conversationLock.release(conversationId);
                if (!cancel.get()) emitter.complete();
            }
        }, agentExecutor);
    }


    /** 快照 plan 取值辅助：null 安全 + String 化 */


    /**
     * HITL 表单提交并续流（编排 checkpoint 驱动，替代 Dify form API）：
     * 校验 form_token 归属/未过期 → 一次性消费（status pending→used）→ orchestrator.resume 恢复对应 step。
     */
    public void submitFormAndResume(String userId, String conversationId, String formToken, String taskId, String action, String customText, Map<String, String> params, java.util.List<String> assetIds, String routingHint, SseEmitter emitter) {
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
                persistUserConfirmation(conversation, action, "custom".equals(action) ? customText : null);
                // checkpoint 校验 + 消费 + 恢复执行（校验失败/过期/重放在 orchestrator.resume 内处理）；
                // resume 返回本轮最后一条 message（如生成图片的 ![...](url)），落库 assistant 消息，
                // 否则刷新后 HITL 结果从对话中消失（产出素材里却有）
                String answer = orchestrator.resume(conversation, formToken, action, customText, params, assetIds, routingHint, emitter);
                if (answer != null && !answer.isBlank()) {
                    persistAssistant(conversation, answer);
                }
            } catch (Exception e) {
                // I1：客户端已断开时不再补发 error/complete（emitter 已被容器关闭）
                if (cancel.get()) {
                    log.debug("SSE 已取消，忽略 HITL 提交异常: conversationId={}", conversationId);
                    return;
                }
                log.error("HITL 提交失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
                sendFriendlyError(emitter, e.getMessage(), "刚才的操作没成功，请稍后重试或换个说法。");
            } finally {
                // 先释放会话锁再 complete（防 EOF 竞态）
                conversationLock.release(conversationId);
                try { emitter.complete(); } catch (Exception ignore) { }
            }
        }, agentExecutor);
    }

    /**
     * 视频异步任务状态查询（前端轮询）：按 taskId 查 agent_assets 行，归属校验防 IDOR。
     * 资产未归属（conversationId 空）或会话无权访问 → 统一 40401。
     */
    public Map<String, Object> getVideoTaskStatus(String userId, String taskId) {
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
                "assetId", asset.getId(),
                "status", asset.getStatus() == null ? "unknown" : asset.getStatus(),
                "url", asset.getUrl() == null ? "" : asset.getUrl(),
                "error", asset.getError() == null ? "" : asset.getError());
    }

    /**
     * HITL 人工确认动作落库为用户消息（如「确认：开始生成视频」）。
     * 与 sendMessage 的 user 消息同语义：独立事务（REQUIRES_NEW）立即提交，
     * 不随后续编排/生成结果成败而回滚，刷新/历史列表始终可见。
     * title 为确认动作可读文案（如「开始生成视频」），缺省回退 action id 原文。
     */
    private void persistUserConfirmation(AgentConversation conversation, String action, String title) {
        // 自定义输入：直接落库用户原文（不带「确认：」前缀）；否则 title 缺省回退 action id 原文
        if (title == null || title.isBlank()) title = action;
        final String content = "custom".equals(action) ? title : "确认：" + title;
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
}