package com.moon.moonagent.ai.agent.agentscope.workflow;

import com.moon.moonagent.ai.agent.agentscope.AgentScopeStructuredModels;
import com.moon.moonagent.ai.agent.agentscope.StructuredDecisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowModels.Mode.AUTO_SKILL;
import static com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowModels.Mode.HYBRID;
import static com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowModels.Mode.USER_DEFINED_WORKFLOW;

@Component
@RequiredArgsConstructor
public class AgentWorkflowIntentResolver {

    private static final Pattern STAGE = Pattern.compile(
            "(?m)^\\s*(阶段\\s*[0-9一二三四五六七八九十]+|步骤\\s*[0-9一二三四五六七八九十]+)[：:]?.*$");
    private static final Pattern COMPLETED_MARKER = Pattern.compile("【[^】]*完成】");
    private static final Pattern QUANTITY = Pattern.compile(
            "(?:共|全部|生成|分别)?\\s*(\\d{1,3})\\s*(?:个|张|镜|条)");
    private static final Pattern COMPLETE_VIDEO = Pattern.compile("完整.{0,8}视频");
    private static final Pattern EXACT_COUNT = Pattern.compile(
            "(\\d{1,3})\\s*(?:个镜头|镜|个分镜|秒)");
    private final StructuredDecisionService decisions;

    @Value("${ai.agent.workflow.custom-enabled:false}")
    private boolean customEnabled;

    public AgentWorkflowModels.Resolution resolve(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        if (!customEnabled) {
            return new AgentWorkflowModels.Resolution(AUTO_SKILL,
                    new AgentWorkflowModels.Constraint(false, false, false, List.of()),
                    List.of(), "custom workflow disabled; use standard skill workflow");
        }
        boolean hasStages = STAGE.matcher(text).find();
        boolean serial = containsAny(text, "必须串行", "不能并行", "不可并行", "顺序执行");
        boolean noSkip = containsAny(text, "不能跳阶段", "不得跳阶段", "禁止跳阶段");
        boolean markers = containsAny(text, "完成】", "完成标记", "每完成一步");
        if (hasStages && serial && noSkip) {
            return compile(text, USER_DEFINED_WORKFLOW, serial, noSkip, markers);
        }
        if (hasStages && (serial || noSkip || markers)) {
            return compile(text, HYBRID, serial, noSkip, markers);
        }

        AgentScopeStructuredModels.WorkflowDecision decision = decisions.resolveWorkflow(
                text, AgentWorkflowCapabilityCatalog.promptText());
        if (decision.mode().isBlank()) {
            return hasStages
                    ? compile(text, HYBRID, serial, noSkip, markers)
                    : new AgentWorkflowModels.Resolution(AUTO_SKILL,
                    new AgentWorkflowModels.Constraint(false, false, false, List.of()),
                    List.of(), "no explicit workflow signals");
        }
        AgentWorkflowModels.Mode mode;
        try {
            mode = AgentWorkflowModels.Mode.valueOf(decision.mode());
        } catch (Exception ignored) {
            mode = hasStages ? HYBRID : AUTO_SKILL;
        }
        List<AgentWorkflowModels.StepSpec> steps = decision.steps().stream()
                .filter(step -> AgentWorkflowCapabilityCatalog.contains(step.toolName()))
                .map(step -> new AgentWorkflowModels.StepSpec(
                        step.key(), step.title(), step.toolName(), step.dependsOn(),
                        step.repeatCount(), step.marker()))
                .toList();
        if (mode != AUTO_SKILL && steps.isEmpty()) {
            return compile(text, mode, decision.serial() || serial,
                    decision.noSkip() || noSkip, decision.requireMarkers() || markers);
        }
        return new AgentWorkflowModels.Resolution(mode,
                new AgentWorkflowModels.Constraint(
                        decision.serial() || serial,
                        decision.noSkip() || noSkip,
                        decision.requireMarkers() || markers,
                        decision.exactCounts()),
                steps, decision.rationale());
    }

    private AgentWorkflowModels.Resolution compile(
            String prompt,
            AgentWorkflowModels.Mode mode,
            boolean serial,
            boolean noSkip,
            boolean markers
    ) {
        List<String> blocks = stageBlocks(prompt);
        if (blocks.isEmpty()) blocks = List.of(prompt);
        List<AgentWorkflowModels.StepSpec> steps = new ArrayList<>();
        int requestedCount = findExactCounts(prompt).stream()
                .mapToInt(Integer::intValue)
                .filter(value -> value >= 2 && value <= 20)
                .findFirst().orElse(0);
        String previous = "";
        for (int index = 0; index < blocks.size(); index++) {
            String block = blocks.get(index);
            String key = "step-" + (index + 1);
            String title = title(block, "步骤 " + (index + 1));
            String marker = marker(block, title);
            String tool = toolFor(block, index, blocks.size());
            int repeatCount = repeatable(tool) ? repeatCount(block) : 1;
            if (repeatCount == 1 && requestedCount > 0 && repeatable(tool)) {
                repeatCount = requestedCount;
            }
            steps.add(new AgentWorkflowModels.StepSpec(
                    key, title, tool, previous.isBlank() ? List.of() : List.of(previous),
                    repeatCount, marker));
            previous = key;
        }
        return new AgentWorkflowModels.Resolution(mode,
                new AgentWorkflowModels.Constraint(serial, noSkip, markers,
                        findExactCounts(prompt)), steps,
                "compiled from explicit user workflow");
    }

    private static List<String> stageBlocks(String prompt) {
        Matcher matcher = STAGE.matcher(prompt);
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) starts.add(matcher.start());
        if (starts.isEmpty()) return List.of();
        List<String> blocks = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            int start = starts.get(index);
            int end = index + 1 < starts.size() ? starts.get(index + 1) : prompt.length();
            String block = prompt.substring(start, end).trim();
            if (!block.isBlank()) blocks.add(block);
        }
        return blocks;
    }

    private static String title(String block, String fallback) {
        String first = block.lines().findFirst().orElse(fallback).trim();
        first = first.replaceFirst("^[#>*\\-\\s]+", "");
        return first.length() > 40 ? first.substring(0, 40) : first;
    }

    private static String marker(String block, String title) {
        Matcher matcher = COMPLETED_MARKER.matcher(block);
        return matcher.find() ? matcher.group() : "【" + title + "完成】";
    }

    private static int repeatCount(String block) {
        Matcher matcher = QUANTITY.matcher(block);
        int maximum = 1;
        while (matcher.find()) {
            maximum = Math.max(maximum, Integer.parseInt(matcher.group(1)));
        }
        return Math.min(maximum, 20);
    }

    private static String toolFor(String block, int index, int total) {
        String value = block.toLowerCase(Locale.ROOT);
        if (containsAny(value, "合并", "拼接", "成片")
                || COMPLETE_VIDEO.matcher(value).find()) {
            return "merge_storyboard_videos";
        }
        if (containsAny(value, "提示词", "prompt") && !containsAny(value, "生成图片", "绘图")) {
            return "list_storyboard_draft";
        }
        if (containsAny(value, "视频", "video") && !containsAny(value, "分镜表")) {
            return "generate_storyboard_video";
        }
        if (containsAny(value, "图片", "首帧", "尾帧", "绘图", "image")) {
            return "generate_storyboard_image";
        }
        if (containsAny(value, "分镜", "镜头", "表格", "提示词", "剧本")) {
            return "start_storyboard_production";
        }
        return index == total - 1 ? "start_storyboard_production" : "list_project_storyboard";
    }

    private static boolean repeatable(String tool) {
        return "generate_storyboard_image".equals(tool)
                || "generate_storyboard_video".equals(tool)
                || "generate_image".equals(tool)
                || "generate_video".equals(tool);
    }

    private static List<Integer> findExactCounts(String prompt) {
        Matcher matcher = EXACT_COUNT.matcher(prompt);
        List<Integer> counts = new ArrayList<>();
        while (matcher.find()) counts.add(Integer.parseInt(matcher.group(1)));
        return List.copyOf(counts);
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null) return false;
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }
}
