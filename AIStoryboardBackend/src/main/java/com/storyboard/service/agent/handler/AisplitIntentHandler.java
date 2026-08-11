package com.storyboard.service.agent.handler;

import com.storyboard.entity.AgentCheckpoint;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.agent.AgentSceneItem;
import com.storyboard.service.agent.AgentTools;
import com.storyboard.service.ai.ScriptGenerationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * aisplit 分镜链：剧本优化 gate → 分镜方案 → 分镜 JSON → HITL「满意/不满意」→ resume 写库。
 *
 * <p>剧本优化与分镜方案两个 gate 均为手动澄清循环：type=0 追问并结束本轮，type=1 继续；
 * message 字段流式增量转发（打字机效果）。
 */
@Component
@RequiredArgsConstructor
public class AisplitIntentHandler implements IntentHandler {

    private static final Logger log = LoggerFactory.getLogger(AisplitIntentHandler.class);

    private final AgentOrchestratorSupport support;
    private final ScriptGenerationService scriptGenerationService;
    private final AgentTools agentTools;
    private final SceneMapper sceneMapper;

    @Override
    public String intentType() {
        return "intent-aisplit";
    }

    @Override
    public Set<String> resumeActions() {
        return Set.of("agree");
    }

    @Override
    public String handle(OrchestrationRequest request) {
        String content = request.getContent();

        // 1. 剧本优化（手动澄清循环：type=0 回答结束，type=1 继续；message 字段已流式增量转发）
        support.sendEvent(request, "workflow", Map.of("title", "正在优化剧本…", "status", "node_started"));
        AgentOrchestratorSupport.ScriptOptimizeResult opt = support.callScriptOptimize(content, request);
        if (opt == null || opt.type() == 0) {
            // 流式失败兜底（message 未发出时补一条）
            if (request.getLastMessage().isBlank()) {
                support.sendMessage(request, opt != null ? opt.message() : "已理解你的需求，请继续补充。");
            }
            return request.getLastMessage();
        }
        String script = opt.script() != null && !opt.script().isBlank() ? opt.script() : content;

        // 2. 分镜方案设计（结构化 message，流式增量转发）
        support.sendEvent(request, "workflow", Map.of("title", "正在设计分镜方案…", "status", "node_started"));
        AgentOrchestratorSupport.StoryboardPlanResult plan = support.callStoryboardPlan(script, request);
        if (plan == null || plan.type() == 0) {
            if (request.getLastMessage().isBlank()) {
                support.sendMessage(request, plan != null ? plan.message() : "分镜方案需要进一步明确，请补充描述。");
            }
            return request.getLastMessage();
        }

        // 3. 分镜 JSON（复用 ScriptGenerationService：LLM 生成 8 字段分镜列表）
        support.sendEvent(request, "workflow", Map.of("title", "正在生成分镜…", "status", "node_started"));
        List<Map<String, Object>> scenes = scriptGenerationService.generateScenes(
                request.getConversation().getProjectId(), script, "movie", null, "16:9", null);
        if (scenes == null || scenes.isEmpty()) {
            support.sendMessage(request, "⚠ 未能生成分镜内容，请重新描述需求。");
            return request.getLastMessage();
        }

        // 4. HITL 通用模板：方案消息 → checkpoint(agree) → human_input 事件（满意/不满意）
        String planText = "📋 分镜方案（共 " + scenes.size() + " 个镜头）：\n" + support.summarizeScenes(scenes);
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                planText, "agree", scenes, "human_input",
                List.of(Map.of("id", "agree", "title", "满意"), Map.of("id", "disagree", "title", "不满意"))));
    }

    @Override
    public void resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        // 执行写分镜（EXECUTE step：自动模式工具调用）
        support.sendEvent(request, "workflow", Map.of("title", "正在写入分镜…", "status", "node_started"));
        List<AgentSceneItem> items = support.parsePlanScenes(checkpoint.getPlan());
        int count = agentTools.writeScenes(request.getConversation().getProjectId(), items)
                .getOrDefault("count", 0) instanceof Number n ? n.intValue() : 0;
        // sceneCount 传写库后项目分镜总数（writeScenes 为追加语义；前端用
        // sceneCount > 会话开始时数量 判断是否需要刷新分镜列表——总数才正确）
        long totalScenes = sceneMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.storyboard.entity.Scene>()
                        .eq(com.storyboard.entity.Scene::getProjectId, request.getConversation().getProjectId()));
        String msg = count > 0
                ? "✅ 分镜方案已确认，已生成 **" + count + " 个分镜**，请查看左侧分镜列表"
                : "⚠ 分镜方案已确认，但未解析到分镜内容，请重新描述需求";
        support.sendMessage(request, msg);
        support.sendEvent(request, "confirm_result", Map.of(
                "kind", "script", "sceneCount", totalScenes, "url", "", "actions", List.of()));
        support.sendEvent(request, "message_end", Map.of(
                "messageId", "", "sceneCount", totalScenes, "content", msg));
    }
}
