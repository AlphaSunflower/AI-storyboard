package com.moon.moonagent.ai.storyboard.production;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方法论提示词的可验收点：两条约束必须真的要求了「读戏」与「填满空格」，
 * 且廉价质感词必须并入默认否定词表。
 */
class StoryboardPromptGuidesTest {

    @Test
    void directorsReadRequiresClassificationAndInformationGain() {
        String guide = StoryboardPromptGuides.DIRECTORS_READ;

        assertThat(guide)
                .contains("读戏")
                .contains("叙事")
                .contains("观察表演")
                .contains("功能演示")
                .contains("信息增量")
                // 读戏是内部规划，不得污染最终提示词
                .contains("不要输出");
    }

    @Test
    void shotTableRequiresFloorPlanAndEveryCellFilled() {
        String guide = StoryboardPromptGuides.SHOT_TABLE;

        assertThat(guide)
                .contains("平面图")
                .contains("轴线")
                .contains("机位")
                .contains("朝向")
                .contains("光位")
                .contains("动作起止")
                .contains("时长")
                // 「默认永远不好看」是本条约束的动机，必须写明
                .contains("默认")
                // 内部标签不得进提示词
                .contains("内部标签");
    }

    @Test
    void antiSlopTermsAreMergedIntoDefaultNegativePrompts() {
        assertThat(StoryboardLexicon.DEFAULT_NEGATIVE_PROMPTS)
                .contains("模糊", "水印", "塑料感", "过饱和", "构图呆板")
                .doesNotHaveDuplicates()
                .allSatisfy(term -> assertThat(term).doesNotContain(" "));
    }

    @Test
    void defaultNegativePromptStaysCommaJoinedWithoutSpaces() {
        String joined = StoryboardLexicon.defaultNegativePrompt();

        assertThat(joined).contains("塑料感").doesNotContain(", ").doesNotContain(" ,");
    }
}
