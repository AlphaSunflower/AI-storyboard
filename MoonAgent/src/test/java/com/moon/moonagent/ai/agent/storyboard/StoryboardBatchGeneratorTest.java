package com.moon.moonagent.ai.agent.storyboard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryboardBatchGeneratorTest {

    @Test
    void generate_callsRangesInOrderAndMergesInRangeOrder() {
        List<StoryboardBatchGenerator.Range> calls = new ArrayList<>();
        Function<String, List<Map<String, Object>>> parser = content ->
                List.of(new LinkedHashMap<>(Map.of("value", content)));

        StoryboardBatchGenerator.Result result = StoryboardBatchGenerator.generate(
                new StoryboardBatchGenerator.Plan(11, 5), parser, range -> {
                    calls.add(range);
                    return new StoryboardBatchGenerator.CallOutput(
                            range.from() + "-" + range.to(), "reason-" + range.from());
                });

        assertThat(calls).containsExactly(
                new StoryboardBatchGenerator.Range(1, 5),
                new StoryboardBatchGenerator.Range(6, 10),
                new StoryboardBatchGenerator.Range(11, 11));
        assertThat(result.scenes()).extracting(s -> s.get("value"))
                .containsExactly("1-5", "6-10", "11-11");
        assertThat(result.reasoning()).contains("[1-5]".replace('[', '【').replace(']', '】'))
                .contains("reason-1").contains("reason-6").contains("reason-11");
    }

    @Test
    void parsePlan_acceptsFencedJsonAndClampsValues() {
        StoryboardBatchGenerator.Plan plan = StoryboardBatchGenerator.parsePlan(
                "```json\n{\"totalScenes\": 300, \"batchSize\": 99}\n```",
                "script", 5, 8, 200);

        assertThat(plan.totalScenes()).isEqualTo(200);
        assertThat(plan.batchSize()).isEqualTo(8);
    }

    @Test
    void parsePlan_invalidContentUsesDeterministicFallback() {
        StoryboardBatchGenerator.Plan plan = StoryboardBatchGenerator.parsePlan(
                "not-json", "第一段\n\n第二段\n\n第三段", 5, 8, 200);

        assertThat(plan.totalScenes()).isEqualTo(3);
        assertThat(plan.batchSize()).isEqualTo(6);
    }

    @Test
    void parsePlan_explicitSceneCountOverridesModelEstimate() {
        StoryboardBatchGenerator.Plan plan = StoryboardBatchGenerator.parsePlan(
                "{\"totalScenes\":5,\"batchSize\":5}",
                "《旧书店》\n共4个分镜\n分镜一：进店\n分镜四：出发",
                5, 8, 200);

        assertThat(plan.totalScenes()).isEqualTo(4);
        assertThat(plan.batchSize()).isEqualTo(5);
    }

    @Test
    void fallbackPlan_supportsArabicAndChineseExplicitCounts() {
        StoryboardBatchGenerator.Plan arabic = StoryboardBatchGenerator.fallbackPlan(
                "总共 6 个镜头", 5, 8, 200);
        StoryboardBatchGenerator.Plan chinese = StoryboardBatchGenerator.fallbackPlan(
                "一共十二个分镜", 5, 8, 200);

        assertThat(arabic.totalScenes()).isEqualTo(6);
        assertThat(chinese.totalScenes()).isEqualTo(12);
    }

    @Test
    void generate_preservesFieldsAndReferenceBindings() {
        String raw = """
                [{"sceneNumber":1,"scriptContent":"脚本","imagePrompt":"图","videoPrompt":"视频",
                  "negativePrompt":"反向","cameraMovement":"推镜","shotType":"近景","soundDesign":"环境音",
                  "referenceBindings":[{"assetId":"a1","url":"u","role":"FIRST_FRAME","purposeText":"首帧"}]}]
                """;
        StoryboardBatchGenerator.Result result = StoryboardBatchGenerator.generate(
                new StoryboardBatchGenerator.Plan(1, 5),
                StoryboardJsonParser::parseScenes,
                range -> new StoryboardBatchGenerator.CallOutput(raw, null));

        Map<String, Object> scene = result.scenes().getFirst();
        assertThat(scene).containsEntry("scriptContent", "脚本")
                .containsEntry("cameraMovement", "推镜")
                .containsEntry("shotType", "近景");
        assertThat(scene.get("referenceBindings")).isInstanceOf(List.class);
        assertThat((List<?>) scene.get("referenceBindings")).hasSize(1);
    }

    @Test
    void generate_throwsImmediatelyOnEmptyBatch() {
        List<Integer> calls = new ArrayList<>();
        assertThatThrownBy(() -> StoryboardBatchGenerator.generate(
                new StoryboardBatchGenerator.Plan(10, 5),
                StoryboardJsonParser::parseScenes,
                range -> {
                    calls.add(range.from());
                    return new StoryboardBatchGenerator.CallOutput(
                            range.from() == 1 ? validSceneJson(1) : "[]", null);
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("6-10")
                .hasMessageContaining("没有有效分镜");

        assertThat(calls).containsExactly(1, 6);
    }

    @Test
    void generateParallel_startsAllBatchesConcurrently() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        StoryboardBatchGenerator.Result result = StoryboardBatchGenerator.generateParallel(
                new StoryboardBatchGenerator.Plan(10, 5),
                content -> List.of(Map.of("value", content)),
                range -> {
                    started.countDown();
                    try {
                        if (!started.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("batch did not run concurrently");
                        }
                        release.countDown();
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("batch interrupted", e);
                    }
                    return new StoryboardBatchGenerator.CallOutput(range.from() + "-" + range.to(), null);
                },
                2);

        assertThat(result.scenes()).extracting(s -> s.get("value"))
                .containsExactly("1-5", "6-10");
    }

    @Test
    void generateParallel_mergesInRangeOrderWhenCompletionReverses() {
        StoryboardBatchGenerator.Result result = StoryboardBatchGenerator.generateParallel(
                new StoryboardBatchGenerator.Plan(10, 5),
                content -> List.of(Map.of("value", content)),
                range -> {
                    if (range.from() == 1) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return new StoryboardBatchGenerator.CallOutput(range.from() + "-" + range.to(), null);
                },
                2);

        assertThat(result.scenes()).extracting(s -> s.get("value"))
                .containsExactly("1-5", "6-10");
    }

    @Test
    void planAndFallback_guardMinimumAndMaximum() {
        StoryboardBatchGenerator.Plan capped = StoryboardBatchGenerator.parsePlan(
                "{\"totalScenes\":500,\"batchSize\":1}", "", 5, 8, 200);
        StoryboardBatchGenerator.Plan floor = StoryboardBatchGenerator.fallbackPlan(
                "", 0, -1, 0);

        assertThat(capped.totalScenes()).isEqualTo(200);
        assertThat(capped.batchSize()).isEqualTo(5);
        assertThat(floor.totalScenes()).isEqualTo(1);
        assertThat(floor.batchSize()).isEqualTo(1);
    }

    private static String validSceneJson(int number) {
        return "[{\"sceneNumber\":" + number + ",\"scriptContent\":\"场景" + number + "\"}]";
    }
}
