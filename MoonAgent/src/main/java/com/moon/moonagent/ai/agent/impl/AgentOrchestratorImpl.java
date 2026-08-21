package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.ai.agent.AgentOrchestrator;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
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
    public String run(AgentConversation conversation, String content, String picUrl, SseEmitter emitter) {
        OrchestrationRequest request = new OrchestrationRequest(conversation, content, picUrl, emitter);
        try {
            // P2（2026-08-21）：run 路径迁入 PlanGraph（StateGraph 主图）——
            // 意图识别（intent_recognize 节点）→ 条件边路由（低置信度 → intent_clarify 卡片 / 否则 → handler 适配器节点）
            // 图内节点调用与原先完全相同的 handler/support，SSE 事件协议零变化
            String answer = planGraph.run(conversation, content, picUrl, emitter);
            log.info("AgentOrchestrator(PlanGraph): conversationId={} 编排完成", conversation.getId());
            return answer;
        } catch (Exception e) {
            log.error("AgentOrchestrator 编排失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            // 上游错误不直接展示：LLM 翻译成友好中文回复（踩线→改措辞 / 网络→稍后重试），正常 message 收尾
            return support.sendFriendlyError(request, e.getMessage(), "服务暂时出了点问题，请稍后重试或换个说法再问我一次。");
        }
        // 注意：emitter.complete() 由调用方（AgentChatServiceImpl）统一执行，
        // 且调用方 finally 先释放会话锁再 complete——避免前端收到 EOF 立即发下一条时锁未释放（竞态 40901）
    }

    @Override
    public String resume(AgentConversation conversation, String formToken, String action, String customText, Map<String, String> params, java.util.List<String> assetIds, SseEmitter emitter) {
        OrchestrationRequest request = new OrchestrationRequest(conversation, "", null, emitter);
        request.setParams(params == null ? Map.of() : params);
        request.setAssetIds(assetIds == null ? java.util.List.of() : assetIds);
        try {
            AgentCheckpoint cp = checkpointMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentCheckpoint>()
                    .eq(AgentCheckpoint::getFormToken, formToken)
                    .eq(AgentCheckpoint::getConversationId, conversation.getId())
                    .last("LIMIT 1"));
            if (cp == null) {
                support.sendEvent(request, "error", Map.of("code", "40401", "message", "确认信息不存在或已失效，请重新发起"));
                return "";
            }
            if (!"pending".equals(cp.getStatus()) || cp.getExpirationTime() == null
                    || cp.getExpirationTime().isBefore(OffsetDateTime.now())) {
                support.sendEvent(request, "error", Map.of("code", "40001", "message", "确认已过期，请重新发起"));
                return "";
            }
            // 一次性消费：status → used（原子条件防并发重放）
            int updated = checkpointMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentCheckpoint>()
                    .eq(AgentCheckpoint::getId, cp.getId())
                    .eq(AgentCheckpoint::getStatus, "pending")
                    .set(AgentCheckpoint::getStatus, "used"));
            if (updated == 0) {
                support.sendEvent(request, "error", Map.of("code", "40001", "message", "确认已被使用，请重新发起"));
                return "";
            }

            // P3（2026-08-21）：resume 路由迁入 PlanGraph（StateGraph 条件边）——
            // 12+ if/else 硬编码分支收敛为图内条件边路由（cpAction/提交 action/customText → handler 节点），
            // 含切链出口（customText 识别为新意图 → 回 intent_recognize 重新分发）。
            // checkpoint 校验/一次性消费保留在此（表单协议不变）；handler 内部零改动。
            String answer = planGraph.resume(conversation, cp, action, customText, params, assetIds, emitter);
            log.info("AgentOrchestrator(PlanGraph).resume: conversationId={} cpAction={} action={} 完成",
                    conversation.getId(), cp.getAction(), action);
            return answer;
        } catch (Exception e) {
            log.error("AgentOrchestrator.resume 失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            // 上游错误不直接展示：LLM 翻译成友好中文回复，正常 message 收尾（同 run）
            return support.sendFriendlyError(request, e.getMessage(), "服务暂时出了点问题，请稍后重试或换个说法再试一次。");
        }
        // 同上：complete 由调用方统一执行（先释放锁再 complete，防 EOF 竞态）
    }
}
