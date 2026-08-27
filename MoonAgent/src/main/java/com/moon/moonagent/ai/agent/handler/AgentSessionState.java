package com.moon.moonagent.ai.agent.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级内存状态（原 {@link AgentOrchestratorSupport} 迁出）：
 * 澄清计数 / 不满意调整计数 / 本轮勾选资产。纯内存，重启即失；
 * 上限值由 {@code ai.agent.*} 配置控制，计数经 checkpoint.plan 持久化（resume 恢复）。
 *
 * <p>ponytail: 单机内存态，重启清零（用户重新获得追问额度，无害）；多实例需落 DB 列。
 */
@Component
public class AgentSessionState {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionState.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 澄清计数：conversationId → 连续追问次数（跨用户消息持续） */
    private final ConcurrentHashMap<String, Integer> clarifyCount = new ConcurrentHashMap<>();

    /** 连续不满意调整计数（写库成功/达上限清零） */
    private final ConcurrentHashMap<String, Integer> regenCount = new ConcurrentHashMap<>();

    /** 本轮勾选资产：conversationId → assetIds（资产选择卡片确认后写入，HITL 链内接力） */
    private final ConcurrentHashMap<String, List<String>> sessionAssets = new ConcurrentHashMap<>();

    /** 澄清计数 +1，返回新值 */
    public int incrClarifyCount(String conversationId) {
        return clarifyCount.merge(conversationId, 1, Integer::sum);
    }

    /** 澄清计数清零（type=1 有进展 / 非 aisplit 轮调用） */
    public void resetClarify(String conversationId) {
        clarifyCount.remove(conversationId);
    }

    /** 不满意调整 +1，返回新值 */
    public int incrRegenCount(String conversationId) {
        return regenCount.merge(conversationId, 1, Integer::sum);
    }

    /** 写库成功/达上限清零 */
    public void clearRegenCount(String conversationId) {
        regenCount.remove(conversationId);
    }

    /** 记录本轮勾选资产 */
    public void setSessionAssets(String conversationId, List<String> ids) {
        sessionAssets.put(conversationId, ids);
    }

    /** 本轮已确认资产 ID（无记录返回空列表） */
    public List<String> getSessionAssets(String conversationId) {
        return sessionAssets.getOrDefault(conversationId, List.of());
    }

    /** 本轮是否已选过资产（防 handle 重入重复弹资产卡片） */
    public boolean hasSessionAssets(String conversationId) {
        return sessionAssets.containsKey(conversationId);
    }

    /** 本轮结束清理 */
    public void clearSessionAssets(String conversationId) {
        sessionAssets.remove(conversationId);
    }

    /** 会话删除时清理全部内存态 */
    public void cleanupOnDelete(String conversationId) {
        clarifyCount.remove(conversationId);
        regenCount.remove(conversationId);
        sessionAssets.remove(conversationId);
    }

    /** 计数写入 checkpoint.plan（createCheckpoint 内调用）——重启/刷新后 resume 可恢复 */
    public void persistCountersToPlan(Map<String, Object> plan, String conversationId) {
        plan.put("_regenCount", regenCount.getOrDefault(conversationId, 0));
        plan.put("_clarifyCount", clarifyCount.getOrDefault(conversationId, 0));
        plan.put("_sessionAssets", sessionAssets.getOrDefault(conversationId, List.of()));
    }

    /** 从 checkpoint.plan 恢复计数（resume 消费后调用）——重启后内存计数不丢 */
    public void restoreCountersFromPlan(String conversationId, String planJson) {
        try {
            if (planJson == null || planJson.isBlank()) return;
            Object root = objectMapper.readValue(planJson, Object.class);
            if (!(root instanceof Map<?, ?> m)) return;
            Object rc = m.get("_regenCount");
            if (rc instanceof Number n) regenCount.put(conversationId, n.intValue());
            Object cc = m.get("_clarifyCount");
            if (cc instanceof Number n) clarifyCount.put(conversationId, n.intValue());
            Object sa = m.get("_sessionAssets");
            if (sa instanceof List<?> list) {
                sessionAssets.put(conversationId, list.stream()
                        .filter(String.class::isInstance).map(String.class::cast).toList());
            }
        } catch (Exception e) {
            log.debug("restoreCountersFromPlan 解析失败（无害）: {}", e.getMessage());
        }
    }
}
