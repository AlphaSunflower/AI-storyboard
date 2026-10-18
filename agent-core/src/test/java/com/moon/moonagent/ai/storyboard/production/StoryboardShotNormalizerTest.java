package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后处理校验：所有修正都必须由代码完成并留下人话说明，不把问题丢回模型重试。
 */
class StoryboardShotNormalizerTest {

    private static final List<Integer> H3_DURATIONS =
            List.of(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);

    @Test
    void keepsCanonicalShotUntouchedAndReportsNoAdjustments() {
        StoryboardShot raw = shot("中景", "推近", 6, "画面", "图prompt", "视prompt", "模糊");

        StoryboardShotNormalizer.Result result =
                StoryboardShotNormalizer.normalize(pseudo(), raw, H3_DURATIONS);

        assertThat(result.changed()).isFalse();
        assertThat(result.shot()).isEqualTo(raw);
    }

    @Test
    void collapsesInventedWordingIntoDictionaryWords() {
        StoryboardShot raw = shot("中近景（半身）", "镜头缓慢向前推进", 6, "画面", "图", "视", "模糊");

        StoryboardShotNormalizer.Result result =
                StoryboardShotNormalizer.normalize(pseudo(), raw, H3_DURATIONS);

        assertThat(result.shot().shotType()).isEqualTo("近景");
        assertThat(result.shot().cameraMovement()).isEqualTo("推近");
        assertThat(result.adjustments())
                .anyMatch(note -> note.contains("景别"))
                .anyMatch(note -> note.contains("运镜"));
    }

    @Test
    void keepsSpecialCameraWordingWithinLengthLimit() {
        StoryboardShot raw = shot("全景", "希区柯克变焦", 6, "画面", "图", "视", "模糊");

        StoryboardShotNormalizer.Result result =
                StoryboardShotNormalizer.normalize(pseudo(), raw, H3_DURATIONS);

        assertThat(result.shot().cameraMovement()).isEqualTo("希区柯克变焦");
        assertThat(result.adjustments()).anyMatch(note -> note.contains("自定义保留"));
    }

    @Test
    void fallsBackToDefaultsWhenFieldsAreBlank() {
        StoryboardShot raw = shot("", "", 6, "", "", "", "");

        StoryboardShotNormalizer.Result result =
                StoryboardShotNormalizer.normalize(pseudo(), raw, H3_DURATIONS);

        assertThat(result.shot().shotType()).isEqualTo(StoryboardLexicon.DEFAULT_SHOT_TYPE);
        assertThat(result.shot().cameraMovement()).isEqualTo(StoryboardLexicon.DEFAULT_CAMERA_MOVEMENT);
        assertThat(result.shot().negativePrompt()).isNotBlank();
        // 画面兜底到伪分镜的动作描述
        assertThat(result.shot().sceneDescription()).isEqualTo("动作描述");
        assertThat(result.shot().imagePrompt()).isEqualTo("动作描述");
        assertThat(result.shot().videoPrompt()).isEqualTo("动作描述");
    }

    @Test
    void snapsDurationToNearestSupportedTier() {
        // MiniMax-H3 不支持 3s，最近档位是 4s
        assertThat(normalize(3).shot().durationSeconds()).isEqualTo(4);
        assertThat(normalize(6).shot().durationSeconds()).isEqualTo(6);
        // 超出上限收敛到 15
        assertThat(normalize(20).shot().durationSeconds()).isEqualTo(15);
        assertThat(normalize(3).adjustments()).anyMatch(note -> note.contains("时长"));
    }

    @Test
    void durationIsOnlySanitizedWhenNoTiersConfigured() {
        StoryboardShotNormalizer.Result result = StoryboardShotNormalizer.normalize(
                pseudo(), shot("中景", "推近", 0, "画面", "图", "视", "模糊"), null);

        assertThat(result.shot().durationSeconds()).isEqualTo(1);
        assertThat(result.adjustments()).anyMatch(note -> note.contains("时长"));
    }

    @Test
    void nullShotIsReportedInsteadOfThrowing() {
        StoryboardShotNormalizer.Result result =
                StoryboardShotNormalizer.normalize(pseudo(), null, H3_DURATIONS);

        assertThat(result.shot()).isNull();
        assertThat(result.adjustments()).hasSize(1);
    }

    /**
     * 衔接说明：模型给了就用模型的；没给就退回伪分镜的 continuity，不能整列空着。
     */
    @Test
    void keepsModelContinuityNoteAndFallsBackToPseudoContinuity() {
        StoryboardShot withNote = new StoryboardShot("shot-1", "segment-1", 1, "画面", "中景", "推近", 6,
                "图", "视", "模糊", List.of(), "承接雨夜冷蓝侧逆光");
        assertThat(StoryboardShotNormalizer.normalize(pseudo(), withNote, H3_DURATIONS)
                .shot().continuityNote())
                .isEqualTo("承接雨夜冷蓝侧逆光");

        PseudoShot withContinuity = new PseudoShot("shot-1", "segment-1", 1, "目的", List.of(),
                "场景", "时间", "冲突", "动作描述", "情绪",
                List.of("湿发不得回退", "光位保持侧逆"), List.of(), List.of());
        StoryboardShot bare = shot("中景", "推近", 6, "画面", "图", "视", "模糊");

        assertThat(StoryboardShotNormalizer.normalize(withContinuity, bare, H3_DURATIONS)
                .shot().continuityNote())
                .isEqualTo("湿发不得回退；光位保持侧逆");
    }

    /**
     * event-density 只在**大纲行**塞多事件时提示，且只提示不改内容。
     * （旧口径量最终画面，会因机位/光位句而 12/12 全量误报，已废弃。）
     */
    @Test
    void warnsWhenOutlineLineCramsMultipleBeats() {
        PseudoShot crowdedOutline = new PseudoShot("shot-1", "segment-1", 1, "", List.of(), "旧仓库",
                "夜", "", "男子推门进入。他停住听声。回头看向货架。", "", List.of(), List.of(), List.of());
        StoryboardShot shot = shot("中景", "推近", 6, "画面描述", "图", "视", "模糊");

        StoryboardShotNormalizer.Result result =
                StoryboardShotNormalizer.normalize(crowdedOutline, shot, H3_DURATIONS);

        assertThat(result.adjustments()).anyMatch(note -> note.contains("建议拆镜"));
        // 只提示，不改写用户/模型写的画面
        assertThat(result.shot().sceneDescription()).isEqualTo("画面描述");
    }

    @Test
    void singleBeatShotHasNoDensityWarning() {
        StoryboardShotNormalizer.Result result = StoryboardShotNormalizer.normalize(
                pseudo(), shot("中景", "推近", 6, "男子停在窗前，雨光映在脸上", "图", "视", "模糊"),
                H3_DURATIONS);

        assertThat(result.adjustments()).noneMatch(note -> note.contains("建议拆镜"));
    }

    /**
     * event-density 必须量**大纲那一句**（pseudo.action），不能量最终画面：
     * 最终画面按 SHOT_TABLE 写满机位/朝向/光位，天然 3–4 句，量它会让每一镜都误报（实测 12/12）。
     */
    @Test
    void densityIsMeasuredOnOutlineLineNotOnCameraMetadata() {
        PseudoShot crowdedOutline = new PseudoShot("shot-1", "segment-1", 1, "", List.of(), "旧仓库",
                "夜", "", "男子推门进入。他停住听声。回头看向货架。", "", List.of(), List.of(), List.of());
        StoryboardShot metadataHeavy = new StoryboardShot("shot-1", "segment-1", 1,
                "机位在门外南侧偏左，越过男子右肩朝北正打门缝。男子从右侧门扇边缘侧身挤入。"
                        + "门内仅极弱冷蓝环境光。门外左上冷蓝雨光切进一道窄边。",
                "中景", "推近", 6, "图", "视", "模糊", List.of());

        StoryboardShotNormalizer.Result crowded =
                StoryboardShotNormalizer.normalize(crowdedOutline, metadataHeavy, H3_DURATIONS);
        assertThat(crowded.adjustments()).anyMatch(note -> note.contains("建议拆镜"));

        // 同一段"元信息很多"的画面，配一句干净的大纲 → 不该报
        PseudoShot cleanOutline = new PseudoShot("shot-1", "segment-1", 1, "", List.of(), "旧仓库",
                "夜", "", "男子挤进门缝停住", "", List.of(), List.of(), List.of());
        StoryboardShotNormalizer.Result clean =
                StoryboardShotNormalizer.normalize(cleanOutline, metadataHeavy, H3_DURATIONS);
        assertThat(clean.adjustments()).noneMatch(note -> note.contains("建议拆镜"));
    }

    /**
     * 机位/朝向/光位用分号分隔是**正常写法**，不能被当成"塞了多个事件"。
     */
    @Test
    void semicolonSeparatedCameraMetadataIsNotCountedAsMultipleBeats() {
        StoryboardShot metadataStyle = new StoryboardShot("shot-1", "segment-1", 1,
                "机位在廊道中段南侧、贴近地面，沿推进轴线正打男子背侧；男子起始贴西侧货架，向北两步后停住；"
                        + "西侧上方暖黄钨丝灯压出光斑，北端冷白余光已退。",
                "中景", "推近", 6, "图", "视", "模糊", List.of());

        StoryboardShotNormalizer.Result result =
                StoryboardShotNormalizer.normalize(pseudo(), metadataStyle, H3_DURATIONS);

        assertThat(result.adjustments()).noneMatch(note -> note.contains("建议拆镜"));
    }

    private static StoryboardShotNormalizer.Result normalize(int duration) {
        return StoryboardShotNormalizer.normalize(
                pseudo(), shot("中景", "推近", duration, "画面", "图", "视", "模糊"), H3_DURATIONS);
    }

    private static StoryboardShot shot(String shotType, String camera, int duration,
                                       String scene, String image, String video, String negative) {
        return new StoryboardShot("shot-1", "segment-1", 1, scene, shotType, camera, duration,
                image, video, negative, List.of());
    }

    private static PseudoShot pseudo() {
        return new PseudoShot("shot-1", "segment-1", 1, "目的", List.of(), "场景", "时间", "冲突",
                "动作描述", "情绪", List.of(), List.of(), List.of());
    }
}
