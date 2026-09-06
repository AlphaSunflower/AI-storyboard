package com.moon.moonagent.ai.agent;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 分镜链上下文续流规则（纯逻辑，供 PlanGraph 与单测共用）。
 *
 * <p>背景：用户一开始说「分镜」，随后在分镜澄清卡自定义输入「根据这个资产做一个多图转场」，
 * 现有 resume_route 会把该自定义文本重新识别为视频并切链，导致用户被拉去生成视频。
 * 本规则只允许全局意图菜单与显式「换个话题」切链；链内澄清输入一律延续当前分镜链。
 */
public final class AgentIntentContinuity {

    /** 分镜链内的人工澄清/确认卡 action（这些卡的自定义输入应留在分镜链） */
    private static final Set<String> AISPLIT_PENDING_ACTIONS = Set.of(
            "requirement-clarify",
            "script-source",
            "storyboard-plan",
            "scene-mode",
            "clarify-option",
            "scene-regenerate",
            "scene-select",
            "asset-selection",
            "asset-gate"
    );

    /** 明确表示切换其它创作意图的关键词（出现任一 → 不自动延续分镜） */
    private static final Pattern EXPLICIT_SWITCH = Pattern.compile(
            "生成视频|做视频|视频方案|动画片|做动画|短片|生成图片|画一张|画个|海报|插画|改图|修图|"
                    + "查看分镜|列出分镜|看分镜|分镜列表|有多少分镜|删除分镜|清空分镜|"
                    + "分析.{0,6}分镜|处理.{0,6}分镜|审查.{0,6}分镜|展示.{0,6}分镜|"
                    + "分镜的图片|分镜的视频|生成分镜的图片|给分镜生|分镜优化|优化方案|审查结果|"
                    + "删掉|删除|清空|清除");

    /** 分镜/剧本/故事相关词 */
    private static final Pattern STORYBOARD_HINT = Pattern.compile("分镜|故事板|剧本|故事|剧情");

    private AgentIntentContinuity() {}

    /** 当前待处理 checkpoint 是否属于分镜链澄清。 */
    public static boolean isAisplitPendingAction(String action) {
        return action != null && AISPLIT_PENDING_ACTIONS.contains(action.trim());
    }

    /**
     * 判断当前用户输入是否应延续分镜链。
     *
     * @param content      本轮用户输入
     * @param pendingAction 会话待处理 checkpoint.action（可为空）
     * @param recentAisplit 最近用户消息是否命中分镜意图
     */
    public static boolean shouldContinueAisplit(String content, String pendingAction, boolean recentAisplit) {
        boolean pendingAisplit = isAisplitPendingAction(pendingAction);
        if (!pendingAisplit && !recentAisplit) return false;
        if (content == null || content.isBlank()) return true;
        String q = content.trim();
        // 用户明确说了生成视频/图片/查看/删除等 → 尊重显式切换，不硬续分镜
        if (EXPLICIT_SWITCH.matcher(q).find()) return false;
        // 有 pending 分镜澄清卡时：只要没有显式切链，就按对当前澄清的回答处理。
        // 仅靠历史（无 pending）时保守一点，仍需含分镜/故事/剧情词。
        if (pendingAisplit) return true;
        return STORYBOARD_HINT.matcher(q).find();
    }
}
