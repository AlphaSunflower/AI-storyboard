package com.moon.moonagent.ai.agent.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentOrchestratorTest {

    @Test
    void runsDependenciesInOrderAndOnlyPassesDirectDependencyResults() {
        SubagentTaskGraph graph = new SubagentTaskGraph("diamond", List.of(
                task("a"),
                task("b", "a"),
                task("c", "a"),
                task("d", "b", "c")), 2, false);

        List<String> started = Collections.synchronizedList(new ArrayList<>());
        Map<String, List<String>> dependencyIds = new ConcurrentHashMap<>();
        SubagentNodeExecutor executor = (task, dependencies, cancellation) -> {
            started.add(task.id());
            dependencyIds.put(task.id(), new ArrayList<>(dependencies.keySet()));
            return SubagentTaskResult.completed(task.id(), "out-" + task.id(), 1, 1);
        };

        SubagentGraphResult result = new SubagentOrchestrator(executor).run(graph);

        assertThat(result.success()).isTrue();
        assertThat(result.results()).hasSize(4);
        assertThat(started.indexOf("a")).isLessThan(started.indexOf("b"));
        assertThat(started.indexOf("a")).isLessThan(started.indexOf("c"));
        assertThat(started.indexOf("d")).isGreaterThan(started.indexOf("b"));
        assertThat(started.indexOf("d")).isGreaterThan(started.indexOf("c"));
        assertThat(dependencyIds.get("d")).containsExactlyInAnyOrder("b", "c");
        assertThat(dependencyIds.get("b")).containsExactly("a");
    }

    @Test
    void reusesCompletedNodesWhenGraphIsRetried() {
        SubagentTaskGraph graph = new SubagentTaskGraph("resume", List.of(
                task("a"), task("b", "a")), 1, false);
        List<String> executed = new ArrayList<>();
        SubagentNodeExecutor executor = (task, dependencies, cancellation) -> {
            executed.add(task.id());
            return SubagentTaskResult.completed(task.id(), "new-" + task.id(), 1, 1);
        };
        SubagentTaskResult previous = SubagentTaskResult.completed(
                "a", "resumed-a", 2, 9);

        SubagentGraphResult result = new SubagentOrchestrator(executor)
                .run(graph, new SubagentCancellation(), Map.of("a", previous));

        assertThat(executed).containsExactly("b");
        assertThat(result.result("a").output()).isEqualTo("resumed-a");
        assertThat(result.result("b").output()).isEqualTo("new-b");
        assertThat(result.success()).isTrue();
    }

    @Test
    void runsIndependentNodesInParallel() throws Exception {
        SubagentTaskGraph graph = new SubagentTaskGraph("parallel", List.of(
                task("left"), task("right")), 2, false);
        CyclicBarrier barrier = new CyclicBarrier(2);
        SubagentNodeExecutor executor = (task, dependencies, cancellation) -> {
            barrier.await(2, TimeUnit.SECONDS);
            return SubagentTaskResult.completed(task.id(), "ok", 1, 1);
        };

        SubagentGraphResult result = new SubagentOrchestrator(executor).run(graph);

        assertThat(result.success()).isTrue();
        assertThat(result.results().values())
                .allMatch(item -> item.status() == SubagentTaskStatus.COMPLETED);
    }

    @Test
    void failedDependencySkipsOnlyItsDownstream() {
        SubagentTaskGraph graph = new SubagentTaskGraph("failure", List.of(
                task("a"),
                task("b", "a"),
                task("c")), 2, false);
        SubagentNodeExecutor executor = (task, dependencies, cancellation) ->
                "a".equals(task.id())
                        ? SubagentTaskResult.failed(task.id(), "boom", 1)
                        : SubagentTaskResult.completed(task.id(), "ok", 1, 1);

        SubagentGraphResult result = new SubagentOrchestrator(executor).run(graph);

        assertThat(result.success()).isFalse();
        assertThat(result.result("a").status()).isEqualTo(SubagentTaskStatus.FAILED);
        assertThat(result.result("b").status()).isEqualTo(SubagentTaskStatus.SKIPPED);
        assertThat(result.result("c").status()).isEqualTo(SubagentTaskStatus.COMPLETED);
    }

    @Test
    void failFastCancelsRemainingLevels() {
        SubagentTaskGraph graph = new SubagentTaskGraph("fail-fast", List.of(
                task("a"),
                task("b", "a")), 2, true);
        SubagentNodeExecutor executor = (task, dependencies, cancellation) ->
                SubagentTaskResult.failed(task.id(), "boom", 1);

        SubagentGraphResult result = new SubagentOrchestrator(executor).run(graph);

        assertThat(result.success()).isFalse();
        assertThat(result.result("a").status()).isEqualTo(SubagentTaskStatus.FAILED);
        assertThat(result.result("b").status()).isEqualTo(SubagentTaskStatus.CANCELLED);
    }

    @Test
    void preCancelledGraphIsNotExecuted() {
        SubagentTaskGraph graph = new SubagentTaskGraph("cancelled", List.of(
                task("a"), task("b", "a")), 2, false);
        SubagentCancellation cancellation = new SubagentCancellation();
        cancellation.cancel();

        SubagentGraphResult result = new SubagentOrchestrator(
                (task, dependencies, token) -> SubagentTaskResult.completed(task.id(), "should-not-run", 1, 1))
                .run(graph, cancellation);

        assertThat(result.success()).isFalse();
        assertThat(result.results().values())
                .allMatch(item -> item.status() == SubagentTaskStatus.CANCELLED);
    }

    @Test
    void executorExceptionBecomesFailedResult() {
        SubagentTaskGraph graph = new SubagentTaskGraph("exception", List.of(task("a")), 2, false);
        SubagentNodeExecutor executor = (task, dependencies, cancellation) -> {
            throw new IllegalStateException("executor down");
        };

        SubagentGraphResult result = new SubagentOrchestrator(executor).run(graph);

        assertThat(result.success()).isFalse();
        assertThat(result.result("a").status()).isEqualTo(SubagentTaskStatus.FAILED);
        assertThat(result.result("a").error()).contains("executor down");
    }

    @Test
    void notifiesStartedAndCompletedInExecutionOrder() {
        SubagentTaskGraph graph = new SubagentTaskGraph("events", List.of(
                task("a"), task("b", "a")), 2, false);
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        SubagentGraphListener listener = new SubagentGraphListener() {
            @Override
            public void onStarted(SubagentTask task) {
                events.add("start:" + task.id());
            }

            @Override
            public void onCompleted(SubagentTask task, SubagentTaskResult result) {
                events.add("done:" + task.id());
            }
        };

        new SubagentOrchestrator(
                (task, dependencies, cancellation) ->
                        SubagentTaskResult.completed(task.id(), "ok", 1, 1),
                listener).run(graph);

        assertThat(events).containsExactly("start:a", "done:a", "start:b", "done:b");
    }

    @Test
    void budgetStopsRemainingLevels() {
        SubagentTaskGraph graph = new SubagentTaskGraph("budget", List.of(
                task("a"), task("b", "a")), 2, false);
        SubagentGraphResult result = new SubagentOrchestrator(
                (task, dependencies, cancellation) ->
                        SubagentTaskResult.completed(task.id(), "ok", 1, 100),
                SubagentGraphListener.NOOP,
                new SubagentBudget(50, 0)).run(graph);

        assertThat(result.success()).isFalse();
        assertThat(result.budgetExceeded()).isTrue();
        assertThat(result.tokensUsed()).isEqualTo(100);
        assertThat(result.result("a").status()).isEqualTo(SubagentTaskStatus.COMPLETED);
        assertThat(result.result("b").status()).isEqualTo(SubagentTaskStatus.CANCELLED);
    }

    private static SubagentTask task(String id, String... dependsOn) {
        return new SubagentTask(id, "worker", "do " + id,
                List.of(dependsOn), List.of(), List.of(), 4);
    }
}
