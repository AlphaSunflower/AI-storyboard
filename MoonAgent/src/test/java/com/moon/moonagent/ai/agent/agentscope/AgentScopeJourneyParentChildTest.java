package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionService;
import com.moon.moonagent.ai.agent.task.AgentTaskEventHub;
import com.moon.moonagent.entity.AgentAsyncTask;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复合旅程的父子任务关系与取消传播。
 *
 * <p>全部断言通过真实调用 {@code AgentScopeAsyncTaskService} 的父子方法完成：
 * 子行由 service 写入，父任务取消复用 {@code cancelTask} → {@code releaseChildren}
 * → {@code releaseChildTask} 的既有链路。
 */
class AgentScopeJourneyParentChildTest {

    /**
     * LambdaQueryWrapper 的列名解析（{@code SFunction} → 列名）需要 MyBatis-Plus 的 lambda 缓存，
     * 只有渲染 SQL 片段时才会触发；纯单测里必须先初始化实体元数据，否则 {@code getSqlSegment()} 抛
     * "can not find lambda cache"。与仓库既有测试 {@code ConversationTitleServiceImplTest} 同一做法。
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
    void journeyChildStartsAsWaitingParentAndIsNotSubmitted() {
        Harness harness = harness();

        String childId = harness.service.startJourneyImageChild(
                harness.parent.getId(), 1, "image", harness.conversation,
                "雨夜街头", List.of(), List.of(), "", "", 1,
                false, "", "", "", "", "call-image");

        AgentAsyncTask child = harness.tasks.get(childId);
        assertThat(child).isNotNull();
        assertThat(child.getParentTaskId()).isEqualTo(harness.parent.getId());
        assertThat(child.getStatus()).isEqualTo("waiting_parent");
        assertThat(child.getStage()).isEqualTo("waiting_parent");
        assertThat(child.getKind()).isEqualTo("image-batch");
        assertThat(harness.service.parentCancelled(harness.parent.getId())).isFalse();
    }

    @Test
    void journeyChildIdempotencyKeyIsStableForSameStep() {
        Harness harness = harness();

        String first = harness.service.startJourneyImageChild(
                harness.parent.getId(), 1, "image", harness.conversation,
                "雨夜街头", List.of(), List.of(), "", "", 1,
                false, "", "", "", "", "call-image");
        String second = harness.service.startJourneyImageChild(
                harness.parent.getId(), 1, "image", harness.conversation,
                "雨夜街头", List.of(), List.of(), "", "", 1,
                false, "", "", "", "", "call-image");

        assertThat(second).isEqualTo(first);
        assertThat(harness.stored()).hasSize(2); // 父 + 1 个子行
    }

    @Test
    void releasingChildMovesWaitingParentToQueued() {
        Harness harness = harness();
        String childId = harness.service.startJourneyImageChild(
                harness.parent.getId(), 1, "image", harness.conversation,
                "雨夜街头", List.of(), List.of(), "", "", 1,
                false, "", "", "", "", "call-image");

        boolean released = harness.service.releaseChildTask(childId);

        assertThat(released).isTrue();
        // 断言放行写入本身，而不是采样字段：写完之后 service 会立刻 submit 该子行，
        // 后台线程随即 claim 成 running，「此刻恰好是 queued」并不是稳定契约。
        assertThat(harness.releaseOf(childId)).isNotNull();
        assertThat(harness.releaseOf(childId).status()).isEqualTo("queued");
        assertThat(harness.releaseOf(childId).stage()).isEqualTo("accepted");
        assertThat(harness.tasks.get(childId).getStatus()).isNotEqualTo("waiting_parent");
        // 幂等：第二次释放不再迁移。
        assertThat(harness.service.releaseChildTask(childId)).isFalse();
    }

    @Test
    void cancellingParentCancelsChildrenInsteadOfRunningThem() {
        Harness harness = harness();
        String imageChild = harness.service.startJourneyImageChild(
                harness.parent.getId(), 1, "image", harness.conversation,
                "雨夜街头", List.of(), List.of(), "", "", 1,
                false, "", "", "", "", "call-image");
        String videoChild = harness.service.startJourneyImageChild(
                harness.parent.getId(), 2, "video", harness.conversation,
                "雨夜街头", List.of(), List.of(), "8", "", 1,
                false, "", "", "", "", "call-video");

        harness.service.cancelTask("user-1", harness.parent.getId());

        assertThat(harness.tasks.get(harness.parent.getId()).getStatus()).isEqualTo("cancelled");
        for (String childId : List.of(imageChild, videoChild)) {
            assertThat(harness.tasks.get(childId).getStatus())
                    .as("child %s must be cancelled with its parent", childId)
                    .isEqualTo("cancelled");
            assertThat(harness.tasks.get(childId).getCompletedAt()).isNotNull();
        }
        assertThat(harness.service.parentCancelled(harness.parent.getId())).isTrue();
    }

    @Test
    void completingParentReleasesStillWaitingChildrenToQueued() {
        Harness harness = harness();
        String childId = harness.service.startJourneyImageChild(
                harness.parent.getId(), 1, "image", harness.conversation,
                "雨夜街头", List.of(), List.of(), "", "", 1,
                false, "", "", "", "", "call-image");
        // 手工把父任务置为运行中，模拟 runner 已接手。
        harness.tasks.get(harness.parent.getId()).setStatus("running");

        harness.completeJourney(harness.parent.getId());

        assertThat(harness.tasks.get(harness.parent.getId()).getStatus()).isEqualTo("completed");
        // 父任务完成时子行仍在 waiting_parent，收口必须把它放行（queued/accepted），
        // 并由 service 立即 submit 出去。断言对象是这次放行写入，不是随后被 claim 的瞬时状态。
        assertThat(harness.releaseOf(childId)).isNotNull();
        assertThat(harness.releaseOf(childId).status()).isEqualTo("queued");
        assertThat(harness.releaseOf(childId).stage()).isEqualTo("accepted");
        assertThat(harness.tasks.get(childId).getStatus()).isNotEqualTo("waiting_parent");
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    private record Harness(AgentScopeAsyncTaskService service,
                           AgentAsyncTaskMapper taskMapper,
                           AgentConversationMapper conversationMapper,
                           Map<String, AgentAsyncTask> tasks,
                           Map<String, Release> releases,
                           AgentConversation conversation,
                           AgentAsyncTask parent) {

        List<AgentAsyncTask> stored() {
            return new ArrayList<>(tasks.values());
        }

        /**
         * 放行写入瞬间的状态快照。
         *
         * <p>{@code releaseChildTask} 写完 {@code waiting_parent → queued/accepted} 后会立刻
         * {@code submit} 该子行，后台线程随即 {@code claim} 成 {@code running}。
         * 因此「子行是否被放行为 queued/accepted」只能断言这次写入本身，
         * 不能去采样一个已被并发任务接管的字段。
         */
        Release releaseOf(String childTaskId) {
            return releases.get(childTaskId);
        }

        /** 父任务进入终态：复用 service 的既有收口方法。 */
        void completeJourney(String parentTaskId) {
            ReflectionTestUtils.invokeMethod(service, "completeJourneyTask",
                    parentTaskId, "旅程完成", List.of("asset-1"), List.of("https://cdn/video.mp4"));
        }
    }

    /** 一次 {@code waiting_parent → queued} 放行写入留下的证据。 */
    private record Release(String status, String stage) {
    }

    private static Harness harness() {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentGenerationService generationService = mock(AgentGenerationService.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        AgentScopeProperties properties = new AgentScopeProperties();
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, generationService, support,
                properties, productionService, new AgentTaskEventHub(),
                mock(AgentScopeMessageBus.class));

        Map<String, AgentAsyncTask> tasks = new LinkedHashMap<>();
        Map<String, Release> releases = new LinkedHashMap<>();
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger updateCalls = new AtomicInteger();

        // 内存任务表：只实现复合旅程父子流程真正用到的那部分 mapper 语义。
        when(taskMapper.insert(any(AgentAsyncTask.class))).thenAnswer(invocation -> {
            AgentAsyncTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId("task-" + sequence.incrementAndGet());
            }
            tasks.put(task.getId(), task);
            return 1;
        });
        when(taskMapper.selectById(any())).thenAnswer(invocation -> {
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
            String status = values.size() > 1 ? String.valueOf(values.get(1)) : "";
            return tasks.values().stream()
                    .filter(task -> parentId.equals(task.getParentTaskId()))
                    .filter(task -> status.isBlank() || status.equals(task.getStatus()))
                    .toList();
        });
        when(taskMapper.update(any(), any())).thenAnswer(invocation -> {
            updateCalls.incrementAndGet();
            Map<String, Object> pairs = params(invocation.getArgument(1));
            // 定位目标行：按参数值匹配任务 ID，避免依赖 MyBatis-Plus 的参数占位符编号
            // （set() 是立即登记、eq() 是渲染 SQL 片段时才登记，编号顺序不稳定）。
            AgentAsyncTask task = tasks.values().stream()
                    .filter(candidate -> candidate.getId() != null
                            && pairs.containsValue(candidate.getId()))
                    .findFirst().orElse(null);
            if (task == null) return 0;
            // 条件列（parent_task_id / status）也混在 paramNameValuePairs 里，按值精确落位。
            if (pairs.containsValue("waiting_parent") && "waiting_parent".equals(task.getStatus())) {
                Release release = new Release("queued", "accepted");
                releases.put(task.getId(), release);
                task.setStatus(release.status());
                task.setStage(release.stage());
                task.setProgress(0);
                task.setCompletedAt(null);
            }
            if (pairs.containsValue("cancelled") && !"cancelled".equals(task.getStatus())) {
                task.setStatus("cancelled");
                task.setStage("cancelled");
                task.setCompletedAt(OffsetDateTime.now());
            }
            if (pairs.containsValue("running") && "queued".equals(task.getStatus())) {
                task.setStatus("running");
            }
            if (pairs.containsValue("completed")) {
                task.setStatus("completed");
                task.setStage("completed");
                task.setProgress(100);
                task.setCompletedAt(OffsetDateTime.now());
            }
            task.setUpdatedAt(OffsetDateTime.now());
            return 1;
        });
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

        AgentAsyncTask parent = new AgentAsyncTask();
        parent.setId("journey-1");
        parent.setConversationId("conversation-1");
        parent.setUserId("user-1");
        parent.setSessionId("conversation-1");
        parent.setToolName(AgentScopeJourneyTools.PRODUCE_VIDEO_JOURNEY);
        parent.setKind("journey");
        parent.setStatus("queued");
        parent.setStage("accepted");
        parent.setProgress(0);
        parent.setRequestJson("{\"steps\":[],\"branch\":\"short\"}");
        parent.setResultJson("{}");
        parent.setCancelRequested(false);
        parent.setVersion(0L);
        parent.setUpdatedAt(OffsetDateTime.now());
        tasks.put(parent.getId(), parent);

        return new Harness(service, taskMapper, conversationMapper, tasks, releases,
                conversation, parent);
    }

    /**
     * 读取 MyBatis-Plus 条件包装器里的参数表。
     *
     * <p>{@code getParamNameValuePairs()} 声明在 {@code AbstractWrapper} 上而不是 {@code Wrapper}，
     * 这里用原始类型读取，避免 {@code AbstractWrapper<T, R, Children>} 自引用泛型带来的写法噪音。
     *
     * <p>关键点：{@code eq()} 登记参数是惰性的（{@code formatParam} 在渲染 SQL 片段时才执行），
     * 因此必须先渲染一次 {@code getSqlSegment()}，否则拿到的是一张空表。
     */
    @SuppressWarnings("rawtypes")
    private static Map<String, Object> params(Object wrapper) {
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper abstractWrapper =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper) wrapper;
        abstractWrapper.getSqlSegment();
        return abstractWrapper.getParamNameValuePairs();
    }

    /**
     * 条件参数按登记顺序返回。
     *
     * <p>{@code paramNameValuePairs} 是 {@code HashMap}，{@code values()} 的迭代顺序与登记顺序无关；
     * 参数键固定为 {@code MPGENVAL1..n}，因此按键名排序才能还原 {@code eq(...)} 的书写顺序。
     */
    private static List<Object> orderedParams(Object wrapper) {
        return params(wrapper).entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("MPGENVAL"))
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }
}
