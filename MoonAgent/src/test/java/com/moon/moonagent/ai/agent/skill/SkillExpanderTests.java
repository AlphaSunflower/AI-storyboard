package com.moon.moonagent.ai.agent.skill;

import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.AgentTools;
import com.moon.moonagent.mapper.AgentConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * SkillExpander 单元测试 —— 声明即扩展的工具桥：
 * AgentTools 上的 @Tool 方法能提取为 ToolCallback 并按名称过滤；缺失工具只忽略不抛错。
 * 不启动 Spring 上下文（SkillLoader.loadAll 手动触发，加载 classpath:skills）。
 */
class SkillExpanderTests {

    private SkillExpander skillExpander;
    private SkillManager skillManager;
    private AgentTools agentTools;

    @BeforeEach
    void setup() {
        SkillLoader skillLoader = new SkillLoader();
        skillLoader.loadAll(); // 包内可见，手动加载 resources/skills
        skillManager = new SkillManager(skillLoader);

        agentTools = new AgentTools(
                mock(AgentGenerationService.class),
                mock(AgentConversationMapper.class));

        skillExpander = new SkillExpander(agentTools, skillManager);
        skillExpander.init(); // 包内可见，手动触发索引
    }

    @Test
    void indexAllAgentTools() {
        Set<String> names = skillExpander.allToolNames();
        assertTrue(names.contains("writeScenes"), "应索引到 writeScenes");
        assertTrue(names.contains("replaceScenes"), "应索引到 replaceScenes");
        assertTrue(names.contains("replaceSpecificScenes"), "应索引到 replaceSpecificScenes");
        assertTrue(names.contains("refineImage"), "应索引到 refineImage");
        assertTrue(names.contains("generateVideo"), "应索引到 generateVideo");
        assertTrue(names.contains("testEcho"), "应索引到新增的 testEcho");
    }

    @Test
    void filterKnownToolByName() {
        ToolCallback[] callbacks = skillExpander.toolCallbacks(List.of("writeScenes"));
        assertEquals(1, callbacks.length, "已知工具应返回 1 个回调");
        assertEquals("writeScenes", callbacks[0].getToolDefinition().name());
    }

    @Test
    void filterSkipsUnknownTool() {
        ToolCallback[] callbacks = skillExpander.toolCallbacks(List.of("notExist", "generateVideo"));
        assertEquals(1, callbacks.length, "未知工具应被忽略，只保留可解析的");
        assertEquals("generateVideo", callbacks[0].getToolDefinition().name());
    }

    @Test
    void emptyOrNullInputReturnsEmpty() {
        assertEquals(0, skillExpander.toolCallbacks(List.of()).length);
        assertEquals(0, skillExpander.toolCallbacks(null).length);
    }

    @Test
    void everySkillDeclaredToolResolves() {
        // 每个技能的 tools 声明都应能解析出回调（声明即扩展：缺了会在启动时告警，这里应可解析）
        for (AgentSkill skill : skillManager.all()) {
            if (skill.tools() == null || skill.tools().isEmpty()) continue;
            ToolCallback[] callbacks = skillExpander.toolCallbacks(skill.tools());
            assertEquals(skill.tools().size(), callbacks.length,
                    "技能 " + skill.name() + " 声明的工具应全部可解析");
        }
    }

    @Test
    void testSkillLoadedWithMultiStepFlow() {
        var skill = skillManager.byIntent("intent-test");
        assertTrue(skill.isPresent(), "test 技能应被 SkillLoader 加载");
        assertFalse(skill.get().flow().isEmpty(), "test 技能应声明 flow 多步澄清流程");
        assertEquals("clarify_style", skill.get().flow().getFirst(), "flow 首步应为 clarify_style");
    }

    @Test
    void testEchoReturnsConcatenatedFirstSentence() {
        String result = agentTools.testEcho("测试一下这个功能好不好？后面是废话。");
        assertEquals("测试无效，略略略。用户第一句话：测试一下这个功能好不好？", result);
    }

    @Test
    void testEchoWithoutPunctuationUsesWholeText() {
        assertEquals("测试无效，略略略。用户第一句话：测试", agentTools.testEcho("测试"));
    }
}
