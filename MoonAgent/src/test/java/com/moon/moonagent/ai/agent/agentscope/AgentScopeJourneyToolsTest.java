package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复合旅程的分支选择、可配置阈值、步骤清单与媒体预算。
 */
class AgentScopeJourneyToolsTest {

    // ------------------------------------------------------------------
    // 分支选择
    // ------------------------------------------------------------------

    @Test
    void durationAtOrBelowThresholdSelectsShortBranchWithImageThenVideo() {
        Fixture fixture = fixture(15);
        when(fixture.asyncTasks.startJourneyTask(
                any(), anyString(), anyList(), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), anyBoolean(),
                any(AgentScopeJourneyPlanner.JourneyPlan.class), anyString()))
                .thenReturn("journey-short");

        Map<String, Object> out = fixture.tools.start(
                Map.of("prompt", "雨夜街头", "target_duration_seconds", "12"),
                fixture.context, "call-1");

        assertThat(out).containsEntry("ok", true)
                .containsEntry("status", "queued")
                .containsEntry("taskId", "journey-short")
                .containsEntry("branch", "short");
        assertThat((List<?>) out.get("steps")).hasSize(2);
        assertThat(stepDisplays(out)).containsExactly("① 生成 1 张图片", "② 生成视频");

        // 短分支只建立父任务，不直接创建分镜/逐镜子任务。
        verify(fixture.asyncTasks).startJourneyTask(
                any(), anyString(), anyList(), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq("12"), eq(1), eq(false), any(), eq("call-1"));
        verify(fixture.asyncTasks, never()).startJourneyImageChild(
                anyString(), anyInt(), anyString(), any(), anyString(), anyList(), anyList(),
                anyString(), anyString(), anyInt(), anyBoolean(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(fixture.asyncTasks, never()).startJourneyStoryboardImageChild(
                anyString(), anyInt(), any(), anyList(), anyBoolean(), anyString());
        verify(fixture.asyncTasks, never()).startJourneyStoryboardVideoChild(
                anyString(), anyInt(), any(), anyList(), anyBoolean(), anyString());
        verify(fixture.asyncTasks, never()).startStoryboardChildTask(
                anyString(), anyInt(), any(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void exactlyFifteenSecondsStaysOnShortBranchAndSixteenUpgradesToLong() {
        assertThat(AgentScopeJourneyPlanner.branch("15", false, 15))
                .isEqualTo(AgentScopeJourneyPlanner.Branch.SHORT);
        assertThat(AgentScopeJourneyPlanner.branch("16", false, 15))
                .isEqualTo(AgentScopeJourneyPlanner.Branch.LONG);

        Fixture at15 = fixture(15);
        when(at15.asyncTasks.startJourneyTask(
                any(), anyString(), anyList(), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), anyBoolean(),
                any(AgentScopeJourneyPlanner.JourneyPlan.class), anyString()))
                .thenReturn("journey-15");
        assertThat(at15.tools.start(
                Map.of("prompt", "边界", "target_duration_seconds", "15"), at15.context, "c15"))
                .containsEntry("branch", "short");

        Fixture at16 = fixture(15);
        when(at16.asyncTasks.startJourneyTask(
                any(), anyString(), anyList(), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), anyBoolean(),
                any(AgentScopeJourneyPlanner.JourneyPlan.class), anyString()))
                .thenReturn("journey-16");
        Map<String, Object> longOut = at16.tools.start(
                Map.of("prompt", "边界", "target_duration_seconds", "16"), at16.context, "c16");
        assertThat(longOut).containsEntry("branch", "long");
        assertThat(stepDisplays(longOut)).containsExactly(
                "① 生成分镜", "② 逐镜配图", "③ 逐镜视频", "④ 合成成片");
        verify(at16.asyncTasks).startJourneyTask(
                any(), anyString(), anyList(), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq("16"), eq(1), eq(false), any(), eq("c16"));
    }

    @Test
    void explicitStoryboardRequestSelectsLongBranchEvenForShortDuration() {
        Fixture fixture = fixture(15);
        when(fixture.asyncTasks.startJourneyTask(
                any(), anyString(), anyList(), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), anyBoolean(),
                any(AgentScopeJourneyPlanner.JourneyPlan.class), anyString()))
                .thenReturn("journey-explicit");

        Map<String, Object> out = fixture.tools.start(
                Map.of("prompt", "分镜成片", "target_duration_seconds", "8", "want_storyboard", true),
                fixture.context, "call-explicit");

        assertThat(out).containsEntry("branch", "long");
        assertThat((String) out.get("branchReason")).contains("explicit-storyboard");
        verify(fixture.asyncTasks).startJourneyTask(
                any(), anyString(), anyList(), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq("8"), eq(1), eq(true), any(), eq("call-explicit"));
    }

    // ------------------------------------------------------------------
    // 阈值可配置：分支判定读属性，而不是硬编码 15
    // ------------------------------------------------------------------

    @Test
    void thresholdComesFromPropertiesNotFromALiteral() {
        Fixture fixture = fixture(30);
        when(fixture.asyncTasks.startJourneyTask(
                any(), anyString(), anyList(), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), anyBoolean(),
                any(AgentScopeJourneyPlanner.JourneyPlan.class), anyString()))
                .thenReturn("journey-configured");

        Map<String, Object> out = fixture.tools.start(
                Map.of("prompt", "20 秒但阈值 30", "target_duration_seconds", "20"),
                fixture.context, "call-configured");

        assertThat(out).containsEntry("branch", "short")
                .containsEntry("thresholdSeconds", 30);
    }

    @Test
    void propertyDefaultIsFifteenSecondsAndIsClamped() {
        AgentScopeProperties properties = new AgentScopeProperties();
        assertThat(properties.getJourneyDirectVideoMaxSeconds()).isEqualTo(15);

        properties.setJourneyDirectVideoMaxSeconds(0);
        assertThat(properties.getJourneyDirectVideoMaxSeconds()).isEqualTo(1);

        properties.setJourneyDirectVideoMaxSeconds(9999);
        assertThat(properties.getJourneyDirectVideoMaxSeconds()).isEqualTo(600);
    }

    // ------------------------------------------------------------------
    // 媒体预算：复合入口不是 preflight / budget 旁路
    // ------------------------------------------------------------------

    @Test
    void compositeEntryConsumesImageAndVideoMediaBudget() {
        Fixture fixture = fixture(15);
        when(fixture.asyncTasks.startJourneyTask(
                any(), anyString(), anyList(), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), anyBoolean(),
                any(AgentScopeJourneyPlanner.JourneyPlan.class), anyString()))
                .thenReturn("journey-budget");

        Map<String, Object> out = fixture.tools.start(
                Map.of("prompt", "预算", "target_duration_seconds", "8"),
                fixture.context, "call-budget");

        assertThat(out).containsEntry("ok", true);
        assertThat(fixture.turnState.mediaAttempts("image")).isEqualTo(1);
        assertThat(fixture.turnState.mediaAttempts("video")).isEqualTo(1);
        assertThat(fixture.turnState.hasStartedMedia("image")).isTrue();
        assertThat(fixture.turnState.hasStartedMedia("video")).isTrue();
    }

    @Test
    void secondJourneyInSameTurnIsRejectedByMediaBudget() {
        Fixture fixture = fixture(15);
        when(fixture.asyncTasks.startJourneyTask(
                any(), anyString(), anyList(), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), anyBoolean(),
                any(AgentScopeJourneyPlanner.JourneyPlan.class), anyString()))
                .thenReturn("journey-first");

        fixture.tools.start(Map.of("prompt", "第一次", "target_duration_seconds", "8"),
                fixture.context, "call-first");
        Map<String, Object> second = fixture.tools.start(
                Map.of("prompt", "第二次", "target_duration_seconds", "8"),
                fixture.context, "call-second");

        assertThat(second).containsEntry("ok", false);
        assertThat(String.valueOf(second.get("error"))).contains("已经生成或提交过图片");
        verify(fixture.asyncTasks).startJourneyTask(
                any(), anyString(), anyList(), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), anyBoolean(),
                any(AgentScopeJourneyPlanner.JourneyPlan.class), eq("call-first"));
    }

    @Test
    void missingReferenceAssetIsRejectedThroughPreflightSemantics() {
        Fixture fixture = fixture(15);
        when(fixture.sessionAssets.existingAssetIds(eq("conversation-1"), anyList()))
                .thenReturn(java.util.Set.of());

        Map<String, Object> out = fixture.tools.start(
                Map.of("prompt", "参考", "source_asset_ids", List.of("asset-missing")),
                fixture.context, "call-reference");

        assertThat(out).containsEntry("ok", false).containsEntry("needsClarification", true);
        assertThat(String.valueOf(out.get("message"))).contains("asset-missing");
        verify(fixture.asyncTasks, never()).startJourneyTask(
                any(), anyString(), anyList(), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), anyBoolean(),
                any(AgentScopeJourneyPlanner.JourneyPlan.class), anyString());
    }

    // ------------------------------------------------------------------
    // 工具注册与元数据
    // ------------------------------------------------------------------

    @Test
    void toolIsRegisteredAsWriteToolWithExpectedSchema() {
        Fixture fixture = fixture(15);

        AgentScopeTool tool = fixture.tools.tool();

        assertThat(tool.getName()).isEqualTo(AgentScopeJourneyTools.PRODUCE_VIDEO_JOURNEY);
        assertThat(tool.isReadOnly()).isFalse();
        assertThat(tool.getParameters().toString())
                .contains("prompt", "shot_prompts", "source_asset_ids", "source_urls",
                        "target_duration_seconds", "aspect_ratio", "resolution", "model",
                        "image_count", "want_storyboard");
    }

    private static List<String> stepDisplays(Map<String, Object> out) {
        Object steps = out.get("steps");
        assertThat(steps).isInstanceOf(List.class);
        return ((List<?>) steps).stream()
                .map(item -> String.valueOf(((Map<?, ?>) item).get("display")))
                .toList();
    }

    private record Fixture(AgentScopeJourneyTools tools,
                           AgentScopeAsyncTaskService asyncTasks,
                           MediaToolPreflightService preflight,
                           SessionAssetService sessionAssets,
                           AgentScopeTurnState turnState,
                           AgentScopeRunContext context) {
    }

    private static Fixture fixture(int thresholdSeconds) {
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        MediaToolPreflightService preflight = mock(MediaToolPreflightService.class);
        SessionAssetService sessionAssets = mock(SessionAssetService.class);
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.setJourneyDirectVideoMaxSeconds(thresholdSeconds);
        AgentScopeJourneyTools tools =
                new AgentScopeJourneyTools(asyncTasks, properties, preflight, sessionAssets);

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "帮我做一条完整短片", List.of(), null);
        AgentScopeRunContext context = new AgentScopeRunContext(
                conversation, request, null, turnState);
        return new Fixture(tools, asyncTasks, preflight, sessionAssets, turnState, context);
    }
}
