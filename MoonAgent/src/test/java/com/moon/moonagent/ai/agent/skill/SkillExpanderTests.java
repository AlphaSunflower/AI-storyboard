package com.moon.moonagent.ai.agent.skill;

import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.AgentTools;
import com.moon.moonagent.ai.agent.audit.ToolExecutionTemplate;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.ai.agent.human.HumanInputRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

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

        ToolExecutionTemplate executionTemplate = mock(ToolExecutionTemplate.class);
        when(executionTemplate.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        agentTools = new AgentTools(
                mock(AgentGenerationService.class),
                mock(AgentConversationMapper.class),
                executionTemplate);

        ToolCallbackProvider canvasProvider = mock(ToolCallbackProvider.class);
        ToolCallback[] canvasCallbacks = List.of(
                "canvas_get_context", "canvas_list_node_types", "canvas_get_state",
                "canvas_list_nodes", "canvas_get_node", "canvas_list_edges", "canvas_get_selection",
                "canvas_create_node", "canvas_create_nodes", "canvas_import_media",
                "canvas_create_canvas", "canvas_rename_canvas",
                "canvas_create_result_slots", "canvas_mark_result_slots",
                "canvas_get_node_model_config", "canvas_set_node_model_config",
                "canvas_update_node", "canvas_create_edge",
                "canvas_move_nodes", "canvas_layout_graph", "canvas_execute_node", "canvas_execute_graph",
                "canvas_get_execution", "canvas_cancel_execution", "canvas_delete_nodes", "canvas_delete_edges")
                .stream()
                .map(name -> {
                    ToolDefinition definition = mock(ToolDefinition.class);
                    when(definition.name()).thenReturn(name);
                    ToolCallback callback = mock(ToolCallback.class);
                    when(callback.getToolDefinition()).thenReturn(definition);
                    return callback;
                })
                .toArray(ToolCallback[]::new);
        when(canvasProvider.getToolCallbacks()).thenReturn(canvasCallbacks);
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenReturn(Stream.of(canvasProvider));

        skillExpander = new SkillExpander(agentTools, skillManager, providers, executionTemplate);
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
        assertTrue(names.contains("moon_request_human_input"), "应索引到人工介入工具");
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
        // 本地工具必须可解析；Canvas 和媒体 MCP 是外部服务，单测环境允许未启动。
        for (AgentSkill skill : skillManager.all()) {
            if (skill.tools() == null || skill.tools().isEmpty()) continue;
            java.util.List<String> missing = skill.tools().stream()
                    .filter(tool -> !skillExpander.hasTool(tool))
                    .toList();
            assertTrue(missing.isEmpty() || missing.stream().allMatch(tool ->
                            tool.startsWith("canvas_")
                                    || "generate_image".equals(tool)
                                    || "generate_video".equals(tool)),
                    "技能 " + skill.name() + " 存在不可解析的本地工具：" + missing);
        }
    }

    @Test
    void humanInputToolIsReturnDirectAndExposesHiddenValue() {
        ToolCallback[] callbacks = skillExpander.toolCallbacks(List.of("moon_request_human_input"));
        assertEquals(1, callbacks.length);
        assertTrue(callbacks[0].getToolMetadata().returnDirect());
        assertTrue(callbacks[0].getToolDefinition().inputSchema().contains("\"options\""));
        assertTrue(callbacks[0].getToolDefinition().description().contains("严禁在回复中向用户展示 ID"));
    }

    @Test
    void humanInputToolReturnsMachineReadableSelectionRequest() {
        ToolCallback callback = skillExpander.toolCallbacks(List.of("moon_request_human_input"))[0];
        String result = callback.call("""
                {"prompt":"请选择画布","allowCustom":false,
                 "options":[{"title":"项目一 / 画布一","value":{"projectId":"project-1","canvasId":"canvas-1"}}]}
                """);

        HumanInputRequest request = HumanInputRequest.parse(result).orElseThrow();
        assertEquals("请选择画布", request.prompt());
        assertEquals("human-option-1", request.options().getFirst().get("id"));
        assertEquals("项目一 / 画布一", request.options().getFirst().get("title"));
    }

    @Test
    void testSkillLoadedWithMultiStepFlow() {
        var skill = skillManager.byIntent("intent-test");
        assertTrue(skill.isPresent(), "test 技能应被 SkillLoader 加载");
        assertFalse(skill.get().flow().isEmpty(), "test 技能应声明 flow 多步澄清流程");
        assertEquals("clarify_style", skill.get().flow().getFirst(), "flow 首步应为 clarify_style");
    }

    @Test
    void legacySceneReviewSkillIsNoLongerLoaded() {
        assertTrue(skillManager.byIntent("intent-scene-review").isEmpty(),
                "旧分镜审查链路应从静态技能目录彻底移除");
    }

    @Test
    void canvasSkillsDescribeMediaRelayAndAutomaticRepair() {
        String design = skillManager.get("canvas-design").orElseThrow().systemPrompt();
        String run = skillManager.get("canvas-run").orElseThrow().systemPrompt();

        assertTrue(design.contains("结果槽"));
        assertTrue(design.contains("中间占位"));
        assertTrue(design.contains("输出口"));
        assertTrue(design.contains("canvas_get_node_model_config"));
        assertTrue(design.contains("canvas_set_node_model_config"));
        assertTrue(run.contains("执行前"));
        assertTrue(run.contains("自动补齐"));
        assertTrue(run.contains("canvas_set_node_model_config"));
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
