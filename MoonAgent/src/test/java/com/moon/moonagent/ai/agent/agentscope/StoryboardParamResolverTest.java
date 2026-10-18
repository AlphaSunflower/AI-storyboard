package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.AgentGenerationPreferences;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardGenerationParams;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 参数收敛：用户偏好 > 模型自选 > 网关能力；越界值必须收敛并留下人话说明。
 *
 * <p>能力数据形如网关 {@code GET /v1/models} 的 {@code params}（JSON 字符串）。
 */
class StoryboardParamResolverTest {

    private static final String H3_PARAMS = """
            {"durations":[4,5,6,7,8,9,10,11,12,13,14,15],"durationDefault":4,
             "resolutions":["768P","2K"],"resolutionDefault":"768P",
             "aspectRatios":["16:9","9:16","4:3","1:1"],"aspectRatioDefault":"16:9",
             "videoSegmentDuration":{"min":5,"max":60},"maxTotalDuration":300}
            """;

    private static final String VEO_PARAMS = """
            {"durations":[4,5,6,7,8],"durationDefault":6,"maxTotalDuration":300}
            """;

    private static final String IMAGE_PARAMS = """
            {"sizes":["1024x1024","1536x1024"],"sizeDefault":"1024x1024",
             "qualities":["standard","high"],"qualityDefault":"standard",
             "n":{"min":1,"max":4,"default":1}}
            """;

    @Test
    void fillsDefaultsFromGatewayCapabilityWhenNothingRequested() {
        StoryboardParamResolver resolver = resolver();

        StoryboardParamResolver.Resolved resolved =
                resolver.resolve(Map.of(), AgentGenerationPreferences.none(), 4);

        assertThat(resolved.unverified()).isFalse();
        assertThat(resolved.params().videoModel()).isEqualTo("MiniMax-H3");
        assertThat(resolved.params().videoResolution()).isEqualTo("768P");
        assertThat(resolved.params().videoAspectRatio()).isEqualTo("16:9");
        assertThat(resolved.params().allowedDurations()).containsExactlyElementsOf(
                List.of(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15));
        assertThat(resolved.notes()).isEmpty();
    }

    @Test
    void userPreferenceOverridesModelChoiceAndLeavesANote() {
        StoryboardParamResolver resolver = resolver();

        StoryboardParamResolver.Resolved resolved = resolver.resolve(
                Map.of("videoResolution", "768P", "aspectRatio", "16:9"),
                AgentGenerationPreferences.of(false, false, Map.of(),
                        Map.of("resolution", "2K", "aspectRatio", "9:16")),
                4);

        assertThat(resolved.params().videoResolution()).isEqualTo("2K");
        assertThat(resolved.params().videoAspectRatio()).isEqualTo("9:16");
        assertThat(resolved.notes())
                .anyMatch(note -> note.contains("分辨率") && note.contains("你的选择"))
                .anyMatch(note -> note.contains("画幅") && note.contains("你的选择"));
    }

    @Test
    void unsupportedResolutionAndAspectFallBackToNearestSupportedValue() {
        StoryboardParamResolver resolver = resolver();

        StoryboardParamResolver.Resolved resolved = resolver.resolve(
                Map.of("videoResolution", "1080P", "aspectRatio", "21:9"),
                AgentGenerationPreferences.none(), 4);

        // |1080-768|=312 比 |1080-1440|=360 更近
        assertThat(resolved.params().videoResolution()).isEqualTo("768P");
        // 画幅无法按数值比，退回模型默认
        assertThat(resolved.params().videoAspectRatio()).isEqualTo("16:9");
        assertThat(resolved.notes())
                .anyMatch(note -> note.contains("1080P"))
                .anyMatch(note -> note.contains("21:9"));
    }

    @Test
    void durationsFollowTheChosenModel() {
        StoryboardParamResolver resolver = resolver();

        StoryboardParamResolver.Resolved veo =
                resolver.resolve(Map.of("videoModel", "veo-3.1-fast"), AgentGenerationPreferences.none(), 4);

        assertThat(veo.params().videoModel()).isEqualTo("veo-3.1-fast");
        assertThat(veo.params().allowedDurations()).containsExactly(4, 5, 6, 7, 8);
    }

    @Test
    void targetDurationBeyondModelLimitIsTruncated() {
        StoryboardParamResolver resolver = resolver();

        StoryboardParamResolver.Resolved resolved = resolver.resolve(
                Map.of("targetDuration", "900"), AgentGenerationPreferences.none(), 10);

        assertThat(resolved.params().targetDurationSeconds()).isEqualTo(300);
        assertThat(resolved.notes()).anyMatch(note -> note.contains("总时长") && note.contains("300"));
    }

    @Test
    void perShotDurationDistributesTargetAcrossShots() {
        StoryboardGenerationParams params = new StoryboardGenerationParams(
                null, null, null, null, "MiniMax-H3", "768P", "16:9", 30,
                List.of(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15));

        // 30s 摊到 4 镜：余数给前几镜，总和必须正好 30
        assertThat(StoryboardParamResolver.perShotDurations(params, 4, 3))
                .containsExactly(8, 8, 7, 7);
        // 30s / 12 镜 = 2.5s 低于最低档 4s → 无法精确分摊，退回逐镜吸附
        assertThat(StoryboardParamResolver.perShotDurations(params, 12, 3))
                .containsOnly(4)
                .hasSize(12);
        // 合法区间内也精确：30s / 3 镜 = 10/10/10
        assertThat(StoryboardParamResolver.perShotDurations(params, 3, 3))
                .containsExactly(10, 10, 10);
        // 没有总时长时用兜底值并吸附（5s 与 4/6 等距，规则取更长档位）
        StoryboardGenerationParams noTarget = StoryboardGenerationParams.durationsOnly(List.of(4, 6, 8));
        assertThat(StoryboardParamResolver.perShotDurations(noTarget, 3, 5)).containsOnly(6);
    }

    @Test
    void perShotDurationsFallsBackWhenTiersAreNotContiguous() {
        StoryboardGenerationParams params = StoryboardGenerationParams.durationsOnly(List.of(4, 6, 8));
        StoryboardGenerationParams withTarget = new StoryboardGenerationParams(
                null, null, null, null, null, null, null, 30, List.of(4, 6, 8));

        // 不连续档位无法保证总和，宁可每镜取同一个就近值
        assertThat(StoryboardParamResolver.perShotDurations(withTarget, 4, 4)).containsOnly(8);
        assertThat(StoryboardParamResolver.perShotDurations(params, 4, 4)).containsOnly(4);
    }

    @Test
    void gatewayOutageFallsBackAndFlagsUnverified() {
        StoryboardParamResolver resolver = new StoryboardParamResolver(
                new FakeGateway(Map.of(), Map.of(), true));

        StoryboardParamResolver.Resolved resolved =
                resolver.resolve(Map.of("videoResolution", "2K"), AgentGenerationPreferences.none(), 4);

        assertThat(resolved.unverified()).isTrue();
        assertThat(resolved.params().videoResolution()).isEqualTo("2K");
        assertThat(resolved.params().allowedDurations()).isNotEmpty();
    }

    @Test
    void imageParamsAreConvergedAndCountClamped() {
        StoryboardParamResolver resolver = resolver();

        StoryboardParamResolver.Resolved resolved = resolver.resolve(
                Map.of("imageSize", "4096x4096", "imageQuality", "ultra", "imageN", "9"),
                AgentGenerationPreferences.none(), 4);

        assertThat(resolved.params().imageSize()).isEqualTo("1024x1024");
        assertThat(resolved.params().imageQuality()).isEqualTo("standard");
        assertThat(resolved.params().imageN()).isEqualTo(4);
        assertThat(resolved.notes())
                .anyMatch(note -> note.contains("生图尺寸"))
                .anyMatch(note -> note.contains("生图质量"))
                .anyMatch(note -> note.contains("生成张数"));
    }

    private static StoryboardParamResolver resolver() {
        return new StoryboardParamResolver(new FakeGateway(
                Map.of("MiniMax-H3", H3_PARAMS, "veo-3.1-fast", VEO_PARAMS),
                Map.of("gpt-image-2", IMAGE_PARAMS),
                false));
    }

    /** 只实现参数解析需要的方法，其余给最小实现。 */
    private static final class FakeGateway implements GatewayModelService {
        private final Map<String, String> videoParams;
        private final Map<String, String> imageParams;
        private final boolean fail;

        FakeGateway(Map<String, String> videoParams, Map<String, String> imageParams, boolean fail) {
            this.videoParams = new LinkedHashMap<>(videoParams);
            this.imageParams = new LinkedHashMap<>(imageParams);
            this.fail = fail;
        }

        @Override
        public List<Map<String, String>> fetchModels(String type) {
            if (fail) throw new IllegalStateException("gateway down");
            Map<String, String> source = "video".equals(type) ? videoParams
                    : "image".equals(type) ? imageParams : Map.of();
            return source.entrySet().stream().map(entry -> {
                Map<String, String> model = new LinkedHashMap<>();
                model.put("value", entry.getKey());
                model.put("label", entry.getKey());
                model.put("params", entry.getValue());
                return model;
            }).toList();
        }

        @Override
        public String getDefaultImageModel() {
            return "gpt-image-2";
        }

        @Override
        public String getDefaultVideoModel() {
            return "MiniMax-H3";
        }

        @Override
        public String getDefaultVisionModel() {
            return "gemini";
        }

        @Override
        public String getDefaultTextModel() {
            return "mimo";
        }
    }
}
