package com.moon.moonagent.ai.agent.agentscope;

import java.util.ArrayList;
import java.util.List;

/**
 * 复合旅程的分支判定与步骤清单。
 *
 * <p>纯函数组件：只依赖调用方传入的阈值，不注入任何 Spring Bean，因此
 * 「分支选择」和「确认卡片里枚举的具体步骤」都可以脱离运行时被断言。
 *
 * <p>两条分支：
 * <ul>
 *   <li>{@link Branch#SHORT}：生图 → 视频（目标时长 ≤ 阈值）</li>
 *   <li>{@link Branch#LONG}：分镜 → 逐镜图片 → 逐镜视频 → 合成（目标时长 &gt; 阈值，
 *       或用户显式要求走分镜）</li>
 * </ul>
 */
public final class AgentScopeJourneyPlanner {

    /** 旅程分支。 */
    public enum Branch {
        /** 生图 → 视频。 */
        SHORT,
        /** 分镜 → 逐镜图片 → 逐镜视频 → 合成。 */
        LONG
    }

    /**
     * 一个可在确认卡片里枚举的具体步骤。
     *
     * @param order     步骤序号，从 1 开始
     * @param kind      机器可读步骤类型（{@code image} / {@code video} / {@code storyboard} /
     *                  {@code storyboard_image} / {@code storyboard_video} / {@code merge}）
     * @param title     面向用户的步骤标题
     * @param unitCount 该步骤的操作次数（图片张数 / 镜头数 / 片段数）
     * @param unitLabel 次数单位（张 / 个镜头 / 个片段）
     */
    public record JourneyStep(int order, String kind, String title,
                              int unitCount, String unitLabel) {

        /** 单步的枚举文案，如「① 生成 3 张图片」。 */
        public String display() {
            StringBuilder out = new StringBuilder(circled(order)).append(' ').append(title);
            if (unitCount > 0 && !unitLabel.isBlank()) {
                out.append(' ').append(unitCount).append(' ').append(unitLabel);
            }
            return out.toString();
        }
    }

    /**
     * 一次旅程的完整计划。
     *
     * @param branch             分支
     * @param requestedSeconds   解析出的目标时长（秒）；未知为 0
     * @param thresholdSeconds   本次生效的阈值（秒）
     * @param explicitStoryboard 用户是否显式要求分镜流程
     * @param steps              按顺序枚举的具体步骤
     */
    public record JourneyPlan(Branch branch, int requestedSeconds, int thresholdSeconds,
                              boolean explicitStoryboard, List<JourneyStep> steps) {

        public JourneyPlan {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public boolean longBranch() {
            return branch == Branch.LONG;
        }

        /** 步骤枚举文案，每行一步。 */
        public String summaryLines() {
            StringBuilder out = new StringBuilder();
            for (JourneyStep step : steps) {
                if (!out.isEmpty()) out.append('\n');
                out.append(step.display());
            }
            return out.toString();
        }
    }

    private AgentScopeJourneyPlanner() {
    }

    /**
     * 判定分支并生成步骤清单。
     *
     * @param requestedDuration        目标时长（可空，接受纯数字或含中文的时长描述）
     * @param explicitStoryboard       用户是否显式要求分镜流程
     * @param directVideoMaxSeconds    生效阈值（来自 {@code ai.agent.agentscope.journey-direct-video-max-seconds}）
     * @param imageCount               短分支要生成的图片数量（至少 1）
     * @param shotCount                长分支预计镜头数（未知传 0，仅用于文案）
     */
    public static JourneyPlan plan(String requestedDuration,
                                   boolean explicitStoryboard,
                                   int directVideoMaxSeconds,
                                   int imageCount,
                                   int shotCount) {
        int threshold = Math.max(1, directVideoMaxSeconds);
        Integer requested = parseSeconds(requestedDuration);
        int resolvedSeconds = requested == null ? 0 : Math.max(0, requested);
        // 时长未知时默认走短分支：只有「明确超过阈值」或「显式要求分镜」才升级成长分支。
        boolean longBranch = explicitStoryboard
                || (requested != null && requested > threshold);
        List<JourneyStep> steps = longBranch
                ? longSteps(shotCount)
                : shortSteps(Math.max(1, imageCount));
        return new JourneyPlan(
                longBranch ? Branch.LONG : Branch.SHORT,
                resolvedSeconds,
                threshold,
                explicitStoryboard,
                steps);
    }

    /** 分支判定（不生成步骤清单，供只关心分支的调用方使用）。 */
    public static Branch branch(String requestedDuration,
                                boolean explicitStoryboard,
                                int directVideoMaxSeconds) {
        return plan(requestedDuration, explicitStoryboard, directVideoMaxSeconds, 1, 0).branch();
    }

    private static List<JourneyStep> shortSteps(int imageCount) {
        List<JourneyStep> steps = new ArrayList<>();
        steps.add(new JourneyStep(1, "image", "生成", Math.max(1, imageCount), "张图片"));
        steps.add(new JourneyStep(2, "video", "生成视频", 0, ""));
        return steps;
    }

    private static List<JourneyStep> longSteps(int shotCount) {
        int count = Math.max(0, shotCount);
        List<JourneyStep> steps = new ArrayList<>();
        steps.add(new JourneyStep(1, "storyboard", "生成分镜", count, "个镜头"));
        steps.add(new JourneyStep(2, "storyboard_image", "逐镜配图", count, "个镜头"));
        steps.add(new JourneyStep(3, "storyboard_video", "逐镜视频", count, "个镜头"));
        steps.add(new JourneyStep(4, "merge", "合成成片", count, "个片段"));
        return steps;
    }

    /** 解析目标时长：接受「12」「12秒」「12s」，以及中文数字描述（交由 {@code SceneDurationPlanner}）。 */
    public static Integer parseSeconds(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        Integer planned = com.moon.moonagent.ai.agent.storyboard.SceneDurationPlanner
                .parseTotalSeconds(text);
        if (planned != null) return planned;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(?:秒|s|sec|seconds?)?\\s*$",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (!matcher.matches()) return null;
        try {
            return (int) Math.round(Double.parseDouble(matcher.group(1)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 中文枚举序号（①…⑳，超出后退化为「21.」）。 */
    static String circled(int order) {
        String[] marks = {"①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
                "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳"};
        if (order >= 1 && order <= marks.length) return marks[order - 1];
        return order + ".";
    }

    /** 分支的中文标签，供工具结果与确认文案共用。 */
    public static String branchLabel(Branch branch) {
        return branch == Branch.LONG
                ? "分镜 → 逐镜图片 → 逐镜视频 → 合成"
                : "生图 → 视频";
    }

    /** 机器可读分支名。 */
    public static String branchName(Branch branch) {
        return branch == Branch.LONG ? "long" : "short";
    }

    /** 判定说明文案，解释为什么走这条分支。 */
    public static String branchReason(JourneyPlan plan) {
        String prefix = plan.branch() == Branch.LONG ? "long-branch" : "short-branch";
        String locale = plan.branch() == Branch.LONG
                ? (plan.explicitStoryboard() ? "explicit-storyboard" : "duration-over-threshold")
                : "duration-within-threshold";
        String duration = plan.requestedSeconds() > 0
                ? plan.requestedSeconds() + "s" : "unknown";
        return prefix + ":" + locale
                + " (duration=" + duration
                + ", threshold=" + plan.thresholdSeconds() + "s)";
    }

    /** 供确认卡片渲染的完整方案文本（标题 + 逐条步骤 + 逐条参数）。 */
    public static String confirmationSummary(JourneyPlan plan, int imageCount,
                                             String duration, String aspectRatio,
                                             int shotCount) {
        StringBuilder out = new StringBuilder();
        out.append("复合旅程：").append(branchLabel(plan.branch()));
        out.append("\n阈值：").append(plan.thresholdSeconds()).append(" 秒")
                .append("；请求时长：")
                .append(duration == null || duration.isBlank() ? "未指定" : duration);
        out.append("\n执行步骤：");
        for (JourneyStep step : plan.steps()) {
            out.append("\n  ").append(step.display());
        }
        out.append("\n参数：图片 ").append(Math.max(1, imageCount)).append(" 张");
        if (aspectRatio != null && !aspectRatio.isBlank()) {
            out.append("；画幅 ").append(aspectRatio);
        }
        if (plan.branch() == Branch.LONG && shotCount > 0) {
            out.append("；镜头 ").append(shotCount).append(" 个");
        }
        out.append("\n批准后仅执行以上列出的步骤；新增操作需再次确认。");
        return out.toString();
    }
}
