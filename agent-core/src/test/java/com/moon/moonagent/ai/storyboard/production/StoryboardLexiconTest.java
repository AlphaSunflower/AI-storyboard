package com.moon.moonagent.ai.storyboard.production;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 枚举字典：模型的各种即兴写法必须落到标准词；字典外的特殊运镜允许保留但有长度上限。
 */
class StoryboardLexiconTest {

    @Test
    void normalizesShotTypeVariantsToCanonicalWords() {
        assertThat(StoryboardLexicon.normalizeShotType("中景")).isEqualTo("中景");
        assertThat(StoryboardLexicon.normalizeShotType("中近景")).isEqualTo("近景");
        assertThat(StoryboardLexicon.normalizeShotType("中景（半身）")).isEqualTo("中景");
        assertThat(StoryboardLexicon.normalizeShotType("大全景")).isEqualTo("远景");
        assertThat(StoryboardLexicon.normalizeShotType("大特写")).isEqualTo("大特写");
    }

    @Test
    void normalizesCameraMovementVariantsToCanonicalWords() {
        assertThat(StoryboardLexicon.normalizeCameraMovement("固定镜头")).isEqualTo("固定");
        assertThat(StoryboardLexicon.normalizeCameraMovement("缓慢推近")).isEqualTo("推近");
        assertThat(StoryboardLexicon.normalizeCameraMovement("缓推")).isEqualTo("推近");
        assertThat(StoryboardLexicon.normalizeCameraMovement("推")).isEqualTo("推近");
        assertThat(StoryboardLexicon.normalizeCameraMovement("后拉")).isEqualTo("拉远");
        assertThat(StoryboardLexicon.normalizeCameraMovement("跟随")).isEqualTo("跟拍");
    }

    @Test
    void returnsNullForUnknownValuesSoCallerCanKeepCustomWording() {
        assertThat(StoryboardLexicon.normalizeShotType("希区柯克变焦")).isNull();
        assertThat(StoryboardLexicon.normalizeCameraMovement("希区柯克变焦")).isNull();
        assertThat(StoryboardLexicon.normalizeShotType("")).isNull();
        assertThat(StoryboardLexicon.normalizeShotType(null)).isNull();
    }

    @Test
    void keepsCustomWordingOnlyWithinLengthLimit() {
        assertThat(StoryboardLexicon.keepCustom("希区柯克变焦")).isTrue();
        assertThat(StoryboardLexicon.keepCustom("   ")).isFalse();
        assertThat(StoryboardLexicon.keepCustom(null)).isFalse();
        assertThat(StoryboardLexicon.keepCustom("这是一个明显过长的运镜描述应该被丢弃")).isFalse();
    }

    @Test
    void promptConstraintListsEveryCanonicalValue() {
        String constraint = StoryboardLexicon.promptConstraint();

        assertThat(constraint).contains("推近").contains("环绕").contains("大特写");
        assertThat(constraint).contains("最多 " + StoryboardLexicon.MAX_CUSTOM_LENGTH + " 个字");
    }

    @Test
    void defaultNegativePromptIsCommaJoinedWordList() {
        assertThat(StoryboardLexicon.defaultNegativePrompt())
                .contains("模糊")
                .contains("水印")
                .doesNotContain("  ");
    }
}
