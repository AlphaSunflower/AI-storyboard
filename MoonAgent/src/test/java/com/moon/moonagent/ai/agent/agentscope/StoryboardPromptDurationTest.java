package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 总时长兜底解析：用户把"总时长 30 秒"写进提示词时，模型不会翻译成 target_duration 参数
 * （连续两轮实测都是 null），所以服务端要自己抓。
 *
 * <p>正则必须带总括词，否则会把「每一镜 5 秒」误读成总时长。
 */
class StoryboardPromptDurationTest {

    @Test
    void extractsTotalDurationFromCommonPhrasings() {
        assertThat(AgentScopeStoryboardTools.totalSecondsFromPrompt("总时长 30 秒")).isEqualTo(30);
        assertThat(AgentScopeStoryboardTools.totalSecondsFromPrompt("全片总长 45s")).isEqualTo(45);
        assertThat(AgentScopeStoryboardTools.totalSecondsFromPrompt("一共 12 秒的短片")).isEqualTo(12);
        assertThat(AgentScopeStoryboardTools.totalSecondsFromPrompt("时长：60秒")).isEqualTo(60);
        assertThat(AgentScopeStoryboardTools.totalSecondsFromPrompt("总共约 20 S")).isEqualTo(20);
    }

    @Test
    void ignoresPerShotDurationsAndUnrelatedNumbers() {
        assertThat(AgentScopeStoryboardTools.totalSecondsFromPrompt("每一镜 5 秒，共 4 镜")).isNull();
        assertThat(AgentScopeStoryboardTools.totalSecondsFromPrompt("做 4 个镜头，16:9")).isNull();
        assertThat(AgentScopeStoryboardTools.totalSecondsFromPrompt("")).isNull();
        assertThat(AgentScopeStoryboardTools.totalSecondsFromPrompt(null)).isNull();
    }

    @Test
    void rejectsImplausibleValues() {
        assertThat(AgentScopeStoryboardTools.totalSecondsFromPrompt("总时长 1 秒")).isNull();
        assertThat(AgentScopeStoryboardTools.totalSecondsFromPrompt("总时长 9999 秒")).isNull();
    }
}
