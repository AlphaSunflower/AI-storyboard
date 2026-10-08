package com.storyboard.ai.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptGenerationServiceImplTest {

    @Test
    void explicitModelWins() {
        assertThat(ScriptGenerationServiceImpl.resolveScriptModel("custom-model", "gateway-default"))
                .isEqualTo("custom-model");
    }

    @Test
    void blankModelUsesCurrentGatewayDefault() {
        assertThat(ScriptGenerationServiceImpl.resolveScriptModel(" ", "gateway-default"))
                .isEqualTo("gateway-default");
        assertThat(ScriptGenerationServiceImpl.resolveScriptModel(null, "gateway-default"))
                .isEqualTo("gateway-default");
    }

    @Test
    void visionCandidatesPreferRequestedThenDefaultAndRemainUnique() {
        assertThat(ScriptGenerationServiceImpl.resolveVisionModelCandidates(
                "gemini",
                "mimo",
                List.of("mimo", "qwen", "gemini")
        )).containsExactly("gemini", "mimo", "qwen");
    }
}
