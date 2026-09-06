package com.moon.moonagent.ai.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIntentContinuityTest {

    @Test
    void shouldContinueAisplit_whenPendingClarifyAndStoryboardWords() {
        assertTrue(AgentIntentContinuity.shouldContinueAisplit(
                "根据这个资产做一个多图转场", "requirement-clarify", false));
        assertTrue(AgentIntentContinuity.shouldContinueAisplit(
                "继续生成分镜", "", true));
    }

    @Test
    void shouldContinueAisplit_returnsTrueForBlankAnswerInAisplitContext() {
        assertTrue(AgentIntentContinuity.shouldContinueAisplit("", "scene-regenerate", false));
    }

    @Test
    void shouldNotContinueAisplit_whenExplicitVideo() {
        assertFalse(AgentIntentContinuity.shouldContinueAisplit(
                "帮我生成视频", "requirement-clarify", true));
    }

    @Test
    void shouldNotContinueAisplit_whenViewingOrDeleting() {
        assertFalse(AgentIntentContinuity.shouldContinueAisplit(
                "查看分镜列表", "storyboard-plan", true));
        assertFalse(AgentIntentContinuity.shouldContinueAisplit(
                "删除分镜", "", true));
    }

    @Test
    void shouldNotContinueAisplit_whenAnalyzingOrShowingExistingScenes() {
        assertFalse(AgentIntentContinuity.shouldContinueAisplit(
                "请分析以下分镜并给出优化和生成建议", "requirement-clarify", true));
        assertFalse(AgentIntentContinuity.shouldContinueAisplit(
                "展示我的分镜", "scene-mode", true));
        assertFalse(AgentIntentContinuity.shouldContinueAisplit(
                "给已有分镜生成图片和视频", "", true));
        assertFalse(AgentIntentContinuity.shouldContinueAisplit(
                "处理分镜1的图片", "", true));
    }

    @Test
    void shouldNotContinueAisplit_whenNoAisplitContext() {
        assertFalse(AgentIntentContinuity.shouldContinueAisplit(
                "讲述一个故事", "video-clarify", false));
    }

    @Test
    void pendingActionDetection() {
        assertTrue(AgentIntentContinuity.isAisplitPendingAction("requirement-clarify"));
        assertFalse(AgentIntentContinuity.isAisplitPendingAction("video-clarify"));
    }
}
