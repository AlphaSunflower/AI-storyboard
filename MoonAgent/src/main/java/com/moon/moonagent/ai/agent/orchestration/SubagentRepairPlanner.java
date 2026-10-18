package com.moon.moonagent.ai.agent.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一轮有界修复：review 判“不通过”后，把 review 意见回灌给 script/visual，
 * 再让 reviewer 复核一次。只修一轮，仍不通过就如实返回，不无限重试。
 *
 * <p>这是偏领域的一层：当前只修 screenwriter / visual-director；research 不重跑。
 * 后续若角色更多，应该把“哪些角色可修”配置化，而不是继续堆 if。
 */
public final class SubagentRepairPlanner {

    private static final int MAX_FEEDBACK_CHARS = 6_000;

    private SubagentRepairPlanner() {
    }

    public static boolean needsRepair(SubagentTaskGraph graph, SubagentGraphResult result) {
        for (SubagentTask task : graph.nodes()) {
            if (!isReviewer(task)) continue;
            SubagentTaskResult review = result.result(task.id());
            if (review != null && review.succeeded() && isRejected(review.output())) {
                return true;
            }
        }
        return false;
    }

    /** 返回修复任务图；没有可修节点时返回 null。 */
    public static SubagentTaskGraph repairGraph(SubagentTaskGraph graph, SubagentGraphResult result) {
        return repairGraph(graph, result, "");
    }

    public static SubagentTaskGraph repairGraph(SubagentTaskGraph graph, SubagentGraphResult result,
                                                String extraFeedback) {
        List<SubagentTask> fixes = new ArrayList<>();
        Set<String> fixIds = new LinkedHashSet<>();
        Set<String> plannedFixIds = new LinkedHashSet<>();
        String scriptFixId = null;
        for (SubagentTask task : graph.nodes()) {
            if (isFixable(task)) {
                String candidate = task.id() + "-fix";
                plannedFixIds.add(candidate);
                if (task.role() != null && task.role().toLowerCase().contains("screenwriter")) {
                    scriptFixId = candidate;
                }
            }
        }
        String feedback = reviewFeedback(graph, result);
        if (extraFeedback != null && !extraFeedback.isBlank()) {
            feedback += "\n## 参数校验\n" + extraFeedback;
        }

        for (SubagentTask task : graph.nodes()) {
            if (!isFixable(task)) continue;
            String fixId = task.id() + "-fix";
            List<String> deps = new ArrayList<>();
            for (String dependency : task.dependsOn()) {
                String candidate = dependency + "-fix";
                if (plannedFixIds.contains(candidate)) {
                    deps.add(candidate);
                }
            }
            // visual 必须等 script 的修订版，否则两份稿会再次错位
            if (isVisual(task) && scriptFixId != null && !deps.contains(scriptFixId)) {
                deps.add(scriptFixId);
            }
            fixes.add(new SubagentTask(
                    fixId,
                    task.role(),
                    repairInstruction(task, result.result(task.id()), feedback),
                    List.copyOf(deps),
                    task.skills(),
                    task.tools(),
                    Math.min(task.maxTurns(), 3),
                    task.outputSchema(),
                    task.allowWrite()));
            fixIds.add(fixId);
        }
        if (fixes.isEmpty()) {
            return null;
        }

        List<SubagentTask> nodes = new ArrayList<>(fixes);
        for (SubagentTask task : graph.nodes()) {
            if (!isReviewer(task)) continue;
            nodes.add(new SubagentTask(
                    task.id() + "-2",
                    task.role(),
                    "复核以下修订版，先逐项核对参数表，再查几何与光学。\n"
                            + "只输出：通过/不通过 + 剩余冲突 + 最小修改建议。\n\n"
                            + "原始 review 规则：\n" + task.instruction(),
                    List.copyOf(fixIds),
                    task.skills(),
                    task.tools(),
                    2,
                    task.outputSchema(),
                    false));
        }
        return new SubagentTaskGraph(graph.task(), nodes, graph.maxConcurrency(), false,
                graph.context());
    }

    private static String repairInstruction(SubagentTask task, SubagentTaskResult previous,
                                            String feedback) {
        StringBuilder out = new StringBuilder();
        out.append("只修复 review 指出的问题，不要改变已通过的硬约束。\n");
        out.append("不要重新调研，不要复述旧稿或解释过程；直接给完整修订版，"
                + "并在末尾附参数表（fps/时长/画幅/色温/焦段/光圈/机位/推进/落幅）。\n");
        out.append("即使只改文字，也必须重新输出完整 parameters；禁止输出 [PARAMS]{}。\n\n");
        out.append("## 原任务\n").append(task.instruction()).append("\n\n");
        if (previous != null && previous.output() != null && !previous.output().isBlank()) {
            out.append("## 上一轮产出\n").append(truncate(previous.output())).append("\n\n");
        }
        out.append("## Review 意见\n").append(feedback);
        return out.toString();
    }

    private static String reviewFeedback(SubagentTaskGraph graph, SubagentGraphResult result) {
        StringBuilder out = new StringBuilder();
        for (SubagentTask task : graph.nodes()) {
            if (!isReviewer(task)) continue;
            SubagentTaskResult review = result.result(task.id());
            if (review != null && review.output() != null && !review.output().isBlank()) {
                out.append("### ").append(task.id()).append("\n")
                        .append(truncate(review.output())).append("\n");
            }
        }
        return out.toString();
    }

    private static boolean isRejected(String text) {
        if (text == null) return false;
        return text.contains("不通过") || text.contains("未通过")
                || text.contains("FAIL") || text.contains("fail");
    }

    private static boolean isReviewer(SubagentTask task) {
        String role = task.role() == null ? "" : task.role().toLowerCase();
        String id = task.id() == null ? "" : task.id().toLowerCase();
        return role.contains("review") || id.contains("review");
    }

    private static boolean isFixable(SubagentTask task) {
        String role = task.role() == null ? "" : task.role().toLowerCase();
        return role.contains("screenwriter") || role.contains("visual")
                || role.contains("director");
    }

    private static boolean isVisual(SubagentTask task) {
        String role = task.role() == null ? "" : task.role().toLowerCase();
        return role.contains("visual");
    }

    private static String truncate(String text) {
        return text.length() <= MAX_FEEDBACK_CHARS
                ? text : text.substring(0, MAX_FEEDBACK_CHARS) + "…";
    }
}
