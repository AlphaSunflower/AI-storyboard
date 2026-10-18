package com.moon.moonagent.ai.storyboard.production;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败修法表：每类错误都要给出一句可执行建议；识别不了就返回空串（不拼空话）。
 */
class StoryboardFailureAtlasTest {

    @Test
    void balanceErrorsTellYouNotToRetry() {
        assertThat(StoryboardFailureAtlas.advise(
                "Video API returned 402: insufficient_balance_error"))
                .contains("额度不足")
                .contains("重试没有意义");
    }

    @Test
    void parameterErrorsTellYouToConvergeFirst() {
        assertThat(StoryboardFailureAtlas.advise(
                "invalid params, model MiniMax-H3 does not support resolution 1080P"))
                .contains("上游拒绝了参数")
                .contains("收敛");
    }

    @Test
    void timeoutsAndJsonErrorsSuggestCheapFixes() {
        assertThat(StoryboardFailureAtlas.advise("read timed out")).contains("批大小调小");
        assertThat(StoryboardFailureAtlas.advise("Unexpected token in JSON")).contains("批大小调小");
        assertThat(StoryboardFailureAtlas.advise("分镜生成超时")).contains("单镜重试");
    }

    @Test
    void missingShotAndReferenceFailuresHaveTheirOwnAdvice() {
        assertThat(StoryboardFailureAtlas.advise("批量生成未返回该镜头")).contains("单独补跑");
        assertThat(StoryboardFailureAtlas.advise("参考素材下载失败: /api/files/images/x.png"))
                .contains("素材");
    }

    @Test
    void unknownOrBlankErrorsProduceNoNoise() {
        assertThat(StoryboardFailureAtlas.advise("")).isEmpty();
        assertThat(StoryboardFailureAtlas.advise(null)).isEmpty();
        assertThat(StoryboardFailureAtlas.advise("something completely unknown")).isEmpty();
    }
}
