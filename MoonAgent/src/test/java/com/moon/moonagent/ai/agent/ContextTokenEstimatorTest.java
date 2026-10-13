package com.moon.moonagent.ai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextTokenEstimatorTest {

    @Test
    void countsCjkCharactersAsOneTokenEach() {
        assertThat(ContextTokenEstimator.estimateTokens("系统提示词")).isEqualTo(5L);
    }

    @Test
    void countsNonCjkAtRoughlyFourCharsPerToken() {
        assertThat(ContextTokenEstimator.estimateTokens("abcdefgh")).isEqualTo(2L);
    }

    @Test
    void mixesCjkAndLatin() {
        // 4 个汉字(4) + "abcd"(1) = 5
        assertThat(ContextTokenEstimator.estimateTokens("工具定义abcd")).isEqualTo(5L);
    }

    @Test
    void returnsZeroForEmptyAndNull() {
        assertThat(ContextTokenEstimator.estimateTokens(null)).isZero();
        assertThat(ContextTokenEstimator.estimateTokens("")).isZero();
    }
}
