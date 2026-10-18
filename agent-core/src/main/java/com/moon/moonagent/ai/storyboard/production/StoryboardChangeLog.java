package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分镜草稿的改动日志（变更审阅的持久化形态）。
 *
 * <p>为什么值得单独一张表：改动一旦只在内存里，刷新或换设备就没了；而"这句画面是谁改的、
 * 改成什么之前是什么"恰恰是审批时最需要看的证据。审批之后再回头查也靠它。
 *
 * <p>粒度是**字段级**：一行 = 一个字段的一次变化（画面 / 地点 / 时间 / 角色 / 资产）。
 * 行级 diff 看不出"改了哪一项"，审批时还得自己比对。
 */
public final class StoryboardChangeLog {

    /** 一次字段级改动。 */
    public record ShotChange(
            String runId,
            String shotId,
            int shotOrdinal,
            /** INSERT / UPDATE / DELETE */
            String kind,
            /** user / agent / system —— 谁改的 */
            String source,
            /** 中文字段名，直接给界面用 */
            String field,
            String before,
            String after,
            OffsetDateTime at
    ) {
    }

    private StoryboardChangeLog() {
    }

    /**
     * 对比两版大纲，产出字段级改动。
     *
     * <p>规则：
     * <ul>
     *   <li>新增镜头 → 一条 INSERT（before 为空）；</li>
     *   <li>删除镜头 → 一条 DELETE（after 为空）；</li>
     *   <li>同一 shotId 的字段变化 → 每个字段一条 UPDATE，值相同不产生噪音。</li>
     * </ul>
     */
    public static List<ShotChange> diff(String runId, String source,
                                        List<PseudoShot> before, List<PseudoShot> after) {
        Map<String, PseudoShot> previous = new LinkedHashMap<>();
        for (PseudoShot shot : before == null ? List.<PseudoShot>of() : before) {
            previous.put(shot.shotId(), shot);
        }
        List<ShotChange> changes = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Boolean> seen = new LinkedHashMap<>();

        for (PseudoShot shot : after == null ? List.<PseudoShot>of() : after) {
            seen.put(shot.shotId(), Boolean.TRUE);
            PseudoShot old = previous.get(shot.shotId());
            if (old == null) {
                changes.add(new ShotChange(runId, shot.shotId(), shot.shotOrdinal(),
                        "INSERT", source, "画面", "", safe(shot.action()), now));
                continue;
            }
            addIfChanged(changes, runId, source, shot, now, "画面", old.action(), shot.action());
            addIfChanged(changes, runId, source, shot, now, "地点", old.location(), shot.location());
            addIfChanged(changes, runId, source, shot, now, "时间", old.time(), shot.time());
            addIfChanged(changes, runId, source, shot, now, "角色",
                    String.join("、", nullSafe(old.characters())),
                    String.join("、", nullSafe(shot.characters())));
            addIfChanged(changes, runId, source, shot, now, "资产",
                    String.join("、", nullSafe(old.referenceAssetIds())),
                    String.join("、", nullSafe(shot.referenceAssetIds())));
        }
        for (PseudoShot old : previous.values()) {
            if (seen.containsKey(old.shotId())) continue;
            changes.add(new ShotChange(runId, old.shotId(), old.shotOrdinal(),
                    "DELETE", source, "画面", safe(old.action()), "", now));
        }
        return List.copyOf(changes);
    }

    private static void addIfChanged(List<ShotChange> changes, String runId, String source,
                                     PseudoShot shot, OffsetDateTime now,
                                     String field, String before, String after) {
        String left = safe(before);
        String right = safe(after);
        if (Objects.equals(left, right)) return;
        changes.add(new ShotChange(runId, shot.shotId(), shot.shotOrdinal(),
                "UPDATE", source, field, left, right, now));
    }

    private static List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
