package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.task.AgentTaskEventHub;
import com.moon.moonagent.ai.agent.task.AgentTaskView;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionService;
import com.moon.moonagent.entity.AgentAsyncTask;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复合旅程进度的「工具卡归属」字段 {@code parentToolCallId}。
 *
 * <p>断言口径（需求 1 的三条）：
 * <ul>
 *   <li>旅程父任务 → 自身的 {@code toolCallId}</li>
 *   <li>旅程子任务 → <b>父任务</b>的 {@code toolCallId}（不是子任务自己的）</li>
 *   <li>非旅程任务 → 空字符串（前端保持既有独立卡片渲染）</li>
 * </ul>
 *
 * <p>全部断言都通过真实调用 {@code AgentScopeAsyncTaskService} 的公开任务视图方法完成，
 * 并用被 mock 的 {@link AgentTaskEventHub} 捕获实时下发的 {@link AgentTaskView}，
 * 证明该字段不只是轮询可得，而是跟着任务视图事件一起实时下发。
 */
class AgentScopeJourneyTaskViewTest {

    /**
     * LambdaQueryWrapper 的列名解析需要 MyBatis-Plus 的 lambda 缓存；
     * 与 {@code AgentScopeJourneyParentChildTest} 同一做法。
     */
    @BeforeAll
    static void initMyBatisMetadata() {
        if (TableInfoHelper.getTableInfo(AgentAsyncTask.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    AgentAsyncTask.class);
        }
    }

    @Test
    void journeyParentExposesItsOwnToolCallIdAsParentToolCallId() {
        Harness harness = harness();
        harness.parent.setToolCallId("call-journey");

        AgentTaskView view = harness.service.getTask("user-1", harness.parent.getId());

        assertThat(view).isNotNull();
        assertThat(view.parentToolCallId()).isEqualTo("call-journey");
        assertThat(view.parentTaskId()).isEmpty();
        // 序列化后的任务视图（REST / 轮询路径）同名字段同值，键名不变。
        assertThat(harness.service.getStatus("user-1", harness.parent.getId()))
                .containsEntry("parentToolCallId", "call-journey")
                .containsEntry("parentTaskId", "");
    }

    @Test
    void journeyChildExposesParentToolCallIdInsteadOfItsOwn() {
        Harness harness = harness();
        harness.parent.setToolCallId("call-journey");
        String childId = harness.service.startJourneyImageChild(
                harness.parent.getId(), 1, "image", harness.conversation,
                "雨夜街头", List.of(), List.of(), "", "", 1,
                false, "", "", "", "", "call-child-own");

        AgentTaskView child = harness.service.getTask("user-1", childId);

        assertThat(child).isNotNull();
        assertThat(child.parentTaskId()).isEqualTo(harness.parent.getId());
        assertThat(child.parentToolCallId())
                .as("旅程子任务折到父任务的工具卡下，而不是自己的 toolCallId")
                .isEqualTo("call-journey")
                .isNotEqualTo("call-child-own");
    }

    @Test
    void startJourneyTaskPersistsToolCallIdAndPublishesItInTaskViewEvents() {
        Harness harness = harness();
        String parentTaskId = harness.service.startJourneyTask(
                harness.conversation, "雨夜街头", List.of(), List.of(), "", "", "",
                "16:9", "1080p", "8", 1, false, null, "call-journey");

        assertThat(harness.tasks.get(parentTaskId).getToolCallId()).isEqualTo("call-journey");
        assertThat(harness.service.getTask("user-1", parentTaskId).parentToolCallId())
                .isEqualTo("call-journey");

        String childId = harness.service.startJourneyImageChild(
                parentTaskId, 1, "image", harness.conversation,
                "雨夜街头", List.of(), List.of(), "", "", 1,
                false, "", "", "", "", "call-child-own");
        harness.service.noteJourneyStep(parentTaskId, "image", 1, 2, childId);

        ArgumentCaptor<AgentTaskView> published = ArgumentCaptor.forClass(AgentTaskView.class);
        verify(harness.taskEvents, atLeastOnce()).publish(eq("user-1"), published.capture());

        assertThat(published.getAllValues())
                .as("旅程进度一经刷新即随任务视图事件实时下发")
                .isNotEmpty()
                .allSatisfy(view -> assertThat(view.parentToolCallId()).isEqualTo("call-journey"));
        AgentTaskView childView = published.getAllValues().stream()
                .filter(view -> childId.equals(view.taskId()))
                .findFirst()
                .orElseThrow();
        assertThat(childView.parentToolCallId()).isEqualTo("call-journey");

        // 步骤进度自身也带同一个归属 id（持久化 payload 与视图事件一致）。
        assertThat(stepPayloads(harness))
                .as("noteJourneyStep 写入的步骤 payload 必须带 parentToolCallId")
                .anySatisfy(payload -> assertThat(payload).contains("\"parentToolCallId\":\"call-journey\""));
    }

    @Test
    void nonJourneyTasksKeepParentToolCallIdBlank() {
        Harness harness = harness();
        // 非旅程父任务（批量生图）及其子行：retry 等既有父子关系不得带工具卡归属。
        AgentAsyncTask plainParent = task("plain-parent", "image-batch",
                AgentScopeImageTools.GENERATE_IMAGE_BATCH, "call-batch", null);
        AgentAsyncTask plainChild = task("plain-child", "image-batch",
                AgentScopeImageTools.GENERATE_IMAGE_BATCH, "call-batch-child", plainParent.getId());
        harness.tasks.put(plainParent.getId(), plainParent);
        harness.tasks.put(plainChild.getId(), plainChild);
        // 旅程子行但父行没有 toolCallId：也必须为空，不能落成 null 或 "null"。
        AgentAsyncTask orphanChild = task("orphan-child", "image-batch",
                AgentScopeImageTools.GENERATE_IMAGE_BATCH, "call-orphan", harness.parent.getId());
        harness.tasks.put(orphanChild.getId(), orphanChild);

        for (String taskId : List.of(plainParent.getId(), plainChild.getId(), orphanChild.getId())) {
            AgentTaskView view = harness.service.getTask("user-1", taskId);
            assertThat(view).as("task %s", taskId).isNotNull();
            assertThat(view.parentToolCallId()).as("task %s", taskId).isEmpty();
        }
        assertThat(harness.service.getStatus("user-1", plainChild.getId()))
                .containsEntry("parentToolCallId", "");
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    private record Harness(AgentScopeAsyncTaskService service,
                           AgentAsyncTaskMapper taskMapper,
                           AgentTaskEventHub taskEvents,
                           Map<String, AgentAsyncTask> tasks,
                           AgentConversation conversation,
                           AgentAsyncTask parent) {
    }

    /** 收集 {@code taskMapper.update(...)} 里写入的 JSON payload（result_json / 其它 JSON 列）。 */
    private static List<String> stepPayloads(Harness harness) {
        ArgumentCaptor<UpdateWrapper<AgentAsyncTask>> updates =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(harness.taskMapper, atLeastOnce()).update(isNull(), updates.capture());
        return updates.getAllValues().stream()
                .flatMap(wrapper -> params(wrapper).values().stream())
                .map(String::valueOf)
                .filter(text -> text.contains("\"currentStep\""))
                .toList();
    }

    private static AgentAsyncTask task(String id, String kind, String toolName,
                                       String toolCallId, String parentTaskId) {
        AgentAsyncTask task = new AgentAsyncTask();
        task.setId(id);
        task.setConversationId("conversation-1");
        task.setUserId("user-1");
        task.setSessionId("conversation-1");
        task.setKind(kind);
        task.setToolName(toolName);
        task.setToolCallId(toolCallId);
        task.setParentTaskId(parentTaskId);
        task.setStatus("queued");
        task.setStage("accepted");
        task.setProgress(0);
        task.setRequestJson("{}");
        task.setResultJson("{}");
        task.setCancelRequested(false);
        task.setVersion(0L);
        task.setUpdatedAt(OffsetDateTime.now());
        return task;
    }

    private static Harness harness() {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentGenerationService generationService = mock(AgentGenerationService.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        AgentTaskEventHub taskEvents = mock(AgentTaskEventHub.class);
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, generationService, support,
                new AgentScopeProperties(), productionService, taskEvents,
                mock(AgentScopeMessageBus.class));

        Map<String, AgentAsyncTask> tasks = new LinkedHashMap<>();
        AtomicInteger sequence = new AtomicInteger();

        when(taskMapper.insert(any(AgentAsyncTask.class))).thenAnswer(invocation -> {
            AgentAsyncTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId("task-" + sequence.incrementAndGet());
            }
            tasks.put(task.getId(), task);
            return 1;
        });
        when(taskMapper.selectById(any())).thenAnswer(invocation -> {
            // 必须先落到 Object：直接 String.valueOf(invocation.getArgument(0)) 会被推断成
            // String.valueOf(char[]) 重载，运行期抛 ClassCastException。
            Object id = invocation.getArgument(0);
            return tasks.get(String.valueOf(id));
        });
        when(taskMapper.selectOne(any())).thenAnswer(invocation -> {
            String key = params(invocation.getArgument(0)).values().stream()
                    .filter(value -> value instanceof String text && text.contains("-"))
                    .map(String::valueOf)
                    .findFirst().orElse("");
            return tasks.values().stream()
                    .filter(task -> key.equals(task.getIdempotencyKey()))
                    .findFirst().orElse(null);
        });
        when(taskMapper.selectList(any())).thenAnswer(invocation -> {
            List<Object> values = orderedParams(invocation.getArgument(0));
            String parentId = values.isEmpty() ? "" : String.valueOf(values.getFirst());
            return tasks.values().stream()
                    .filter(task -> parentId.equals(task.getParentTaskId()))
                    .toList();
        });
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(taskMapper.updateById(any(AgentAsyncTask.class))).thenAnswer(invocation -> {
            AgentAsyncTask task = invocation.getArgument(0);
            tasks.put(task.getId(), task);
            return 1;
        });

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);

        AgentAsyncTask parent = task("journey-1", "journey",
                AgentScopeJourneyTools.PRODUCE_VIDEO_JOURNEY, "", null);
        tasks.put(parent.getId(), parent);

        return new Harness(service, taskMapper, taskEvents, tasks, conversation, parent);
    }

    /**
     * 读取 MyBatis-Plus 包装器参数表；{@code eq()} 是惰性登记的，必须先渲染一次 SQL 片段。
     */
    @SuppressWarnings("rawtypes")
    private static Map<String, Object> params(Object wrapper) {
        AbstractWrapper abstractWrapper = (AbstractWrapper) wrapper;
        abstractWrapper.getSqlSegment();
        return abstractWrapper.getParamNameValuePairs();
    }

    private static List<Object> orderedParams(Object wrapper) {
        return params(wrapper).entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("MPGENVAL"))
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }
}
