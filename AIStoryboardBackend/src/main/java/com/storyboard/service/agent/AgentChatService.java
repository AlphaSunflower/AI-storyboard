package com.storyboard.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.dto.request.DifyGenerateScriptRequest;
import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.AgentMessage;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.AgentConversationMapper;
import com.storyboard.mapper.AgentMessageMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.FileStorageService;
import com.storyboard.service.ai.AiConfigProperties;
import com.storyboard.service.ai.ImageRefinePromptService;
import com.storyboard.service.ai.VideoPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final AgentConversationMapper conversationMapper;
    private final AgentGenerationService generationService;
    private final AgentMessageMapper messageMapper;
    private final ConversationTitleService titleService;
    private final IntentRecognitionService intentRecognitionService;
    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final FileStorageService fileStorageService;
    private final AiConfigProperties config;
    private final ImageRefinePromptService imageRefinePromptService;
    private final VideoPlanService videoPlanService;
    private final TransactionTemplate transactionTemplate;
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

    public AgentChatService(AgentConversationMapper conversationMapper,
                            AgentGenerationService generationService,
                            AgentMessageMapper messageMapper,
                            ConversationTitleService titleService,
                            IntentRecognitionService intentRecognitionService,
                            ProjectMapper projectMapper,
                            SceneMapper sceneMapper,
                            FileStorageService fileStorageService,
                            AiConfigProperties config,
                            ImageRefinePromptService imageRefinePromptService,
                            VideoPlanService videoPlanService,
                            PlatformTransactionManager transactionManager) {
        this.conversationMapper = conversationMapper;
        this.generationService = generationService;
        this.messageMapper = messageMapper;
        this.titleService = titleService;
        this.intentRecognitionService = intentRecognitionService;
        this.projectMapper = projectMapper;
        this.sceneMapper = sceneMapper;
        this.fileStorageService = fileStorageService;
        this.config = config;
        this.imageRefinePromptService = imageRefinePromptService;
        this.videoPlanService = videoPlanService;
        // user 消息保存使用独立事务（REQUIRES_NEW）：即使外层存在事务，也单独提交，
        // 保证 Dify 调用失败时 user 消息不被回滚。
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        transactionTemplate.executeWithoutResult(tx -> {
            messageMapper.delete(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId));
            if (conversation.getDifyConversationId() != null && !conversation.getDifyConversationId().isBlank()) {
                conversation.setDifyConversationId(null);
                conversationMapper.updateById(conversation);
            }
        });
        log.info("已清空会话聊天记录: conversationId={}, userId={}", conversationId, userId);
    }

    /**
     * 满意完成：清空 Dify 会话的 storage_pic_talk 变量（图片方案状态重置）。
     *
     * 触发点：前端 confirm_result 卡片「满意完成」→ 本方法；生成后的人工介入由后端驱动，
     * 清空动作也必须在后端做（而非 Dify 工作流 generate_image 分支）——点「生成图片」只是开始
     * 生成，未确认满意，变量必须保留供「继续完善」走完善路径；确认满意后才清空，下次图片
     * 需求才走全新设计。
     *
     * 实现：GET /v1/conversations/{difyId}/variables（user 走 query 参数）→ 取 variable_id →
     * PUT .../variables/{variableId}，body {value: 四字段全空, user}（user 走 JSON body；Dify 归属校验
     * from_end_user_id == user.id，user 必须与建会话时一致，即后端 userId）。
     *
     * ⚠ Dify 1.16.1 PG bug：GET 不能带 variable_name 过滤参数——PG 分支用
     * json_extract_path_text(data,'name') 过滤，但 data 列是 text 类型，PG 不自动 cast，
     * 必然 500（UndefinedFunction: json_extract_path_text(text, unknown) does not exist，实测）。
     * 必须不带参数拉全量后本地遍历匹配 name。
     *
     * @return true=已清空（含 Dify 变量不存在等视为已完成）；false=Dify 会话未建立（无可清空）
     */
    public boolean confirmImageDone(String userId, String conversationId) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        String difyId = conversation.getDifyConversationId();
        if (difyId == null || difyId.isBlank()) {
            log.info("满意完成：会话尚无 Dify 会话（difyConversationId 为空），无可清空: conversationId={}", conversationId);
            return false;
        }
        try {
            // 1. 查变量列表，定位 storage_pic_talk 的 variable_id（GET 的 user 走 query 参数；
            //    不带 variable_name 过滤——Dify 1.16.1 PG bug，见方法 javadoc）
            HttpRequest listReq = HttpRequest.newBuilder()
                .uri(URI.create(config.getDifyBaseUrl() + "/v1/conversations/"
                    + URLEncoder.encode(difyId, StandardCharsets.UTF_8)
                    + "/variables?user=" + URLEncoder.encode(userId, StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + config.getDifyApiKey())
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            HttpResponse<String> listResp = httpClient.send(listReq, HttpResponse.BodyHandlers.ofString());
            if (listResp.statusCode() != 200) {
                log.error("Dify 查询会话变量失败: status={}, body={}", listResp.statusCode(), listResp.body());
                throw new BusinessException(50202, "Dify 服务异常，请稍后重试");
            }
            JsonNode root = objectMapper.readTree(listResp.body());
            JsonNode data = root.path("data");
            String variableId = null;
            if (data.isArray()) {
                for (JsonNode item : data) {
                    if ("storage_pic_talk".equals(item.path("name").asText())) {
                        variableId = item.path("id").asText(null);
                        break;
                    }
                }
            }
            if (variableId == null || variableId.isBlank()) {
                // 变量不存在（工作流未部署/变量改名）视为已完成，不阻断满意完成
                log.info("Dify 会话变量 storage_pic_talk 不存在，视为已清空: difyId={}", difyId);
                return true;
            }
            // 2. 整体重置 storage_pic_talk（四字段全空；必须整体覆盖——「传到公共变量」code 会
            //    兜底取 picture 走图改图，只清 pic_generate_talk 不清 picture 会让下次全新设计
            //    意外带旧图）。PUT 的 user 走 JSON body（Dify 源码 WhereisUserArg.JSON）。
            Map<String, Object> emptyValue = Map.of(
                "mode", "",
                "pic_generate_talk", "",
                "picture", "",
                "user_finishing", ""
            );
            HttpRequest updateReq = HttpRequest.newBuilder()
                .uri(URI.create(config.getDifyBaseUrl() + "/v1/conversations/"
                    + URLEncoder.encode(difyId, StandardCharsets.UTF_8)
                    + "/variables/" + URLEncoder.encode(variableId, StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getDifyApiKey())
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                    "value", emptyValue,
                    "user", userId))))
                .build();
            HttpResponse<String> updateResp = httpClient.send(updateReq, HttpResponse.BodyHandlers.ofString());
            if (updateResp.statusCode() != 200) {
                log.error("Dify 清空会话变量失败: status={}, body={}", updateResp.statusCode(), updateResp.body());
                throw new BusinessException(50202, "Dify 服务异常，请稍后重试");
            }
            log.info("满意完成：已清空 Dify storage_pic_talk: conversationId={}, difyId={}", conversationId, difyId);
            return true;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("满意完成清空 Dify 变量异常: conversationId={}", conversationId, e);
            throw new BusinessException(50202, "Dify 服务异常，请稍后重试");
        }
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

        // 1. 保存 user 消息 —— 独立事务（REQUIRES_NEW），Dify 失败时保留
        AgentMessage userMessage = new AgentMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(content);

        // 1.5 首条消息异步 AI 重命名标题（不阻塞 Dify 调用；blocking 无 SSE 通道，靠前端下次拉取可见）。
        // 注意：必须在 user 消息落库【前】判定"首条"——落库后 selectCount 已 +1，判定永远不成立
        maybeScheduleTitleRename(conversation, content);

        transactionTemplate.executeWithoutResult(tx -> messageMapper.insert(userMessage));

        // 1.6 意图识别（type 控制 Dify 工作流路由；失败已兜底 intent-other，不阻塞）。
        // 落库后查历史（含本条，与"当前输入"轻微重复，flash 档成本可忽略）——与 streamMessage 行为一致
        String intentType = intentRecognitionService.recognize(content, loadRecentHistory(conversationId));

        // 2. 调 Dify chat-messages
        Map<String, Object> result;
        try {
            result = callDifyChat(conversation, content, userId, intentType);
        } catch (Exception e) {
            // M8：失败路径也刷新 conversation 的 updatedAt（updateById 触发 MyBatis fill），
            // 反映最近一次对话尝试；刷新失败只记日志，不掩盖原始异常。
            try {
                transactionTemplate.executeWithoutResult(tx -> conversationMapper.updateById(conversation));
            } catch (Exception ex) {
                log.warn("刷新会话 updatedAt 失败: conversationId={}", conversationId, ex);
            }
            throw e;
        }

        // 3. 成功：事务性完成"回填 difyConversationId + 保存 assistant 消息 + 刷新 updatedAt"
        return transactionTemplate.execute(tx -> {
            String difyConversationId = (String) result.get("conversationId");
            if (difyConversationId != null && !difyConversationId.isBlank()
                    && !difyConversationId.equals(conversation.getDifyConversationId())) {
                conversation.setDifyConversationId(difyConversationId);
            }
            // M8：成功路径也刷新 updatedAt（updateById 触发 MyBatis fill）
            conversationMapper.updateById(conversation);

            AgentMessage assistantMessage = new AgentMessage();
            assistantMessage.setConversationId(conversationId);
            assistantMessage.setRole("assistant");
            assistantMessage.setContent((String) result.getOrDefault("answer", ""));
            assistantMessage.setDifyMessageId((String) result.get("messageId"));
            messageMapper.insert(assistantMessage);
            return assistantMessage;
        });
    }

    /** 调用 Dify /v1/chat-messages（blocking 模式） */
    private Map<String, Object> callDifyChat(AgentConversation conversation,
                                              String query, String userId, String type) {
        try {
            Map<String, Object> body = new HashMap<>();
            // Moon 工作流 start 节点变量：currentProjectId（项目 ID）+ PicUrl（参考图 URL）+ type（意图路由）
            body.put("inputs", Map.of(
                "currentProjectId", conversation.getProjectId(),
                "PicUrl", "",
                "type", type
            ));
            body.put("query", query);
            body.put("response_mode", "blocking");
            body.put("user", userId);
            if (conversation.getDifyConversationId() != null
                    && !conversation.getDifyConversationId().isBlank()) {
                body.put("conversation_id", conversation.getDifyConversationId());
            }

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getDifyBaseUrl() + "/v1/chat-messages"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getDifyApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                // M5：上游错误完整信息只进日志，抛给客户端的文案脱敏
                log.error("Dify chat-messages 返回非 200: status={}, body={}",
                        resp.statusCode(), resp.body());
                throw new BusinessException(50202, "Dify 服务异常，请稍后重试");
            }

            JsonNode root = objectMapper.readTree(resp.body());
            Map<String, Object> result = new HashMap<>();
            result.put("answer", root.path("answer").asText(""));
            result.put("conversationId", root.path("conversation_id").asText(""));
            result.put("messageId", root.path("message_id").asText(""));
            return result;
        } catch (BusinessException e) {
            // 已脱敏的业务异常直接透传（文案不携带上游细节）
            throw e;
        } catch (Exception e) {
            // M5：完整异常（含根因堆栈）只进日志
            log.error("Dify chat-messages 调用失败: conversationId={}, error={}",
                    conversation.getId(), e.getMessage(), e);
            throw new BusinessException(50202, "Dify 服务异常，请稍后重试");
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

        // 图改图参考图兜底：Dify 的 node_finished 事件不带 code 节点 outputs（联调实证 plan 只有
        // LLM 的 text/structured_output，picture/mode 永远拿不到）→ 后端按会话记录最近一次 PicUrl：
        // 本条消息带图则更新，不带图则清空（严格限定在"带图的当轮"内生效，防跨轮误用）
        if (picUrl != null && !picUrl.isBlank()) {
            lastPicUrlByConversation.put(conversationId, picUrl);
        } else {
            lastPicUrlByConversation.remove(conversationId);
        }

        // I1：注册 SseEmitter 断开/超时/异常回调——客户端断开即置取消标志，
        // forwardDifySse 读循环据此尽早退出并跳过落库，避免对已断开的连接做无效工作。
        // （若 BufferedReader 阻塞在 readLine 无法立即中断，由 600s 超时兜底）
        AtomicBoolean cancel = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancel.set(true));
        emitter.onTimeout(() -> cancel.set(true));
        emitter.onError(ignored -> cancel.set(true));

        // 1. user 消息独立事务立即提交
        AgentMessage userMessage = new AgentMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(content);

        // 1.5 首条消息异步 AI 重命名标题（不阻塞 Dify 主流程；结果随本轮 message_end 一次性推送）。
        // 注意：必须在 user 消息落库【前】判定"首条"——落库后 selectCount 已 +1，判定永远不成立
        maybeScheduleTitleRename(conversation, content);

        transactionTemplate.executeWithoutResult(tx -> messageMapper.insert(userMessage));

        // 2. 异步代理 Dify（SseEmitter 需异步写，否则阻塞 Controller 返回；I6 专用 executor）
        CompletableFuture.runAsync(() -> {
            try {
                // 2.0 意图识别（type 控制 Dify 工作流路由；失败已兜底 intent-other，不阻塞）。
                // user 消息已在上方落库，历史查询含本条——与"当前输入"轻微重复，flash 档成本可忽略
                String intentType = intentRecognitionService.recognize(content, loadRecentHistory(conversationId));
                Map<String, Object> body = buildChatBody(conversation, content, picUrl, intentType);
                body.put("response_mode", "streaming");
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getDifyBaseUrl() + "/v1/chat-messages"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getDifyApiKey())
                    .timeout(Duration.ofSeconds(600))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
                HttpResponse<java.io.InputStream> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    // Z2：非 200 时 InputStream 未被消费，显式关闭避免每次 Dify 错误泄漏一个 HTTP 连接
                    closeQuietly(resp.body());
                    log.error("Dify chat-messages streaming 非 200: status={}", resp.statusCode());
                    sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                    emitter.complete();
                    return;
                }
                forwardDifySse(resp, emitter, conversation, userId, new StringBuilder(), cancel, false);
            } catch (Exception e) {
                // I1：客户端已断开时不再补发 error/complete（emitter 已被容器关闭）
                if (cancel.get()) {
                    log.debug("SSE 已取消，忽略流式调用异常: conversationId={}", conversationId);
                    return;
                }
                log.error("Dify streaming 调用失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                emitter.complete();
            }
        }, agentExecutor);
    }

    /** 构建 Dify chat-messages 请求体（streaming/blocking 共用） */
    private Map<String, Object> buildChatBody(AgentConversation conversation, String query,
                                              String picUrl, String type) {
        Map<String, Object> body = new HashMap<>();
        body.put("inputs", Map.of(
            "currentProjectId", conversation.getProjectId(),
            "PicUrl", picUrl == null ? "" : picUrl,
            "type", type
        ));
        body.put("query", query);
        body.put("user", conversation.getUserId());
        if (conversation.getDifyConversationId() != null && !conversation.getDifyConversationId().isBlank()) {
            body.put("conversation_id", conversation.getDifyConversationId());
        }
        return body;
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

    /** 静默关闭响应流（Z2：非 200 分支的 InputStream 必须关闭，避免 HTTP 连接泄漏） */
    private static void closeQuietly(java.io.InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Exception ignored) {
            // 关闭失败无补救手段，忽略
        }
    }

    /**
     * 逐行读取 Dify SSE 流，裁剪转发给前端。
     * 事件类型由 SSE event name 承担，转发负载本身不含 type 键。事件映射：
     *   message                → 累积 answer，转发 {content:增量}
     *   node_started/finished  → 转发 {title, status}（丢弃 inputs/outputs）
     *   human_input_required   → 转发 {formToken, taskId, formContent, actions, expirationTime}，结束流
     *   message_end            → 落库 assistant + 回填 + 转发 {messageId, sceneCount}，结束流
     *   error                  → 转发 {code, message}，结束流
     *   ping 等其余            → 忽略
     */
    private void forwardDifySse(HttpResponse<java.io.InputStream> resp, SseEmitter emitter,
                                AgentConversation conversation, String userId, StringBuilder answer,
                                AtomicBoolean cancel, boolean deferComplete) {
        // 完善图片自动生成标志：信号节点（answer「后端执行识别图片加人工介入流程」）触发后置位，
        // 后续 message_end / EOF 等收尾事件不再 complete（等自动生成完成后再 complete）
        AtomicBoolean autoGenerate = new AtomicBoolean(false);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // I1：客户端断开（cancel 置位）时尽早退出读循环，不再转发/落库
                if (cancel.get()) break;
                if (!line.startsWith("data:")) continue;
                JsonNode node;
                try {
                    node = objectMapper.readTree(line.substring(5).trim());
                } catch (Exception e) {
                    continue; // 忽略无法解析的行
                }
                String event = node.path("event").asText("");
                switch (event) {
                    case "message" -> {
                        String delta = node.path("answer").asText("");
                        // Dify 工具生成的文件（图片/视频）URL 是 /files/tools/ 相对路径，
                        // 前端访问不到，需拼上 Dify base（负向后顾避免替换已含域名的 URL，如 http://localhost/files/tools/）
                        if (delta.contains("/files/tools/")) {
                            delta = delta.replaceAll("(?<![A-Za-z0-9])/files/tools/", config.getDifyBaseUrl() + "/files/tools/");
                        }
                        answer.append(delta);
                        sendEvent(emitter, "message", Map.of("content", delta));
                    }
                    case "node_started", "node_finished" -> {
                        // 生成后端化：node_finished 捕获 LLM 方案输出（分镜 items / 图片 message+style+size /
                        // 视频方案），暂存供表单提交时取用。outputs 仅进内存，绝不转发前端。
                        if ("node_finished".equals(event)) {
                            // 完善图片自动生成信号：answer 节点标题命中 → 无 HITL，直接触发后端生成
                            // （视觉模型看图 + 用户诉求 → refined_prompt → 图生图 edits）
                            String nodeTitle = node.path("data").path("title").asText("");
                            if (AUTO_REFINE_SIGNAL_TITLE.equals(nodeTitle)) {
                                if (autoGenerate.compareAndSet(false, true)) {
                                    triggerAutoImageRefine(conversation, emitter, cancel);
                                }
                            }
                            // 图生视频方案设计信号：answer 节点标题命中 → 无 HITL，后端视觉模型
                            // 看图设计方案 → 推 video_plan 事件（前端确认卡片）→ 确认后生成
                            if (VIDEO_PLAN_SIGNAL_TITLE.equals(nodeTitle)) {
                                if (autoGenerate.compareAndSet(false, true)) {
                                    triggerAutoVideoPlan(conversation, emitter, cancel);
                                }
                            }
                            JsonNode outputs = node.path("data").path("outputs");
                            if (outputs.isObject() && !outputs.isEmpty()) {
                                lastNodeOutputs.put(conversation.getId(),
                                        objectMapper.convertValue(outputs, Map.class));
                            }
                        }
                        sendEvent(emitter, "workflow", Map.of(
                            "title", node.path("data").path("title").asText(""),
                            "status", "node_started".equals(event) ? "node_started" : "node_finished"));
                    }
                    case "human_input_required" -> {
                        JsonNode data = node.path("data");
                        List<Map<String, String>> actions = new ArrayList<>();
                        for (JsonNode a : data.path("actions")) {
                            actions.add(Map.of("id", a.path("id").asText(""), "title", a.path("title").asText("")));
                        }
                        // 生成后端化：转发前缓存方案快照，表单提交时按 formToken 取用
                        cacheFormSnapshot(data.path("form_token").asText(""),
                                data.path("form_content").asText(""), actions, conversation);
                        sendEvent(emitter, "human_input", Map.of(
                            "formToken", data.path("form_token").asText(""),
                            // 续流端点 /v1/workflow/{id}/events 需要 workflow_run_id（源码 doc：
                            // task_id = "Workflow run ID"）；human_input 事件的顶层 task_id 是消息级
                            // 任务标识（不存在于 workflow_runs），用它续流必然 404
                            "taskId", node.path("workflow_run_id").asText(""),
                            "formContent", data.path("form_content").asText(""),
                            "actions", actions,
                            "expirationTime", data.path("expiration_time").asLong(0)));
                        // HITL 暂停：落库方案文本（formContent 优先——structured_output 节点的
                        // answer 可能为空，方案文本只在 formContent 中），结束当前流
                        String hitlFormContent = data.path("form_content").asText("");
                        persistAssistant(conversation,
                                !hitlFormContent.isBlank() ? hitlFormContent : answer.toString(), null, null);
                        if (!deferComplete && !autoGenerate.get()) emitter.complete();
                        return;
                    }
                    case "message_end" -> {
                        String messageId = node.path("message_id").asText("");
                        String difyConvId = node.path("conversation_id").asText("");
                        // 本地化 Dify 工具文件 URL（下载到本地 + 改写消息内容）：签名 URL 有时效，
                        // 过期后前端刷新即裂图；落库与返回前必须替换为 /api/files/images/ 永久 URL
                        String localized = localizeDifyFileUrls(answer.toString());
                        // Z1：difyConversationId 回填并入 persistAssistant 同一事务，与 assistant 落库原子完成
                        persistAssistant(conversation, localized, messageId, difyConvId);
                        // I4：sceneCount 查询失败降级为 -1，仍照常发送 message_end（不升级为 error）
                        long sceneCount = -1;
                        try {
                            sceneCount = sceneMapper.selectCount(
                                new LambdaQueryWrapper<com.storyboard.entity.Scene>()
                                    .eq(com.storyboard.entity.Scene::getProjectId, conversation.getProjectId()));
                        } catch (Exception e) {
                            log.warn("查询场景数失败，sceneCount 降级为 -1: conversationId={}, error={}",
                                    conversation.getId(), e.getMessage());
                        }
                        // content 随 message_end 返回：前端用本地化后的完整文本覆盖流式占位气泡，
                        // 流结束瞬间 UI 即显示永久本地 URL（无需等刷新重拉消息）
                        sendEvent(emitter, "message_end",
                                messageEndPayload(conversation, messageId, sceneCount, localized));
                        if (!deferComplete && !autoGenerate.get()) emitter.complete();
                        return;
                    }
                    // Dify 恢复事件流（/v1/workflow/{taskId}/events）特有事件：与 chat-messages 流
                    // （message/message_end/human_input_required）不同，恢复流用 text_chunk 携带文本、
                    // workflow_paused 表示再次暂停、workflow_finished 收尾（无 message_end）
                    case "text_chunk" -> {
                        // 恢复流文本增量（对应 chat-messages 流的 message 事件）
                        String text = node.path("data").path("text").asText("");
                        if (!text.isEmpty()) {
                            answer.append(text);
                            sendEvent(emitter, "message", Map.of("content", text));
                        }
                    }
                    case "workflow_paused" -> {
                        // 恢复流中再次遇到 HITL 暂停：表单定义在 data.reasons[0]
                        // （结构与 chat-messages 流的 human_input_required 不同），
                        // 转发前端确认卡片后结束本轮流，等待下一次表单提交
                        JsonNode reasons = node.path("data").path("reasons");
                        if (reasons.isArray() && !reasons.isEmpty()) {
                            JsonNode reason = reasons.get(0);
                            List<Map<String, String>> actions = new ArrayList<>();
                            for (JsonNode a : reason.path("actions")) {
                                actions.add(Map.of("id", a.path("id").asText(""), "title", a.path("title").asText("")));
                            }
                            cacheFormSnapshot(reason.path("form_token").asText(""),
                                    reason.path("form_content").asText(""), actions, conversation);
                            sendEvent(emitter, "human_input", Map.of(
                                "formToken", reason.path("form_token").asText(""),
                                // 同 chat-messages 流：workflow_paused 顶层 workflow_run_id 才是续流所需
                                "taskId", node.path("workflow_run_id").asText(""),
                                "formContent", reason.path("form_content").asText(""),
                                "actions", actions,
                                "expirationTime", reason.path("expiration_time").asLong(0)));
                            // HITL 暂停：方案文本落库（formContent 优先，同 chat-messages 流）
                            String pausedFormContent = reason.path("form_content").asText("");
                            persistAssistant(conversation,
                                    !pausedFormContent.isBlank() ? pausedFormContent : answer.toString(), null, null);
                            if (!deferComplete && !autoGenerate.get()) emitter.complete();
                            return;
                        }
                    }
                    case "workflow_finished" -> {
                        // 恢复流结束事件（无 message_end）：成功/失败统一在此收尾
                        String finishedStatus = node.path("data").path("status").asText("");
                        // 最终回答可能只在 data.outputs.answer（text_chunk 未覆盖时补发）
                        String outputsAnswer = node.path("data").path("outputs").path("answer").asText("");
                        if (!outputsAnswer.isEmpty() && !answer.toString().endsWith(outputsAnswer)) {
                            answer.append(outputsAnswer);
                            sendEvent(emitter, "message", Map.of("content", outputsAnswer));
                        }
                        if ("failed".equals(finishedStatus)) {
                            String err = node.path("data").path("error").asText("");
                            // 失败也落库已累积的回复文本（用户消息后的部分回答不丢）
                            if (answer.length() > 0) {
                                persistAssistant(conversation, localizeDifyFileUrls(answer.toString()), null, null);
                            }
                            log.warn("Dify 恢复工作流失败: taskId={}, error={}",
                                    node.path("task_id").asText(""), err);
                            // 透传工作流真实错误（截断防刷屏），便于前端/用户定位 Dify 工作流问题
                            // （LLM structured_output 偶发类型错误等场景，笼统"服务异常"无法排查）
                            String readable = (err == null || err.isBlank())
                                ? "Dify 服务异常，请稍后重试"
                                : "Dify 工作流执行失败：" + (err.length() > 150 ? err.substring(0, 150) + "…" : err);
                            sendEvent(emitter, "error", Map.of("code", "50202", "message", readable));
                            if (!deferComplete && !autoGenerate.get()) emitter.complete();
                            return;
                        }
                        // succeeded/stopped：本地化 + 落库 + 回填，复用 message_end 语义收尾
                        String finishedMessageId = node.path("task_id").asText("");
                        String localized = localizeDifyFileUrls(answer.toString());
                        // HITL 方案合并：若存在待合并的 HITL 方案消息（difyMessageId=null，
                        // human_input_required 时落库的 formContent），message_end 返回「方案 + 最终回答」
                        // 合并后的完整内容——否则前端用最终回答整体覆盖占位气泡时方案会消失。
                        // 数据库侧 persistAssistant 的 I2 合并拼出相同结果，两端一致。
                        String mergedForClient = mergedHitlContent(conversation.getId(), localized);
                        persistAssistant(conversation, localized, finishedMessageId, null);
                        long sceneCount = -1;
                        try {
                            sceneCount = sceneMapper.selectCount(
                                new LambdaQueryWrapper<com.storyboard.entity.Scene>()
                                    .eq(com.storyboard.entity.Scene::getProjectId, conversation.getProjectId()));
                        } catch (Exception e) {
                            log.warn("查询场景数失败，sceneCount 降级为 -1: conversationId={}, error={}",
                                    conversation.getId(), e.getMessage());
                        }
                        sendEvent(emitter, "message_end",
                                messageEndPayload(conversation, finishedMessageId, sceneCount, mergedForClient));
                        if (!deferComplete && !autoGenerate.get()) emitter.complete();
                        return;
                    }
                    case "error" -> {
                        sendEvent(emitter, "error", Map.of(
                            "code", node.path("code").asText("50202"),
                            "message", "Dify 服务异常，请稍后重试"));
                        if (!deferComplete && !autoGenerate.get()) emitter.complete();
                        return;
                    }
                    default -> { /* ping 等忽略 */ }
                }
            }
            // 流正常 EOF（无 message_end 的兜底）——取消时不落库（I1）
            if (!cancel.get() && answer.length() > 0) persistAssistant(conversation, answer.toString(), null, null);
            if (!cancel.get() && !deferComplete && !autoGenerate.get()) emitter.complete();
        } catch (Exception e) {
            // I1：客户端已断开时不再补发 error/complete（emitter 已被容器关闭）
            if (cancel.get()) {
                log.debug("SSE 已取消，忽略读取异常: conversationId={}", conversation.getId());
                return;
            }
            log.error("Dify SSE 读取失败: conversationId={}", conversation.getId(), e);
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
            if (!deferComplete && !autoGenerate.get()) emitter.complete();
        }
    }

    /**
     * 完善图片自动生成（无 HITL 信号触发）。
     *
     * 触发点：Dify 工作流「后端执行识别图片加人工介入流程」answer 节点 node_finished。
     * 流程：视觉模型（gemini-3-flash-preview）看图 + 用户诉求 → refined_prompt →
     * {@link AgentGenerationService#generateImage}（mode=edit 图生图）→ 推图 + 确认卡片。
     *
     * 源图与诉求来源：
     * - 源图：lastPicUrlByConversation（本轮消息携带的 PicUrl，完善路径必带图）；
     * - 诉求：最近一条 user 消息内容（streamMessage 已落库，等价 sys.query）。
     *
     * 线程模型：运行在 agentExecutor 虚拟线程，不阻塞 Dify SSE 读循环；
     * 完成后 complete emitter（forwardDifySse 中 autoGenerate 标志已阻止流提前关闭）。
     */
    private void triggerAutoImageRefine(AgentConversation conversation, SseEmitter emitter,
                                        AtomicBoolean cancel) {
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 源图：本轮 PicUrl（无图则无法图生图，降级为仅提示）
                String source = lastPicUrlByConversation.get(conversation.getId());
                if (source == null || source.isBlank()) {
                    log.warn("完善图片自动生成跳过：无源图 PicUrl, conversationId={}", conversation.getId());
                    sendEvent(emitter, "error", Map.of("code", "40001", "message", "未检测到参考图片，请重新发送图片后继续"));
                    return;
                }

                // 2. 用户诉求：最近一条 user 消息（streamMessage 已落库）
                String userRequest = "";
                var lastUser = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getConversationId, conversation.getId())
                        .eq(AgentMessage::getRole, "user")
                        .orderByDesc(AgentMessage::getCreatedAt)
                        .last("LIMIT 1"));
                if (lastUser != null && !lastUser.isEmpty()) {
                    userRequest = lastUser.get(0).getContent();
                }
                if (userRequest == null || userRequest.isBlank()) {
                    log.warn("完善图片自动生成跳过：无用户诉求, conversationId={}", conversation.getId());
                    sendEvent(emitter, "error", Map.of("code", "40001", "message", "未获取到你的修改诉求，请重新描述后继续"));
                    return;
                }

                // 3. 视觉模型看图 + 诉求 → refined_prompt
                sendEvent(emitter, "workflow", Map.of("title", "视觉理解图片", "status", "node_started"));
                String refinedPrompt = imageRefinePromptService.buildRefinedPrompt(source, userRequest);
                if (refinedPrompt == null || refinedPrompt.isBlank()) {
                    throw new RuntimeException("视觉理解未生成有效的改图提示词");
                }
                log.info("完善图片自动生成：refined_prompt 前 120 字: {}", refinedPrompt.length() > 120
                        ? refinedPrompt.substring(0, 120) + "…" : refinedPrompt);

                // 3.5 把优化后的改图提示词展示到对话（用户可见 AI 理解成什么样、准备怎么改），
                //     并落库（刷新后不丢）。随后才执行图生图。
                String promptMsg = "📝 优化后的改图提示词：\n" + refinedPrompt;
                sendEvent(emitter, "message", Map.of("content", promptMsg));
                if (conversation != null) {
                    persistAssistant(conversation, promptMsg, null, null);
                }

                // 4. 图生图（sceneId=null → 落 agent_assets）
                Map<String, String> result = generationService.generateImage(
                        conversation, null, refinedPrompt, null, null, "edit", null, source);

                // 5. 推图 + 看图确认卡片
                pushGenerationResult(emitter, "image", result.get("imageUrl"),
                        result.get("assetId"), 0, true, conversation);
                log.info("完善图片自动生成完成: conversationId={}, imageUrl={}", conversation.getId(), result.get("imageUrl"));
            } catch (Exception e) {
                // 客户端已断开则不再补发错误（emitter 已被容器关闭）
                if (cancel.get()) return;
                log.error("完善图片自动生成失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "图片完善失败，请稍后重试"));
            } finally {
                if (!cancel.get()) emitter.complete();
            }
        }, agentExecutor);
    }

    /**
     * 图生视频方案自动设计（无 HITL 信号触发）。
     *
     * 触发点：Dify 工作流「后端执行图生视频方案设计」answer 节点 node_finished（「视频类型分流」
     * 判断携带参考图后进入）。流程：视觉模型（gemini-3-flash-preview）看图 + 用户诉求 →
     * 视频方案（message/duration）→ 缓存 planToken 快照 → 推 video_plan 事件 →
     * 前端确认卡片「开始生成视频」→ {@link #generateVideoFromPlan} 生成 MiniMax 图生视频。
     *
     * 源图与诉求来源（同完善图片路径）：
     * - 源图：lastPicUrlByConversation（本轮消息携带的 PicUrl，图生视频必带图）；
     * - 诉求：最近一条 user 消息内容（streamMessage 已落库，等价 sys.query）。
     *
     * 线程模型：运行在 agentExecutor 虚拟线程，不阻塞 Dify SSE 读循环；
     * 完成后 complete emitter（forwardDifySse 中 autoGenerate 标志已阻止流提前关闭）。
     */
    private void triggerAutoVideoPlan(AgentConversation conversation, SseEmitter emitter,
                                      AtomicBoolean cancel) {
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 源图：本轮 PicUrl（无图则无法图生视频，降级为仅提示）
                String source = lastPicUrlByConversation.get(conversation.getId());
                if (source == null || source.isBlank()) {
                    log.warn("图生视频方案设计跳过：无源图 PicUrl, conversationId={}", conversation.getId());
                    sendEvent(emitter, "error", Map.of("code", "40001", "message", "未检测到参考图片，请重新发送图片后继续"));
                    return;
                }

                // 2. 用户诉求：最近一条 user 消息（streamMessage 已落库）
                String userRequest = "";
                var lastUser = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getConversationId, conversation.getId())
                        .eq(AgentMessage::getRole, "user")
                        .orderByDesc(AgentMessage::getCreatedAt)
                        .last("LIMIT 1"));
                if (lastUser != null && !lastUser.isEmpty()) {
                    userRequest = lastUser.get(0).getContent();
                }

                // 3. 视觉模型看图 + 诉求 → 视频方案（prompt + 时长）
                sendEvent(emitter, "workflow", Map.of("title", "视觉理解图片", "status", "node_started"));
                VideoPlanService.VideoPlan plan = videoPlanService.buildVideoPlan(source, userRequest);

                // 4. 缓存一次性 planToken 快照（消费即移除，防重放；TTL 30min）
                String planToken = UUID.randomUUID().toString();
                videoPlanSnapshots.put(planToken, new VideoPlanSnapshot(
                        conversation.getId(), conversation.getProjectId(),
                        plan.message(), plan.duration(), source, System.currentTimeMillis()));
                log.info("图生视频方案已缓存: planToken={}, conversationId={}, duration={}",
                        planToken, conversation.getId(), plan.duration());

                // 5. 方案文本落库 + 推 video_plan 事件（前端确认卡片）
                String planMsg = "📹 结合你上传的参考图，为你设计了视频方案：\n" + plan.message()
                        + "\n（时长 " + plan.duration() + " 秒）";
                sendEvent(emitter, "message", Map.of("content", planMsg));
                persistAssistant(conversation, planMsg, null, null);
                sendEvent(emitter, "video_plan", Map.of(
                        "planToken", planToken,
                        "message", plan.message(),
                        "duration", plan.duration(),
                        "picUrl", source,
                        "actions", List.of(
                                Map.of("id", "generate_video", "title", "开始生成视频"),
                                Map.of("id", "refine", "title", "继续完善"))));
                log.info("图生视频方案设计完成: conversationId={}", conversation.getId());
            } catch (Exception e) {
                // 客户端已断开则不再补发错误（emitter 已被容器关闭）
                if (cancel.get()) return;
                log.error("图生视频方案设计失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "视频方案设计失败，请稍后重试"));
            } finally {
                if (!cancel.get()) emitter.complete();
            }
        }, agentExecutor);
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
    private void persistAssistant(AgentConversation conversation, String content,
                                  String difyMessageId, String difyConversationId) {
        if (content == null || content.isBlank()) return;
        // 幂等本地化：已本地化的 content 不含 /files/tools/，原样返回。
        // 覆盖 HITL 暂停落库（human_input_required）与流 EOF 兜底两条路径。
        // 注意：必须用 final 局部变量，否则 lambda 内引用会编译失败（非 effectively final）
        final String localizedContent = localizeDifyFileUrls(content);
        transactionTemplate.executeWithoutResult(tx -> {
            if (difyConversationId != null && !difyConversationId.isBlank()
                    && !difyConversationId.equals(conversation.getDifyConversationId())) {
                conversation.setDifyConversationId(difyConversationId);
            }
            // I2：查会话最后一条 assistant 消息（HITL 暂停时落库的"未完成"消息 difyMessageId 为 null）
            AgentMessage last = messageMapper.selectOne(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversation.getId())
                .eq(AgentMessage::getRole, "assistant")
                .orderByDesc(AgentMessage::getCreatedAt)
                .last("LIMIT 1"));
            if (last != null && last.getDifyMessageId() == null) {
                // 续流合并：追加内容 + 回填 messageId
                last.setContent(last.getContent() + localizedContent);
                last.setDifyMessageId(difyMessageId);
                messageMapper.updateById(last);
            } else {
                AgentMessage assistantMessage = new AgentMessage();
                assistantMessage.setConversationId(conversation.getId());
                assistantMessage.setRole("assistant");
                assistantMessage.setContent(localizedContent);
                assistantMessage.setDifyMessageId(difyMessageId);
                messageMapper.insert(assistantMessage);
            }
            conversationMapper.updateById(conversation); // 触发 updatedAt fill + 持久化回填
        });
    }

    /**
     * 合并 HITL 方案与最终回答：查会话最后一条 difyMessageId 为 null 的 assistant 消息
     * （human_input_required / workflow_paused 时落库的方案文本），存在则拼「方案 + 新增内容」返回，
     * 与 persistAssistant 的 I2 合并结果一致；不存在（非 HITL 路径）原样返回新增内容。
     */
    private String mergedHitlContent(String conversationId, String additional) {
        AgentMessage pending = messageMapper.selectOne(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .eq(AgentMessage::getRole, "assistant")
                .isNull(AgentMessage::getDifyMessageId)
                .orderByDesc(AgentMessage::getCreatedAt)
                .last("LIMIT 1"));
        if (pending != null && pending.getContent() != null && !pending.getContent().isBlank()) {
            return pending.getContent() + additional;
        }
        return additional;
    }

    // ============ 智能体生成后端化：HITL 方案快照缓存 ============

    /** 表单快照 TTL（与 Dify form_token 过期时间对齐，30 分钟） */
    private static final long FORM_SNAPSHOT_TTL_MS = 30 * 60 * 1000L;
    /** formToken → 表单快照 */
    private final Map<String, FormSnapshot> formSnapshots = new ConcurrentHashMap<>();
    /** conversationId → 最近 LLM 节点输出（node_finished 捕获的 outputs） */
    private final Map<String, Map<String, Object>> lastNodeOutputs = new ConcurrentHashMap<>();
    /** conversationId → 最近一次消息携带的 PicUrl（图改图兜底：node_finished 不带 code 节点 outputs，
     *  plan 无 picture；本字段在 streamMessage 按轮更新/清空，仅当轮生效） */
    private final Map<String, String> lastPicUrlByConversation = new ConcurrentHashMap<>();
    /** conversationId → 最近一次 HITL 表单文案（快照缺失时降级生成兜底，TTL 同快照） */
    private final Map<String, LastFormContent> lastFormContentByConversation = new ConcurrentHashMap<>();

    /** 会话维度 formContent 兜底记录 */
    private record LastFormContent(String content, long createdAt) {
        boolean expired() { return System.currentTimeMillis() - createdAt > FORM_SNAPSHOT_TTL_MS; }
    }

    /**
     * 图生视频方案快照（video_plan 事件的一次性凭据）。
     * 触发点：Dify 信号节点「后端执行图生视频方案设计」→ 视觉模型设计视频方案 →
     * 生成 planToken 缓存本快照 → 推 video_plan 事件（携 planToken）→ 前端确认卡片
     * 「开始生成视频」→ 按 planToken 取用（消费即移除，防重放）。TTL 同表单快照 30min。
     */
    private record VideoPlanSnapshot(String conversationId, String projectId, String message,
                                     Integer duration, String source, long createdAt) {
        boolean expired() { return System.currentTimeMillis() - createdAt > FORM_SNAPSHOT_TTL_MS; }
    }

    /** planToken → 图生视频方案快照 */
    private final Map<String, VideoPlanSnapshot> videoPlanSnapshots = new ConcurrentHashMap<>();

    /** 取图生视频方案快照（消费即移除；过期同样移除返回 null） */
    private VideoPlanSnapshot takeVideoPlanSnapshot(String planToken) {
        if (planToken == null || planToken.isBlank()) return null;
        VideoPlanSnapshot snap = videoPlanSnapshots.remove(planToken);
        if (snap == null) return null;
        if (snap.expired()) return null;
        return snap;
    }

    /**
     * HITL 表单快照：用户点"确认"时后端需要的一切。
     * formContent = 确认卡片文案（含完整方案文本）；plan = 最近 LLM 节点结构化输出
     * （分镜 items / 图片 message+style+size / 视频方案），缺失时降级用 formContent。
     */
    public record FormSnapshot(String formContent, List<Map<String, String>> actions,
                               Map<String, Object> plan, String conversationId,
                               String projectId, long createdAt) {
        boolean expired() { return System.currentTimeMillis() - createdAt > FORM_SNAPSHOT_TTL_MS; }
    }

    /** 缓存表单快照（human_input_required / workflow_paused 转发前调用） */
    private void cacheFormSnapshot(String formToken, String formContent,
                                   List<Map<String, String>> actions, AgentConversation conversation) {
        if (formToken == null || formToken.isBlank()) return;
        // §4.3 快照缺失兜底：formSnapshots 按 formToken 缓存，服务重启/过期都会丢失；额外按会话维度
        // 冗余记录最近一次 formContent（TTL 同快照），提交时快照缺失可据此构造降级快照（plan=null，
        // formContent 作 prompt）继续 generate_image/generate_video
        if (formContent != null && !formContent.isBlank()) {
            lastFormContentByConversation.put(conversation.getId(),
                    new LastFormContent(formContent, System.currentTimeMillis()));
        }
        Map<String, Object> plan = lastNodeOutputs.get(conversation.getId());
        formSnapshots.put(formToken, new FormSnapshot(formContent, actions, plan,
                conversation.getId(), conversation.getProjectId(), System.currentTimeMillis()));
        log.info("已缓存 HITL 方案快照: formToken={}, conversationId={}, planKeys={}", formToken,
                conversation.getId(), plan != null ? plan.keySet() : "null");
    }

    /**
     * 取表单快照（消费即移除）：取到即从缓存删除，防止同一 formToken 重复提交/重放；
     * 过期快照同样移除并返回 null。无快照返回 null（调用方降级为仅续流不生成）。
     */
    private FormSnapshot takeFormSnapshot(String formToken) {
        FormSnapshot snap = formSnapshots.remove(formToken);
        if (snap == null) return null;
        if (snap.expired()) return null;
        return snap;
    }

    /** 生成中/完成的工作流进度事件（title 供前端展示生成阶段） */
    private static final Map<String, String> GENERATION_STAGE_LABELS = Map.of(
        "script", "正在生成分镜…",
        "image", "正在生成图片…",
        "video", "正在生成视频…"
    );

    /**
     * 表单提交后按 action 分发生成（生成后端化核心）。
     * 工作流方案确认节点的 action id 约定：
     *   agree         → 分镜写库（人工介入 3 的"满意"）
     *   generate_image → 生图（图片方案确认的"生成图片"）
     *   generate_video → 生视频（视频方案确认的"开始生成视频"）
     *   refine        → 不触发生成（前端带 PicUrl 发消息走 Dify 完善分支）
     * 返回异步任务；调用方 await 后 complete emitter。
     */
    private CompletableFuture<Void> dispatchGeneration(FormSnapshot snapshot, String action,
                                                       SseEmitter emitter) {
        return CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> plan = snapshot.plan() != null ? snapshot.plan() : Map.of();
                switch (action) {
                    case "agree" -> {
                        // 分镜写库：plan 里取 items（宽松：Map 顶层 items / structured_output.items / List / String JSON）
                        sendEvent(emitter, "workflow", Map.of("title", GENERATION_STAGE_LABELS.get("script"), "status", "node_started"));
                        List<DifyGenerateScriptRequest.SceneItem> scenes = parseScenes(plan);
                        int count = generationService.writeScript(snapshot.projectId(), scenes);
                        String msg = count > 0
                            ? "✅ 分镜方案已确认，已生成 **" + count + " 个分镜**，请查看左侧分镜列表"
                            : "⚠ 分镜方案已确认，但未解析到分镜内容，请重新描述需求";
                        sendEvent(emitter, "message", Map.of("content", msg));
                        // §4.3：确认结果消息落库（刷新后历史消息不丢）；会话可能已删（conversationOf 为 null），判空
                        AgentConversation agreeConv = conversationOf(snapshot);
                        if (agreeConv != null) persistAssistant(agreeConv, msg, null, null);
                        sendEvent(emitter, "confirm_result", Map.of(
                            "kind", "script", "sceneCount", count, "url", "", "actions", List.of()));
                    }
                    case "generate_image" -> {
                        sendEvent(emitter, "workflow", Map.of("title", GENERATION_STAGE_LABELS.get("image"), "status", "node_started"));
                        // mode/edit 与源图：完善路径（快照有 picture 且 mode=edit）走图改图；缺源图时 mode 置 null 降级文生图。
                        // 兜底：node_finished 不带 code 节点 outputs（plan 无 picture）——用本消息轮次携带的
                        // PicUrl（lastPicUrlByConversation，streamMessage 按轮更新）作源图
                        String source = plan.get("picture") instanceof String p && !p.isBlank() ? p
                                : lastPicUrlByConversation.get(snapshot.conversationId());
                        String mode = "edit".equals(plan.get("mode")) && source != null ? "edit"
                                : (source != null ? "edit" : null);
                        // §4.3：降级快照 plan 为 null 时用 formContent 文本兜底作 prompt（快照缺失降级生成）
                        String prompt = str(plan.get("message"));
                        if (prompt == null) prompt = snapshot.formContent();
                        AgentConversation conv = conversationOf(snapshot); // 可能为 null（会话已删），生成服务/落库各自判空
                        Map<String, String> result = generationService.generateImage(
                            conv, null, prompt, str(plan.get("model")), str(plan.get("size")),
                            mode, null, source);
                        pushGenerationResult(emitter, "image", result.get("imageUrl"),
                            result.get("assetId"), 0, true, conv);
                    }
                    case "generate_video" -> {
                        // 源图兜底同 generate_image（图生视频依赖当轮 PicUrl）
                        String source = plan.get("picture") instanceof String p && !p.isBlank() ? p
                                : lastPicUrlByConversation.get(snapshot.conversationId());
                        // §4.3：降级快照 plan 为 null 时用 formContent 文本兜底作 prompt（快照缺失降级生成）
                        String prompt = str(plan.get("message"));
                        if (prompt == null) prompt = snapshot.formContent();
                        AgentConversation conv = conversationOf(snapshot);
                        // aspectRatio 透传（2026-08-07 修复：此前写死 null，文生视频 9:16/16:9 选择
                        // 恒被 MiniMax 降级 16:9；图生视频传 null 无妨，恒 adaptive 自动匹配原图）
                        executeVideoGeneration(conv, prompt, str(plan.get("duration")),
                                str(plan.get("aspectRatio")), source, emitter);
                    }
                    default -> log.info("action={} 不触发生成（refine/其他），由 Dify 继续完善", action);
                }
            } catch (Exception e) {
                log.error("Agent 生成分发失败: action={}, error={}", action, e.getMessage(), e);
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "生成失败，请稍后重试"));
            } finally {
                // 编排裁定：分发完成后清理该会话的 LLM 节点输出缓存与 formContent 兜底缓存，防止内存滞留
                lastNodeOutputs.remove(snapshot.conversationId());
                lastFormContentByConversation.remove(snapshot.conversationId());
            }
        }, agentExecutor);
    }

    /** 取会话（快照仅存 id，重新查库；查不到返回 null 由调用方降级） */
    private AgentConversation conversationOf(FormSnapshot snapshot) {
        return conversationMapper.selectById(snapshot.conversationId());
    }

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
        if (conversation != null) persistAssistant(conversation, content, null, null);
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
        // 生成视频后清空 Dify 会话 picture 全局变量：图已作为首帧提交，Dify 侧 picture 使命完成。
        // 否则用户在等待生成期间继续对话，「视频类型分流」会误判旧图仍有效（复用
        // confirmImageDone 的 GET variables + PUT 模式；失败仅 warn 不影响生成结果）
        clearDifyVariable(conv, "picture");
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
        // I1：注册 SseEmitter 断开/超时/异常回调（同 streamMessage / submitFormAndResume）
        AtomicBoolean cancel = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancel.set(true));
        emitter.onTimeout(() -> cancel.set(true));
        emitter.onError(ignored -> cancel.set(true));
        CompletableFuture.runAsync(() -> {
            try {
                VideoPlanSnapshot snap = takeVideoPlanSnapshot(planToken);
                if (snap == null || !conversationId.equals(snap.conversationId())) {
                    log.warn("图生视频方案快照无效或已过期: conversationId={}, planToken={}", conversationId, planToken);
                    sendEvent(emitter, "error", Map.of("code", "40001", "message", "视频方案已过期，请重新上传图片生成"));
                    return;
                }
                // 确认动作落库为用户消息（独立事务立即提交，刷新/历史可见）
                persistUserConfirmation(conversation, "开始生成视频", null);
                executeVideoGeneration(conversation, snap.message(), String.valueOf(snap.duration()),
                        null, snap.source(), emitter);
                log.info("图生视频生成完成: conversationId={}, duration={}", conversationId, snap.duration());
            } catch (Exception e) {
                // I1：客户端已断开时不再补发 error/complete（emitter 已被容器关闭）
                if (cancel.get()) return;
                log.error("图生视频生成失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "视频生成失败，请稍后重试"));
            } finally {
                if (!cancel.get()) emitter.complete();
            }
        }, agentExecutor);
    }

    /**
     * 清空 Dify 会话变量（生成后清理：视频生成后清 picture，防止分流误判旧图）。
     * 复用 {@link #confirmImageDone} 的 GET variables + PUT 模式；失败仅 warn（清理是附加动作，
     * 不影响已创建的视频任务与推送结果）。变量不存在（工作流未部署/改名）视为已清空。
     *
     * ⚠ Dify 1.16.1 PG bug：GET 不能带 variable_name 过滤参数（PG 分支 json_extract_path_text
     * 作用于 text 列不 cast 必 500），必须不带参数拉全量后本地遍历匹配 name。
     */
    private void clearDifyVariable(AgentConversation conversation, String variableName) {
        String difyId = conversation.getDifyConversationId();
        if (difyId == null || difyId.isBlank()) {
            log.info("清空 Dify 变量跳过：会话尚无 Dify 会话(difyConversationId 为空): conversationId={}",
                    conversation.getId());
            return;
        }
        try {
            // 1. 查变量列表，定位 variable_id（GET 的 user 走 query 参数；不带 variable_name 过滤——PG bug）
            HttpRequest listReq = HttpRequest.newBuilder()
                .uri(URI.create(config.getDifyBaseUrl() + "/v1/conversations/"
                    + URLEncoder.encode(difyId, StandardCharsets.UTF_8)
                    + "/variables?user=" + URLEncoder.encode(conversation.getUserId(), StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + config.getDifyApiKey())
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            HttpResponse<String> listResp = httpClient.send(listReq, HttpResponse.BodyHandlers.ofString());
            if (listResp.statusCode() != 200) {
                log.warn("Dify 查询会话变量失败(忽略): status={}, body={}", listResp.statusCode(), listResp.body());
                return;
            }
            String variableId = null;
            JsonNode data = objectMapper.readTree(listResp.body()).path("data");
            if (data.isArray()) {
                for (JsonNode item : data) {
                    if (variableName.equals(item.path("name").asText())) {
                        variableId = item.path("id").asText(null);
                        break;
                    }
                }
            }
            if (variableId == null || variableId.isBlank()) {
                log.info("Dify 会话变量 {} 不存在，视为已清空: difyId={}", variableName, difyId);
                return;
            }
            // 2. 重置为空字符串（PUT 的 user 走 JSON body；Dify 源码 WhereisUserArg.JSON）
            HttpRequest updateReq = HttpRequest.newBuilder()
                .uri(URI.create(config.getDifyBaseUrl() + "/v1/conversations/"
                    + URLEncoder.encode(difyId, StandardCharsets.UTF_8)
                    + "/variables/" + URLEncoder.encode(variableId, StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getDifyApiKey())
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                    "value", "",
                    "user", conversation.getUserId()))))
                .build();
            HttpResponse<String> updateResp = httpClient.send(updateReq, HttpResponse.BodyHandlers.ofString());
            if (updateResp.statusCode() != 200) {
                log.warn("Dify 清空会话变量 {} 失败(忽略): status={}, body={}",
                        variableName, updateResp.statusCode(), updateResp.body());
                return;
            }
            log.info("已清空 Dify 会话变量 {}: conversationId={}, difyId={}",
                    variableName, conversation.getId(), difyId);
        } catch (Exception e) {
            log.warn("清空 Dify 会话变量 {} 异常(忽略): conversationId={}, error={}",
                    variableName, conversation.getId(), e.getMessage());
        }
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

    /** 宽松解析 scenes：Map（顶层 items / structured_output.items / structured_output 数组）/ List / String JSON / null → 空列表（绝不抛） */
    @SuppressWarnings("unchecked")
    private List<DifyGenerateScriptRequest.SceneItem> parseScenes(Object raw) {
        if (raw == null) return List.of();
        try {
            if (raw instanceof List<?> list) {
                return objectMapper.convertValue(list,
                    new com.fasterxml.jackson.core.type.TypeReference<List<DifyGenerateScriptRequest.SceneItem>>() {});
            }
            if (raw instanceof String s && !s.isBlank() && !s.contains("{{#")) {
                Object parsed = objectMapper.readValue(s, Object.class);
                if (parsed instanceof List<?> list) {
                    return objectMapper.convertValue(list,
                        new com.fasterxml.jackson.core.type.TypeReference<List<DifyGenerateScriptRequest.SceneItem>>() {});
                }
                if (parsed instanceof java.util.Map<?, ?> m && m.get("items") instanceof List<?> items) {
                    return objectMapper.convertValue(items,
                        new com.fasterxml.jackson.core.type.TypeReference<List<DifyGenerateScriptRequest.SceneItem>>() {});
                }
            }
            if (raw instanceof java.util.Map<?, ?> m) {
                // Dify node_finished outputs 为 LLM 完整输出 {text, structured_output:{items}}——
                // structured_output 偶发解析坏（=schema/空数组/json_repair 脏 JSON），text 永远干净，
                // 优先解析 text（LLM 原始 JSON 文本）；其次 structured_output
                Object text = m.get("text");
                if (text instanceof String ts && !ts.isBlank() && !ts.contains("{{#")) {
                    Object parsed = objectMapper.readValue(ts, Object.class);
                    if (parsed instanceof List<?> list) {
                        return objectMapper.convertValue(list,
                            new com.fasterxml.jackson.core.type.TypeReference<List<DifyGenerateScriptRequest.SceneItem>>() {});
                    }
                    if (parsed instanceof java.util.Map<?, ?> pm && pm.get("items") instanceof List<?> items) {
                        return objectMapper.convertValue(items,
                            new com.fasterxml.jackson.core.type.TypeReference<List<DifyGenerateScriptRequest.SceneItem>>() {});
                    }
                }
                Object items = m.get("items");
                if (items instanceof List<?> list) {
                    return objectMapper.convertValue(list,
                        new com.fasterxml.jackson.core.type.TypeReference<List<DifyGenerateScriptRequest.SceneItem>>() {});
                }
                Object so = m.get("structured_output");
                if (so instanceof java.util.Map<?, ?> som && som.get("items") instanceof List<?> list) {
                    return objectMapper.convertValue(list,
                        new com.fasterxml.jackson.core.type.TypeReference<List<DifyGenerateScriptRequest.SceneItem>>() {});
                }
                if (so instanceof List<?> list) {
                    return objectMapper.convertValue(list,
                        new com.fasterxml.jackson.core.type.TypeReference<List<DifyGenerateScriptRequest.SceneItem>>() {});
                }
            }
            return List.of();
        } catch (Exception e) {
            log.warn("解析分镜 scenes 失败, 降级为空列表: {}", e.getMessage());
            return List.of();
        }
    }

    /** 快照 plan 取值辅助：null 安全 + String 化 */
    private static String str(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s.isBlank() ? null : s;
        return String.valueOf(v);
    }

    /**
     * 把消息内容中的 Dify 工具文件 URL（/files/tools/ 签名 URL）下载到本地并改写为
     * /api/files/images/xxx.png 永久 URL。
     *
     * 背景：Dify 工作流 LLM 输出引用的图片/视频 URL 是 Dify 内部文件服务的带时效签名 URL
     * （?timestamp=...&nonce=...&sign=...），过期后（默认数分钟~数小时）访问返回 403，
     * 而消息内容已持久化到 agent_messages —— 刷新重放后前端必然裂图。
     * 必须在生成后（签名仍有效）立即下载落盘，替换为本地永久 URL。
     *
     * 下载失败（签名已过期/网络异常）时保留原 URL 不阻塞消息落库，由前端 onError 兜底降级显示。
     */
    private String localizeDifyFileUrls(String content) {
        if (content == null || !content.contains("/files/tools/")) return content;
        Matcher matcher = DIFY_TOOLS_URL_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String url = matcher.group();
            try {
                String local = fileStorageService.saveImage(url);
                log.info("Dify 工具文件已本地化: {} -> {}", url, local);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(local));
            } catch (Exception e) {
                // 保留原 URL：前端 img onError 会降级为"图片已过期"占位，不阻塞落库
                log.warn("Dify 工具文件本地化失败(签名可能已过期), 保留原 URL: {}，原因: {}",
                        url, e.getMessage());
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * HITL 表单提交并续流：
     * 1. POST {base}/v1/form/human_input/{formToken}（body {action}）
     * 2. 成功 → GET {base}/v1/workflow/{taskId}/events?user={userId} 续传 SSE（复用 forwardDifySse）
     */
    public void submitFormAndResume(String userId, String conversationId, String formToken, String taskId, String action, SseEmitter emitter) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        // I1：注册 SseEmitter 断开/超时/异常回调（同 streamMessage，语义见上）
        AtomicBoolean cancel = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancel.set(true));
        emitter.onTimeout(() -> cancel.set(true));
        emitter.onError(ignored -> cancel.set(true));
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest submitReq = HttpRequest.newBuilder()
                    // C4：formToken 属不可信输入，拼 URL 前做 UTF-8 百分号编码，避免特殊字符破坏路径
                    .uri(URI.create(config.getDifyBaseUrl() + "/v1/form/human_input/"
                        + URLEncoder.encode(formToken, StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getDifyApiKey())
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of(
                            "action", action,
                            // Dify 源码（controllers/common/human_input.py）：HumanInputFormSubmitPayload
                            // 必填字段为 inputs(dict) + action；Moon 工作流 HITL 表单均为纯按钮型
                            // （工作流定义 inputs: []），提交空对象即可，缺字段会 400 invalid_param
                            "inputs", Map.of(),
                            // user 一并携带：Dify 部分端点从 JSON body 获取且必填（fetch_from=JSON, required=True）
                            "user", userId))))
                    .build();
                HttpResponse<String> submitResp = httpClient.send(submitReq, HttpResponse.BodyHandlers.ofString());
                if (submitResp.statusCode() != 200) {
                    log.error("Dify 表单提交失败: status={}, body={}", submitResp.statusCode(), submitResp.body());
                    sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                    emitter.complete();
                    return;
                }
                // 生成后端化：表单提交成功即按快照 + action 分发生成（与 Dify 续流并行）
                FormSnapshot snapshot = takeFormSnapshot(formToken);
                CompletableFuture<Void> generationFuture = null;
                if (snapshot != null) {
                    generationFuture = dispatchGeneration(snapshot, action, emitter);
                } else if ("generate_image".equals(action) || "generate_video".equals(action)) {
                    // §4.3 快照缺失兜底：formSnapshots 按 formToken 缓存可能因服务重启/过期丢失，此时用
                    // 会话维度冗余记录的最近 formContent 构造降级快照（plan=null，formContent 作 prompt
                    // 由 dispatchGeneration 兜底取用），保证生成类 action 仍能出图/出视频；agree 无法
                    // 结构化写库（无 items），仍仅续流。降级快照不写入 formSnapshots，仅本次使用。
                    LastFormContent last = lastFormContentByConversation.get(conversation.getId());
                    if (last != null && !last.expired()) {
                        log.info("快照缺失但存在会话 formContent 兜底, 降级生成: conversationId={}, action={}",
                                conversationId, action);
                        snapshot = new FormSnapshot(last.content(), List.of(), null,
                                conversation.getId(), conversation.getProjectId(), System.currentTimeMillis());
                        generationFuture = dispatchGeneration(snapshot, action, emitter);
                    } else {
                        log.info("无方案快照且无 formContent 兜底(formToken={}), 仅续流不生成", formToken);
                    }
                } else {
                    log.info("无方案快照(formToken={}), 仅续流不生成", formToken);
                }
                // 生成后端化：HITL 人工确认动作落库为用户消息（独立事务立即提交，刷新/历史可见；
                // 与 sendMessage 的 user 消息同语义——用户操作记录不因后续 Dify 续流/生成成败而丢失）
                persistUserConfirmation(conversation, action, snapshot);
                // 续流：workflow events 端点 user 参数必填（已核实 Dify 源码）+ continue_on_pause=true
                // （Dify 文档：设为 true 时流在 workflow_paused 事件之间保持连接，直到 workflow_finished
                //  才结束；否则遇到第一个暂停事件流即关闭，后续 HITL 节点无法继续订阅）
                HttpRequest eventsReq = HttpRequest.newBuilder()
                    // C4：taskId（路径段）与 userId（query 参数）均做 UTF-8 百分号编码
                    .uri(URI.create(config.getDifyBaseUrl() + "/v1/workflow/"
                        + URLEncoder.encode(taskId, StandardCharsets.UTF_8)
                        + "/events?user=" + URLEncoder.encode(userId, StandardCharsets.UTF_8)
                        + "&continue_on_pause=true"))
                    .header("Authorization", "Bearer " + config.getDifyApiKey())
                    .timeout(Duration.ofSeconds(600))
                    .GET()
                    .build();
                HttpResponse<java.io.InputStream> eventsResp = httpClient.send(eventsReq, HttpResponse.BodyHandlers.ofInputStream());
                if (eventsResp.statusCode() != 200) {
                    // Z2：非 200 时 InputStream 未被消费，显式关闭避免 HTTP 连接泄漏
                    closeQuietly(eventsResp.body());
                    sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                    emitter.complete();
                    return;
                }
                forwardDifySse(eventsResp, emitter, conversation, userId, new StringBuilder(), cancel, true);
                // 等待生成任务完成（图片 30-60s / 视频 2-5min），完成后再关闭 SSE
                if (generationFuture != null) {
                    generationFuture.get(8, TimeUnit.MINUTES);
                }
                if (!cancel.get()) emitter.complete();
            } catch (Exception e) {
                // I1：客户端已断开时不再补发 error/complete（emitter 已被容器关闭）
                if (cancel.get()) {
                    log.debug("SSE 已取消，忽略 HITL 提交/续流异常: conversationId={}", conversationId);
                    return;
                }
                // §4.3：generationFuture.get(8min) 超时是预期内失败（视频生成慢），与 Dify 服务异常区分文案
                if (e instanceof java.util.concurrent.TimeoutException) {
                    log.warn("Agent 生成超时(8min): conversationId={}", conversationId);
                    sendEvent(emitter, "error", Map.of("code", "50202", "message", "生成超时，请稍后重试"));
                    emitter.complete();
                    return;
                }
                log.error("Dify HITL 提交/续流失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                emitter.complete();
            }
        }, agentExecutor);
    }

    /**
     * HITL 人工确认动作落库为用户消息（如「确认：开始生成视频」）。
     * 与 sendMessage 的 user 消息同语义：独立事务（REQUIRES_NEW）立即提交，
     * 不随后续 Dify 续流/生成结果成败而回滚，刷新/历史列表始终可见。
     * 动作标题从表单快照 actions 中按 action id 解析，快照缺失时回退 action id 原文。
     */
    private void persistUserConfirmation(AgentConversation conversation, String action, FormSnapshot snapshot) {
        String title = action;
        if (snapshot != null && snapshot.actions() != null) {
            for (Map<String, String> a : snapshot.actions()) {
                if (action.equals(a.get("id"))) {
                    title = a.getOrDefault("title", action);
                    break;
                }
            }
        }
        final String content = "确认：" + title;
        transactionTemplate.executeWithoutResult(tx -> {
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
