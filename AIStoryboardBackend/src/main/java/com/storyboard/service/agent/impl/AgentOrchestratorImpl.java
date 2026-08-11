package com.storyboard.service.agent.impl;

import com.storyboard.entity.AgentCheckpoint;
import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.AgentMessage;
import com.storyboard.mapper.AgentCheckpointMapper;
import com.storyboard.mapper.AgentMessageMapper;
import com.storyboard.service.agent.AgentOrchestrator;
import com.storyboard.service.agent.IntentRecognitionService;
import com.storyboard.service.agent.IntentResult;
import com.storyboard.service.agent.handler.AgentOrchestratorSupport;
import com.storyboard.service.agent.handler.IntentHandler;
import com.storyboard.service.agent.handler.OrchestrationRequest;
import com.storyboard.service.ai.AgentAiConfigProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排分发器（策略模式注册机制，替代原硬编码 switch 4 分支）：
 *
 * <pre>
 * run():   意图识别（规则前置 → LLM + confidence → 阈值判断）→ 按 intentType 分发 IntentHandler
 * resume(): checkpoint 校验 + 一次性消费 → 按提交 action 分发对应 handler.resume
 * </pre>
 *
 * <p>意图处理器注册表由 Spring 自动收集（{@link List}&lt;{@link IntentHandler}&gt; + @PostConstruct 建表）：
 * 新增意图 = 新实现类 + @Component，核心零改动。意图链实现见
 * {@link com.storyboard.service.agent.handler} 包（aisplit / pic / video / other）。
 */
@Service
@RequiredArgsConstructor
public class AgentOrchestratorImpl implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorImpl.class);

    private final IntentRecognitionService intentRecognitionService;
    private final AgentMessageMapper messageMapper;
    private final AgentCheckpointMapper checkpointMapper;
    private final AgentAiConfigProperties agentConfig;
    private final AgentOrchestratorSupport support;
    private final List<IntentHandler> intentHandlers;

    /** intent → handler（策略注册表） */
    private Map<String, IntentHandler> byIntent = Map.of();
    /** resume 提交 action → handler（由各 handler 的 resumeActions 展开注册） */
    private Map<String, IntentHandler> byAction = Map.of();

    /** 构建处理器注册表：Spring 自动收集所有 @Component IntentHandler */
    @PostConstruct
    void buildRegistry() {
        Map<String, IntentHandler> intentMap = new HashMap<>();
        Map<String, IntentHandler> actionMap = new HashMap<>();
        for (IntentHandler handler : intentHandlers) {
            intentMap.put(handler.intentType(), handler);
            for (String action : handler.resumeActions()) {
                actionMap.put(action, handler);
            }
        }
        byIntent = intentMap;
        byAction = actionMap;
        log.info("AgentOrchestrator: 意图处理器注册完成 intentHandlers={} resumeActions={}",
                byIntent.keySet(), byAction.keySet());
    }

    @Override
    public String run(AgentConversation conversation, String content, String picUrl, SseEmitter emitter) {
        OrchestrationRequest request = new OrchestrationRequest(conversation, content, picUrl, emitter);
        try {
            // 1. 意图识别（历史拼最近 8 条）
            List<AgentMessage> recent = messageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentMessage>()
                    .eq(AgentMessage::getConversationId, conversation.getId())
                    .orderByDesc(AgentMessage::getCreatedAt)
                    .last("LIMIT " + IntentRecognitionService.HISTORY_LIMIT)).reversed();
            IntentResult intentResult = intentRecognitionService.recognize(content, recent);
            String intent = intentResult.type();
            log.info("AgentOrchestrator: conversationId={} intent={} confidence={} source={}",
                    conversation.getId(), intent, intentResult.confidence(), intentResult.source());

            // 2. 低置信度：不硬路由，走澄清分支（rule 命中 1.0 / fallback 0.0 均不受影响）
            if (intentResult.confidence() < agentConfig.getIntentThreshold()) {
                support.sendEvent(request, "message", Map.of("content",
                        "没太确定你想做什么——是要生成分镜（剧本/故事板）、图片，还是视频？"));
                return request.getLastMessage();
            }

            // 2.5 非 aisplit 轮清零澄清计数（澄清追问次数只在分镜链内连续累计）
            if (!"intent-aisplit".equals(intent)) {
                support.resetClarify(conversation.getId());
            }

            // 3. intent → handler 分发（未知意图兜底 intent-other）
            IntentHandler handler = byIntent.get(intent);
            if (handler == null) {
                log.warn("AgentOrchestrator: 未知意图 {}，兜底分发 intent-other", intent);
                handler = byIntent.get(IntentRecognitionService.FALLBACK_TYPE);
            }
            String answer = handler.handle(request);
            log.info("AgentOrchestrator: conversationId={} 编排完成", conversation.getId());
            return answer;
        } catch (Exception e) {
            log.error("AgentOrchestrator 编排失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            support.sendEvent(request, "error", Map.of("code", "50202", "message", "服务异常，请稍后重试"));
            return "";
        } finally {
            try { emitter.complete(); } catch (Exception ignore) { }
        }
    }

    @Override
    public void resume(AgentConversation conversation, String formToken, String action, SseEmitter emitter) {
        OrchestrationRequest request = new OrchestrationRequest(conversation, "", null, emitter);
        try {
            AgentCheckpoint cp = checkpointMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentCheckpoint>()
                    .eq(AgentCheckpoint::getFormToken, formToken)
                    .eq(AgentCheckpoint::getConversationId, conversation.getId())
                    .last("LIMIT 1"));
            if (cp == null) {
                support.sendEvent(request, "error", Map.of("code", "40401", "message", "确认信息不存在或已失效，请重新发起"));
                return;
            }
            if (!"pending".equals(cp.getStatus()) || cp.getExpirationTime() == null
                    || cp.getExpirationTime().isBefore(OffsetDateTime.now())) {
                support.sendEvent(request, "error", Map.of("code", "40001", "message", "确认已过期，请重新发起"));
                return;
            }
            // 一次性消费：status → used（原子条件防并发重放）
            int updated = checkpointMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentCheckpoint>()
                    .eq(AgentCheckpoint::getId, cp.getId())
                    .eq(AgentCheckpoint::getStatus, "pending")
                    .set(AgentCheckpoint::getStatus, "used"));
            if (updated == 0) {
                support.sendEvent(request, "error", Map.of("code", "40001", "message", "确认已被使用，请重新发起"));
                return;
            }

            // 按提交 action 分发（前端提交的 action 为准：agree/generate_image → 对应 handler；refine/disagree 等 → 默认继续完善）
            IntentHandler handler = byAction.get(action);
            if (handler == null) {
                support.sendEvent(request, "message", Map.of("content", "好的，请继续完善设计方案。"));
                support.sendEvent(request, "message_end", Map.of(
                        "messageId", "", "sceneCount", -1L, "content", "好的，请继续完善设计方案。"));
                return;
            }
            handler.resume(request, cp);
        } catch (Exception e) {
            log.error("AgentOrchestrator.resume 失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            support.sendEvent(request, "error", Map.of("code", "50202", "message", "服务异常，请稍后重试"));
        } finally {
            try { emitter.complete(); } catch (Exception ignore) { }
        }
    }
}
