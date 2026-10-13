package com.moon.moonagent.ai;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationPreferenceCapabilitiesTest {

    private static Map<String, String> model(String params) {
        Map<String, String> model = new LinkedHashMap<>();
        model.put("value", "doubao-seedream-4-0-250828");
        model.put("label", "Seedream 4.0");
        model.put("default", "true");
        if (params != null) model.put("params", params);
        return model;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> preferencesOf(Map<String, Object> decorated) {
        return (Map<String, Object>) decorated.get("preferences");
    }

    @Test
    void imageModelsExposeRatiosAndSizeClarity() {
        var decorated = GenerationPreferenceCapabilities.decorate(
                List.of(model("""
                        {"sizes":["1K","2K","4K"],"sizeDefault":"2K",
                         "aspectRatios":["21:9","16:9","1:1"],"aspectRatioDefault":"16:9"}""")),
                "image");

        Map<String, Object> preferences = preferencesOf(decorated.getFirst());
        assertThat(preferences).containsEntry("ratioOptions", List.of("21:9", "16:9", "1:1"));
        assertThat(preferences).containsEntry("ratioDefault", "16:9");
        assertThat(preferences.get("clarity")).isEqualTo(Map.of(
                "label", "清晰度", "param", "size",
                "options", List.of("1K", "2K", "4K"), "default", "2K"));
        // 原模型字段原样保留，前端模型下拉不受影响
        assertThat(decorated.getFirst()).containsEntry("value", "doubao-seedream-4-0-250828");
    }

    @Test
    void imageModelsWithoutRatiosOnlyOfferSmart() {
        var decorated = GenerationPreferenceCapabilities.decorate(
                List.of(model("{\"sizes\":[\"2K\"]}")), "image");

        assertThat(preferencesOf(decorated.getFirst())).containsEntry("ratioOptions", List.of());
        assertThat(preferencesOf(decorated.getFirst())).doesNotContainKey("ratioDefault");
    }

    @Test
    void qualityOnlyImageModelsFallBackToQualityLabel() {
        var decorated = GenerationPreferenceCapabilities.decorate(
                List.of(model("{\"qualities\":[\"low\",\"high\"]}")), "image");

        assertThat(preferencesOf(decorated.getFirst()).get("clarity")).isEqualTo(Map.of(
                "label", "质量", "param", "quality",
                "options", List.of("low", "high"), "default", "low"));
    }

    @Test
    void videoModelsExposeResolutionClarity() {
        var decorated = GenerationPreferenceCapabilities.decorate(
                List.of(model("{\"resolutions\":[\"720P\",\"1080P\"],\"resolutionDefault\":\"1080P\"}")),
                "video");

        assertThat(preferencesOf(decorated.getFirst()).get("clarity")).isEqualTo(Map.of(
                "label", "清晰度", "param", "resolution",
                "options", List.of("720P", "1080P"), "default", "1080P"));
    }

    @Test
    void modelsWithoutParamsKeepWorkingWithoutCapability() {
        var decorated = GenerationPreferenceCapabilities.decorate(
                List.of(model(null), model("{broken")), "image");

        assertThat(decorated).hasSize(2);
        assertThat(decorated.getFirst()).doesNotContainKey("preferences");
        assertThat(decorated.get(1)).doesNotContainKey("preferences");
    }
}
