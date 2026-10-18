package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;

import java.util.ArrayList;
import java.util.List;

/**
 * 正式镜头的**后处理校验与补齐**（不调用模型）。
 *
 * <p>设计原则（对抗「LLM 反复自我校验」）：模型一次成文即可，剩下的判断全在代码里做，
 * 且优先**补齐**而不是把问题丢回给模型重试。规则分四类：
 * <ol>
 *   <li>字典收敛：景别 / 运镜落到 {@link StoryboardLexicon} 的标准词；</li>
 *   <li>取值收敛：时长落到当前视频模型支持的档位；</li>
 *   <li>空值兜底：画面 / 图片提示词 / 视频提示词 / 否定词缺一不可，缺了用上一级内容补；</li>
 *   <li>镜号与资产绑定由调用方（{@code withPseudoIdentity}）强制覆盖，这里不碰。</li>
 * </ol>
 *
 * <p>每次调整都产出一条人话 note，便于回给用户「我把『缓慢推近』规范成了『推近』」，
 * 而不是静默改掉。
 */
public final class StoryboardShotNormalizer {

    /** 归一结果：修正后的镜头 + 逐条调整说明（空列表表示模型一次就给对了）。 */
    public record Result(StoryboardShot shot, List<String> adjustments) {
        public boolean changed() {
            return !adjustments.isEmpty();
        }
    }

    private StoryboardShotNormalizer() {
    }

    /**
     * 归一一个正式镜头。
     *
     * @param pseudo           对应的伪分镜 / 大纲行（画面与动作的最终兜底来源）
     * @param raw              模型产出的正式镜头
     * @param allowedDurations 当前视频模型支持的时长档位（空 = 不校验，仅保证 &gt; 0）
     */
    public static Result normalize(PseudoShot pseudo, StoryboardShot raw,
                                   List<Integer> allowedDurations) {
        if (raw == null) return new Result(null, List.of("模型没有返回镜头内容"));
        List<String> notes = new ArrayList<>();

        String shotType = normalizeWithNotes(raw.shotType(),
                StoryboardLexicon::normalizeShotType, StoryboardLexicon.DEFAULT_SHOT_TYPE,
                "景别", notes);
        String camera = normalizeWithNotes(raw.cameraMovement(),
                StoryboardLexicon::normalizeCameraMovement, StoryboardLexicon.DEFAULT_CAMERA_MOVEMENT,
                "运镜", notes);

        int duration = normalizeDuration(raw.durationSeconds(), allowedDurations, notes);
        String scene = firstNonBlank(raw.sceneDescription(), pseudo == null ? "" : pseudo.action(),
                pseudo == null ? "" : "分镜" + pseudo.shotOrdinal());
        String imagePrompt = firstNonBlank(raw.imagePrompt(), scene);
        String videoPrompt = firstNonBlank(raw.videoPrompt(), imagePrompt);
        // event-density **只量大纲那一句**（pseudo.action），不量最终画面：
        // 最终画面按 SHOT_TABLE 要求写满机位/朝向/光位/动作起止，天然是 3–4 个句子，
        // 拿它计数会让每一镜都"建议拆镜"（实测 12/12 全中）。事件是剧情属性，不是镜头描述属性。
        if (pseudo != null) {
            int beats = countBeats(pseudo.action());
            if (beats >= 3) {
                notes.add("该镜疑似塞了 " + beats + " 个事件，建议拆镜");
            }
        }
        String negative = firstNonBlank(raw.negativePrompt(),
                StoryboardLexicon.defaultNegativePrompt());
        // 衔接说明：模型没给就退回伪分镜的 continuity（大纲阶段可能为空，那就不写）
        String continuity = firstNonBlank(raw.continuityNote(),
                pseudo == null ? "" : String.join("；", pseudo.continuity()));

        StoryboardShot normalized = new StoryboardShot(
                raw.shotId(), raw.segmentId(), raw.shotOrdinal(), scene, shotType, camera, duration,
                imagePrompt, videoPrompt, negative, raw.referenceHints(), continuity);
        return new Result(normalized, List.copyOf(notes));
    }

    /**
     * 字典收敛：能识别就换成标准词；识别不了但值得保留（≤10 字）就留原样并记一条说明；
     * 其余退回默认值。
     */
    private static String normalizeWithNotes(String raw, java.util.function.Function<String, String> lexer,
                                             String fallback, String label, List<String> notes) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            notes.add(label + "缺失，已用默认值「" + fallback + "」");
            return fallback;
        }
        String canonical = lexer.apply(trimmed);
        if (canonical != null) {
            if (!canonical.equals(trimmed)) {
                notes.add(label + "「" + trimmed + "」规范为「" + canonical + "」");
            }
            return canonical;
        }
        if (StoryboardLexicon.keepCustom(trimmed)) {
            notes.add(label + "「" + trimmed + "」不在字典内，已按自定义保留");
            return trimmed;
        }
        notes.add(label + "「" + trimmed + "」无法识别，已改为「" + fallback + "」");
        return fallback;
    }

    /** 时长收敛到模型支持的档位（取最近值，同距取更长的档位）。 */
    private static int normalizeDuration(int raw, List<Integer> allowedDurations, List<String> notes) {
        List<Integer> allowed = allowedDurations == null ? List.of() : allowedDurations.stream()
                .filter(value -> value != null && value > 0)
                .distinct()
                .sorted()
                .toList();
        if (allowed.isEmpty()) {
            int clamped = Math.max(1, raw);
            if (clamped != raw) notes.add("时长 " + raw + "s 非法，已改为 " + clamped + "s");
            return clamped;
        }
        int target = Math.max(1, raw);
        int nearest = allowed.getFirst();
        int bestGap = Integer.MAX_VALUE;
        for (Integer value : allowed) {
            int gap = Math.abs(value - target);
            if (gap < bestGap || (gap == bestGap && value > nearest)) {
                bestGap = gap;
                nearest = value;
            }
        }
        if (nearest != raw) {
            notes.add("时长 " + raw + "s 不在模型支持档位内，已改为 " + nearest + "s");
        }
        return nearest;
    }

    /**
     * 数一句画面里有几个"事件"：**只认句末标点（。！？）**。
     *
     * <p>绝不能把分号算进来：SHOT_TABLE 要求画面里用"；"分隔机位/朝向/光位等元信息，
     * 一份正常镜头几乎必然有 3 个分号——拿分号计数会让每一镜都被误报"建议拆镜"（实测 12/12 全中）。
     */
    private static int countBeats(String text) {
        if (text == null || text.isBlank()) return 0;
        int beats = 1;
        for (char ch : text.toCharArray()) {
            if (ch == '。' || ch == '！' || ch == '？') beats++;
        }
        return beats;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }
}
