package com.moon.moonagent.ai.agent.specialist.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillExpander;
import com.moon.moonagent.ai.agent.skill.SkillManager;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通用执行器「多步澄清步骤机」纯逻辑验证（不调 LLM）。
 *
 * <p>Mock 掉 ChatClient 链（.content() 返回固定 JSON）与 {@link AgentOrchestratorSupport}，
 * 驱动真实 {@link GenericSkillSpecialist#execute} 验证：
 * run 首步 needClarify=true → 弹 skill-step:clarify_style 卡；
 * resume 拿到所选 → 逐步推进（needClarify=false）→ 最终返回「测试成功」。
 */
class GenericSkillSpecialistStepTests {

    private ChatClient.Builder builder;
    private ChatClient client;
    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;
    private AgentOrchestratorSupport support;
    private GenericSkillSpecialist specialist;

    @BeforeEach
    void setup() {
        builder = mock(ChatClient.Builder.class);
        client = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        support = mock(AgentOrchestratorSupport.class);

        GatewayModelService gateway = mock(GatewayModelService.class);
        when(gateway.getDefaultTextModel()).thenReturn("model");
        when(gateway.getDefaultVisionModel()).thenReturn("vision");

        specialist = new GenericSkillSpecialist(
                builder, gateway,
                mock(AgentMessageMapper.class), mock(FileStorageService.class),
                mock(SkillExpander.class), mock(SkillManager.class), support);

        when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(support.runHITLStage(any(), any(), any())).thenAnswer(inv ->
                ((AgentOrchestratorSupport.StagePlan) inv.getArgument(2)).planText());
    }

    @Test
    void runFirstStepClarifies() {
        when(callSpec.content()).thenReturn("{\"needClarify\":true,\"message\":\"请选择风格\","
                + "\"options\":[{\"id\":\"style1\",\"title\":\"赛博朋克\"},{\"id\":\"style2\",\"title\":\"水墨画\"}]}");

        String result = specialist.execute(ctx(testSkill(), "测试", "", "", "", "")).message();

        assertEquals("请选择风格", result, "run 首步应返回卡片文案");
        ArgumentCaptor<AgentOrchestratorSupport.StagePlan> cap =
                ArgumentCaptor.forClass(AgentOrchestratorSupport.StagePlan.class);
        verify(support).runHITLStage(any(), any(), cap.capture());
        assertEquals("skill-step:clarify_style", cap.getValue().action(), "首步应为 clarify_style 卡");
        assertEquals(2, cap.getValue().actions().size(), "卡片选项应为 LLM 生成的 2 项");
    }

    @Test
    void resumeAdvancesToFinish() {
        // 后续步骤都 needClarify=false → 直接推进到收尾
        when(callSpec.content()).thenReturn("{\"needClarify\":false,\"message\":\"已明确\"}");
        AgentSkill skill = testSkill();
        String cpPlan = buildPlan("clarify_style",
                List.of(Map.of("step", "clarify_style", "value", "赛博朋克")),
                List.of(Map.of("id", "style1", "title", "赛博朋克")));
        when(support.planField(cpPlan, "intent")).thenReturn("intent-test");
        when(support.planField(cpPlan, "step")).thenReturn("clarify_style");
        when(support.planListField(cpPlan, "collected")).thenReturn(
                new ArrayList<>(List.of(Map.of("step", "clarify_style", "value", "赛博朋克"))));
        when(support.planListField(cpPlan, "actions")).thenReturn(
                new ArrayList<>(List.of(Map.of("id", "style1", "title", "赛博朋克"))));

        // resume：用户选了 style1
        String result = specialist.execute(ctx(skill, "", "", "style1", "skill-step:clarify_style", cpPlan)).message();

        assertTrue(result.startsWith("测试成功"), "走到 flow 末尾应返回测试成功，实际=" + result);
        verify(support, never()).runHITLStage(any(), any(), any());
    }

    private AgentSkill testSkill() {
        Map<String, Object> fm = new HashMap<>();
        fm.put("name", "test");
        fm.put("description", "测试技能");
        fm.put("label", "测试技能");
        fm.put("model_role", "text");
        fm.put("tools", List.of());
        fm.put("triggers", List.of("测试"));
        fm.put("priority", 15);
        fm.put("menu_order", 45);
        fm.put("flow", List.of("clarify_style", "clarify_actor", "confirm_plan"));
        return AgentSkill.of("test", fm, Map.of(
                "clarify_style", "请用户选择风格。",
                "clarify_actor", "请用户提供角色。",
                "confirm_plan", "确认方案。"));
    }

    private SpecialistContext ctx(AgentSkill skill, String user, String custom, String action,
                                  String cpAction, String cpPlan) {
        AgentConversation conv = new AgentConversation();
        conv.setId("c1");
        return new SpecialistContext(conv, user, List.of(), null, null, skill,
                action, custom, Map.of(), List.of(), cpAction, cpPlan);
    }

    private String buildPlan(String step, List<Map<String, Object>> collected,
                             List<Map<String, Object>> actions) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("intent", "intent-test");
        item.put("skill", "test");
        item.put("step", step);
        item.put("collected", collected);
        item.put("actions", actions);
        try {
            return new ObjectMapper().writeValueAsString(Map.of("items", List.of(item)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
