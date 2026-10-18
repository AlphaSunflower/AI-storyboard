package com.moon.moonagent.ai.agent.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 通用子智能体任务图调度器。
 *
 * <p>按依赖分层，同层并行；每个节点只拿到「指令 + 直接依赖的结果」，不共享父级完整上下文。
 * 单节点失败默认只跳过它的下游，兄弟节点继续；{@code failFast=true} 时取消剩余全部节点。
 *
 * <p>执行器由外部注入：真实实现构造受限上下文的子 Agent，测试可注入任意函数。
 */
public final class SubagentOrchestrator {

    private final SubagentNodeExecutor executor;
    private final SubagentGraphListener listener;
    private final SubagentBudget budget;

    public SubagentOrchestrator(SubagentNodeExecutor executor) {
        this(executor, SubagentGraphListener.NOOP, SubagentBudget.UNLIMITED);
    }

    public SubagentOrchestrator(SubagentNodeExecutor executor, SubagentGraphListener listener) {
        this(executor, listener, SubagentBudget.UNLIMITED);
    }

    public SubagentOrchestrator(SubagentNodeExecutor executor, SubagentGraphListener listener,
                                SubagentBudget budget) {
        if (executor == null) {
            throw new IllegalArgumentException("SubagentNodeExecutor 不能为空");
        }
        this.executor = executor;
        this.listener = listener == null ? SubagentGraphListener.NOOP : listener;
        this.budget = budget == null ? SubagentBudget.UNLIMITED : budget;
    }

    public SubagentGraphResult run(SubagentTaskGraph graph) {
        return run(graph, new SubagentCancellation());
    }

    public SubagentGraphResult run(SubagentTaskGraph graph, SubagentCancellation cancellation) {
        return run(graph, cancellation, Map.of());
    }

    /**
     * 带“已完成节点”恢复的运行：同 graphId 的 COMPLETED 节点直接复用输出，
     * 只重跑失败/未完成节点，避免重试时重复消耗 token 或重复写入。
     */
    public SubagentGraphResult run(SubagentTaskGraph graph, SubagentCancellation cancellation,
                                   Map<String, SubagentTaskResult> resumed) {
        SubagentCancellation token = cancellation == null ? new SubagentCancellation() : cancellation;
        List<List<SubagentTask>> levels = SubagentGraphValidator.validateAndLevels(graph);
        Map<String, SubagentTaskResult> results = new ConcurrentHashMap<>();
        if (resumed != null) {
            for (SubagentTask task : graph.nodes()) {
                SubagentTaskResult done = resumed.get(task.id());
                if (done != null && done.succeeded()) {
                    results.put(task.id(), done);
                }
            }
        }
        long startedAt = System.currentTimeMillis();
        int tokensUsed = 0;
        boolean budgetExceeded = false;
        boolean success = true;
        ExecutorService pool = Executors.newFixedThreadPool(
                graph.maxConcurrency(),
                Thread.ofVirtual().name("subagent-", 0).factory());
        try {
            for (int levelIndex = 0; levelIndex < levels.size(); levelIndex++) {
                if (token.isCancelled()) {
                    markRemaining(levels, levelIndex, results, SubagentTaskStatus.CANCELLED);
                    success = false;
                    break;
                }
                if (budget.exhausted(tokensUsed, System.currentTimeMillis() - startedAt)) {
                    token.cancel();
                    budgetExceeded = true;
                    markRemaining(levels, levelIndex, results, SubagentTaskStatus.CANCELLED);
                    success = false;
                    break;
                }
                List<SubagentTask> level = levels.get(levelIndex);
                List<RunningTask> running = new ArrayList<>();
                for (SubagentTask task : level) {
                    SubagentTaskResult done = results.get(task.id());
                    if (done != null && done.succeeded()) {
                        notifyCompleted(task, done);
                        continue;
                    }
                    String problem = dependencyProblem(task, results);
                    if (problem != null) {
                        SubagentTaskResult skipped = SubagentTaskResult.skipped(task.id(), problem);
                        results.put(task.id(), skipped);
                        notifyCompleted(task, skipped);
                        continue;
                    }
                    Map<String, SubagentTaskResult> dependencies = dependencies(task, results);
                    notifyStarted(task);
                    running.add(new RunningTask(task,
                            CompletableFuture.supplyAsync(
                                    () -> executeSafely(task, dependencies, token, graph.context()), pool)));
                }
                for (RunningTask task : running) {
                    SubagentTaskResult completed = task.future().join();
                    results.put(task.task().id(), completed);
                    notifyCompleted(task.task(), completed);
                }
                for (SubagentTask task : level) {
                    SubagentTaskResult result = results.get(task.id());
                    if (result != null) {
                        tokensUsed += result.tokens();
                    }
                }
                if (budget.exhausted(tokensUsed, System.currentTimeMillis() - startedAt)) {
                    token.cancel();
                    budgetExceeded = true;
                    markRemaining(levels, levelIndex + 1, results, SubagentTaskStatus.CANCELLED);
                    success = false;
                    break;
                }
                if (graph.failFast() && hasFailure(level, results)) {
                    token.cancel();
                    markRemaining(levels, levelIndex + 1, results, SubagentTaskStatus.CANCELLED);
                    success = false;
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (results.size() < graph.nodes().size()) {
            markRemaining(levels, 0, results, SubagentTaskStatus.CANCELLED);
        }
        success = success && results.size() == graph.nodes().size()
                && results.values().stream().allMatch(SubagentTaskResult::succeeded);
        return new SubagentGraphResult(graph.task(), results, success, tokensUsed,
                System.currentTimeMillis() - startedAt, budgetExceeded);
    }

    private SubagentTaskResult executeSafely(SubagentTask task,
                                             Map<String, SubagentTaskResult> dependencies,
                                             SubagentCancellation token,
                                             String context) {
        if (token.isCancelled()) {
            return SubagentTaskResult.cancelled(task.id());
        }
        long started = System.nanoTime();
        try {
            SubagentTaskResult result = executor.execute(task, dependencies, token, context);
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            if (result == null) {
                return SubagentTaskResult.failed(task.id(), "子智能体未返回结果", durationMs);
            }
            if (!task.id().equals(result.taskId())) {
                return new SubagentTaskResult(task.id(), result.status(), result.output(),
                        result.error(), result.durationMs(), result.tokens());
            }
            return result;
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            return SubagentTaskResult.failed(task.id(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    durationMs);
        }
    }

    private static String dependencyProblem(SubagentTask task,
                                            Map<String, SubagentTaskResult> results) {
        for (String dependency : task.dependsOn()) {
            SubagentTaskResult result = results.get(dependency);
            if (result == null) {
                return "依赖未完成: " + dependency;
            }
            if (!result.succeeded()) {
                return "依赖失败: " + dependency + " (" + result.status() + ")";
            }
        }
        return null;
    }

    private static Map<String, SubagentTaskResult> dependencies(
            SubagentTask task, Map<String, SubagentTaskResult> results) {
        Map<String, SubagentTaskResult> dependencies = new LinkedHashMap<>();
        for (String dependency : task.dependsOn()) {
            SubagentTaskResult result = results.get(dependency);
            if (result != null) {
                dependencies.put(dependency, result);
            }
        }
        return Map.copyOf(dependencies);
    }

    private static boolean hasFailure(List<SubagentTask> level,
                                      Map<String, SubagentTaskResult> results) {
        for (SubagentTask task : level) {
            SubagentTaskResult result = results.get(task.id());
            if (result != null && result.status() == SubagentTaskStatus.FAILED) {
                return true;
            }
        }
        return false;
    }

    private static void markRemaining(List<List<SubagentTask>> levels, int fromLevel,
                                      Map<String, SubagentTaskResult> results,
                                      SubagentTaskStatus status) {
        for (int i = Math.max(0, fromLevel); i < levels.size(); i++) {
            for (SubagentTask task : levels.get(i)) {
                results.putIfAbsent(task.id(), status == SubagentTaskStatus.CANCELLED
                        ? SubagentTaskResult.cancelled(task.id())
                        : SubagentTaskResult.skipped(task.id(), "未调度"));
            }
        }
    }

    private void notifyStarted(SubagentTask task) {
        try {
            listener.onStarted(task);
        } catch (RuntimeException ignored) {
            // 进度监听失败不能影响调度
        }
    }

    private void notifyCompleted(SubagentTask task, SubagentTaskResult result) {
        try {
            listener.onCompleted(task, result);
        } catch (RuntimeException ignored) {
            // 进度监听失败不能影响调度
        }
    }

    private record RunningTask(SubagentTask task,
                               CompletableFuture<SubagentTaskResult> future) {
    }
}
