package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.ai.agent.AgentOrchestrator;
import com.moon.moonagent.ai.agent.AgentInterruptException;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.recovery.CheckpointClaim;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeException;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeService;
import com.moon.moonagent.ai.agent.recovery.ResumeCommand;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.orchestration.UnifiedAgentLoop;
import com.moon.moonagent.orchestration.OrchestrationMode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 统一编排入口：run/resume 全部委托 {@link UnifiedAgentLoop}。
 */
@Service
@RequiredArgsConstructor
public class AgentOrchestratorImpl implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorImpl.class);

    private final UnifiedAgentLoop unifiedAgentLoop;
    private final CheckpointResumeService checkpointResumeService;
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
        return runTurn(conversation, content, picUrls, assetIds, routingHint, cancelActiveTasks,
                canvasContext, java.util.List.of(), java.util.List.of(), emitter);
    }

    @Override
    public AgentTurnResult runTurn(AgentConversation conversation, String content, java.util.List<String> picUrls,
                                   java.util.List<String> assetIds, String routingHint, boolean cancelActiveTasks,
                                   CanvasSessionContext canvasContext, java.util.List<String> referenceVideos,
                                   java.util.List<String> referenceAudios, SseEmitter emitter) {
        OrchestrationRequest request = new OrchestrationRequest(conversation, content, picUrls, emitter);
        request.setRoutingHint(routingHint);
        request.setCanvasContext(canvasContext);
        try {
            OrchestrationMode mode = OrchestrationMode.from(conversation.getOrchestrationMode());
            if (mode != OrchestrationMode.UNIFIED_LOOP) {
                return AgentTurnResult.done("旧版会话已只读归档，请新建会话后继续。");
            }
            AgentTurnResult result;
            result = unifiedAgentLoop.runTurn(conversation, content, picUrls, assetIds, routingHint,
                    cancelActiveTasks, canvasContext, referenceVideos, referenceAudios, emitter);
            log.info("AgentOrchestrator(UnifiedLoop).runTurn: conversationId={} hitlPaused={} 完成",
                    conversation.getId(), result.hitlPaused());
            result = withEngineMetadata(result, mode);
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
        ResumeCommand command = ResumeCommand.of(
                action, customText, params, assetIds, routingHint, canvasContext);
        try {
            CheckpointClaim claim =
                    checkpointResumeService.claim(conversation.getId(), formToken, command);
            return resumeClaimed(conversation, claim, emitter);
        } catch (CheckpointResumeException e) {
            OrchestrationRequest errorRequest =
                    new OrchestrationRequest(conversation, "", java.util.List.of(), emitter);
            support.sendEvent(errorRequest, "error",
                    Map.of("code", String.valueOf(e.code()), "message", e.getMessage()));
            return AgentTurnResult.done("");
        }
    }

    @Override
    public AgentTurnResult resumeClaimed(AgentConversation conversation, CheckpointClaim claim,
                                         SseEmitter emitter) {
        AgentCheckpoint cp = claim.checkpoint();
        ResumeCommand command = claim.command();
        OrchestrationRequest request = new OrchestrationRequest(conversation, "", java.util.List.of(), emitter);
        request.setParams(command.params());
        request.setAssetIds(command.assetIds());
        request.setAction(command.action());
        request.setCustomText(command.customText());
        request.setRoutingHint(command.routingHint());
        request.setCanvasContext(command.canvasContext());
        try {
            boolean agentScopeOwner = "AGENTSCOPE".equalsIgnoreCase(cp.getOwnerEngine());

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

            AgentTurnResult result;
            result = unifiedAgentLoop.resumeTurn(conversation, cp, command.action(), command.customText(),
                    command.params(), request.getAssetIds(), command.routingHint(),
                    request.getCanvasContext(), emitter);
            result = withEngineMetadata(result, OrchestrationMode.UNIFIED_LOOP);
            if (agentScopeOwner) result = withCheckpointOwner(result, "AGENTSCOPE");
            checkpointResumeService.complete(claim);
            log.info("AgentOrchestrator({}).resumeTurn: conversationId={} cpAction={} action={} routingHint={} hitlPaused={} 完成",
                    agentScopeOwner ? "AGENTSCOPE" : "UNIFIED_LOOP",
                    conversation.getId(), cp.getAction(), command.action(),
                    command.routingHint(), result.hitlPaused());
            return result;
        } catch (AgentInterruptException e) {
            log.info("AgentOrchestrator.resumeTurn 被用户打断: conversationId={}", conversation.getId());
            checkpointResumeService.retry(claim, "用户打断");
            throw new CheckpointResumeException(40904, "确认处理已中断，请稍后重试");
        } catch (CheckpointResumeException e) {
            checkpointResumeService.retry(claim, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("AgentOrchestrator.resumeTurn 失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            checkpointResumeService.retry(claim, e.getMessage());
            throw new CheckpointResumeException(50001,
                    e.getMessage() == null ? "确认恢复失败，请稍后重试" : e.getMessage());
        }
    }

    private static AgentTurnResult withEngineMetadata(AgentTurnResult result,
                                                      OrchestrationMode mode) {
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>(result.metadata());
        metadata.put("orchestrationMode", mode.name());
        metadata.put("engineVersion", mode.engineVersion());
        metadata.put("checkpointOwner", mode == OrchestrationMode.UNIFIED_LOOP ? "UNIFIED_LOOP" : "LEGACY");
        return new AgentTurnResult(result.content(), metadata, result.agentTrace(), result.reasoning(),
                result.suggestedActions(), result.hitlPaused(), result.messageId());
    }

    private static AgentTurnResult withCheckpointOwner(AgentTurnResult result, String owner) {
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>(result.metadata());
        metadata.put("checkpointOwner", owner);
        return new AgentTurnResult(result.content(), metadata, result.agentTrace(), result.reasoning(),
                result.suggestedActions(), result.hitlPaused(), result.messageId());
    }
}
