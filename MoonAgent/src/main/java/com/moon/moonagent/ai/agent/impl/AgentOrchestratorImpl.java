package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.ai.agent.AgentOrchestrator;
import com.moon.moonagent.ai.agent.AgentInterruptException;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 编排分发器（P2/P3 2026-08-21 起瘦身为 PlanGraph 委托壳）：
 *
 * <pre>
 * run():   委托 PlanGraph——意图识别节点 → 条件边路由 → handler 适配器节点
 * resume(): checkpoint 校验 + 一次性消费（表单协议不变）→ 委托 PlanGraph 条件边路由
 * </pre>
 *
 * <p>意图处理器注册表、resume 12+ if/else 分发已全部迁入
 * {@link com.moon.moonagent.ai.agent.PlanGraph}（StateGraph 声明式节点+条件边，
 * 含切链出口）。新增意图 = 新实现类 + @Component，核心零改动。
 */
@Service
@RequiredArgsConstructor
public class AgentOrchestratorImpl implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorImpl.class);

    private final com.moon.moonagent.ai.agent.PlanGraph planGraph;
    private final AgentCheckpointMapper checkpointMapper;
    private final AgentOrchestratorSupport support;

    @Override
    public String run(AgentConversation conversation, String content, java.util.List<String> picUrls, java.util.List<String> assetIds, SseEmitter emitter) {
        return runTurn(conversation, content, picUrls, assetIds, "", emitter).content();
    }

    @Override
    public String resume(AgentConversation conversation, String formToken, String action, String customText, Map<String, String> params, java.util.List<String> assetIds, String routingHint, SseEmitter emitter) {
        return resumeTurn(conversation, formToken, action, customText, params, assetIds, routingHint, emitter).content();
    }

    @Override
    public AgentTurnResult runTurn(AgentConversation conversation, String content, java.util.List<String> picUrls, java.util.List<String> assetIds, SseEmitter emitter) {
        return runTurn(conversation, content, picUrls, assetIds, "", emitter);
    }

    @Override
    public AgentTurnResult runTurn(AgentConversation conversation, String content, java.util.List<String> picUrls, java.util.List<String> assetIds, String routingHint, SseEmitter emitter) {
        return runTurn(conversation, content, picUrls, assetIds, routingHint, false, emitter);
    }

    @Override
    public AgentTurnResult runTurn(AgentConversation conversation, String content, java.util.List<String> picUrls, java.util.List<String> assetIds, String routingHint, boolean cancelActiveTasks, SseEmitter emitter) {
        return runTurn(conversation, content, picUrls, assetIds, routingHint, cancelActiveTasks, null, emitter);
    }

    @Override
    public AgentTurnResult runTurn(AgentConversation conversation, String content, java.util.List<String> picUrls,
                                   java.util.List<String> assetIds, String routingHint, boolean cancelActiveTasks,
                                   CanvasSessionContext canvasContext, SseEmitter emitter) {
        OrchestrationRequest request = new OrchestrationRequest(conversation, content, picUrls, emitter);
        request.setRoutingHint(routingHint);
        request.setCanvasContext(canvasContext);
        try {
            AgentTurnResult result = planGraph.runTurn(conversation, content, picUrls, assetIds, routingHint,
                    cancelActiveTasks, canvasContext, emitter);
            log.info("AgentOrchestrator(PlanGraph).runTurn: conversationId={} hitlPaused={} 完成", conversation.getId(), result.hitlPaused());
            return result;
        } catch (AgentInterruptException e) {
            log.info("AgentOrchestrator.runTurn 被用户打断: conversationId={}", conversation.getId());
            return AgentTurnResult.done("");
        } catch (Exception e) {
            log.error("AgentOrchestrator.runTurn 失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            return AgentTurnResult.done(support.sendFriendlyError(request, e.getMessage(), "服务暂时出了点问题，请稍后重试或换个说法再问我一次。"));
        }
    }

    @Override
    public AgentTurnResult resumeTurn(AgentConversation conversation, String formToken, String action, String customText, Map<String, String> params, java.util.List<String> assetIds, String routingHint, SseEmitter emitter) {
        return resumeTurn(conversation, formToken, action, customText, params, assetIds, routingHint, null, emitter);
    }

    @Override
    public AgentTurnResult resumeTurn(AgentConversation conversation, String formToken, String action,
                                      String customText, Map<String, String> params,
                                      java.util.List<String> assetIds, String routingHint,
                                      CanvasSessionContext canvasContext, SseEmitter emitter) {
        OrchestrationRequest request = new OrchestrationRequest(conversation, "", java.util.List.of(), emitter);
        request.setParams(params == null ? Map.of() : params);
        request.setAssetIds(assetIds == null ? java.util.List.of() : assetIds);
        request.setAction(action == null ? "" : action);
        request.setCustomText(customText == null ? "" : customText);
        request.setRoutingHint(routingHint);
        request.setCanvasContext(canvasContext);
        try {
            AgentCheckpoint cp = checkpointMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentCheckpoint>()
                    .eq(AgentCheckpoint::getFormToken, formToken)
                    .eq(AgentCheckpoint::getConversationId, conversation.getId())
                    .last("LIMIT 1"));
            if (cp == null) {
                support.sendEvent(request, "error", Map.of("code", "40401", "message", "确认信息不存在或已失效，请重新发起"));
                return AgentTurnResult.done("");
            }
            if (!"pending".equals(cp.getStatus()) || cp.getExpirationTime() == null
                    || cp.getExpirationTime().isBefore(OffsetDateTime.now())) {
                support.sendEvent(request, "error", Map.of("code", "40001", "message", "确认已过期，请重新发起"));
                return AgentTurnResult.done("");
            }
            // 一次性消费：status → used（原子条件防并发重放）
            int updated = checkpointMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentCheckpoint>()
                    .eq(AgentCheckpoint::getId, cp.getId())
                    .eq(AgentCheckpoint::getStatus, "pending")
                    .set(AgentCheckpoint::getStatus, "used"));
            if (updated == 0) {
                support.sendEvent(request, "error", Map.of("code", "40001", "message", "确认已被使用，请重新发起"));
                return AgentTurnResult.done("");
            }

            // P4：从 checkpoint.plan 恢复会话级内存计数（regenCount/clarifyCount/sessionAssets）——
            // 重启后 resume 上限判定与资产接力不丢（_regenCount/_clarifyCount/_sessionAssets 私有键）
            support.restoreCountersFromPlan(conversation.getId(), cp.getPlan());
            // 素材上下文接力：恢复上一张卡保存的参考图；表单未携带资产时回填会话级已确认资产
            request.setPicUrls(support.planPicUrls(cp.getPlan()));
            if (request.getAssetIds().isEmpty()) {
                // 资产上下文接力：优先从 checkpoint 的 items[0].assetIds 恢复（如 video_plan 勾选的资产），
                // 再回退会话级 _sessionAssets 快照。这样「我要分镜」等自定义切链不会把已选资产丢掉。
                List<String> fromPlan = support.planAssetIds(cp.getPlan());
                request.setAssetIds(fromPlan.isEmpty()
                        ? support.getSessionAssets(conversation.getId())
                        : fromPlan);
            }

            AgentTurnResult result = planGraph.resumeTurn(conversation, cp, action, customText, params,
                    request.getAssetIds(), routingHint, canvasContext, emitter);
            log.info("AgentOrchestrator(PlanGraph).resumeTurn: conversationId={} cpAction={} action={} routingHint={} hitlPaused={} 完成",
                    conversation.getId(), cp.getAction(), action, routingHint, result.hitlPaused());
            return result;
        } catch (AgentInterruptException e) {
            log.info("AgentOrchestrator.resumeTurn 被用户打断: conversationId={}", conversation.getId());
            return AgentTurnResult.done("");
        } catch (Exception e) {
            log.error("AgentOrchestrator.resumeTurn 失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            return AgentTurnResult.done(support.sendFriendlyError(request, e.getMessage(), "服务暂时出了点问题，请稍后重试或换个说法再试一次。"));
        }
    }
}
