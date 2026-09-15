package com.moon.moonagent.ai.storyboard;

import com.storyboard.common.BusinessException;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Converts the model's structured JSON response into the stable storyboard contract.
 */
@Component
public final class StoryboardPlanResponseMapper {

    private final BeanOutputConverter<StoryboardPlanDraft> converter =
            new BeanOutputConverter<>(StoryboardPlanDraft.class);

    public String responseFormat() {
        return converter.getFormat();
    }

    public List<StoryboardShot> toShots(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("storyboard planner returned empty content", null);
        }

        StoryboardPlanDraft draft;
        try {
            draft = converter.convert(raw);
        } catch (RuntimeException e) {
            throw invalid("storyboard planner returned invalid JSON", e);
        }
        if (draft == null || draft.shots() == null || draft.shots().isEmpty()) {
            throw invalid("storyboard planner returned no shots", null);
        }

        List<IndexedShot> ordered = new ArrayList<>(draft.shots().size());
        for (int i = 0; i < draft.shots().size(); i++) {
            StoryboardShotDraft shot = draft.shots().get(i);
            if (shot == null) throw invalid("storyboard planner returned a null shot", null);
            ordered.add(new IndexedShot(i, shot));
        }
        ordered.sort(Comparator
                .comparingInt((IndexedShot item) -> sortOrdinal(item.shot().shotOrdinal(), item.index()))
                .thenComparingInt(IndexedShot::index));

        Set<String> usedShotIds = new HashSet<>();
        List<StoryboardShot> shots = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            StoryboardShotDraft shot = ordered.get(i).shot();
            int ordinal = i + 1;
            String segmentId = textOrDefault(shot.segmentId(), "segment-" + ordinal);
            String shotId = uniqueShotId(textOrDefault(shot.shotId(), "shot-" + ordinal), ordinal, usedShotIds);

            shots.add(new StoryboardShot(
                    shotId,
                    segmentId,
                    ordinal,
                    requiredText(shot.sceneDescription(), "sceneDescription", ordinal),
                    requiredText(shot.shotType(), "shotType", ordinal),
                    requiredText(shot.cameraMovement(), "cameraMovement", ordinal),
                    requiredDuration(shot.durationSeconds(), ordinal),
                    requiredText(shot.imagePrompt(), "imagePrompt", ordinal),
                    requiredText(shot.videoPrompt(), "videoPrompt", ordinal),
                    shot.negativePrompt() == null ? "" : shot.negativePrompt().trim(),
                    normalizedHints(shot.referenceHints())
            ));
        }
        return List.copyOf(shots);
    }

    private static int sortOrdinal(Integer value, int index) {
        return value != null && value > 0 ? value : index + 1;
    }

    private static String uniqueShotId(String requested, int ordinal, Set<String> used) {
        String base = requested;
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate == null || candidate.isBlank() ? "shot-" + ordinal : candidate;
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requiredText(String value, String field, int ordinal) {
        if (value == null || value.isBlank()) {
            throw invalid("storyboard planner omitted " + field + " for shot " + ordinal, null);
        }
        return value.trim();
    }

    private static int requiredDuration(Integer value, int ordinal) {
        if (value == null || value <= 0) {
            throw invalid("storyboard planner omitted durationSeconds for shot " + ordinal, null);
        }
        return value;
    }

    private static List<String> normalizedHints(List<String> hints) {
        if (hints == null || hints.isEmpty()) return List.of();
        return hints.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static BusinessException invalid(String message, Throwable cause) {
        return cause == null
                ? new BusinessException(50201, message)
                : new BusinessException(50201, message, cause);
    }

    private record IndexedShot(int index, StoryboardShotDraft shot) {}

    public record StoryboardPlanDraft(List<StoryboardShotDraft> shots) {}

    public record StoryboardShotDraft(
            String shotId,
            String segmentId,
            Integer shotOrdinal,
            String sceneDescription,
            String shotType,
            String cameraMovement,
            Integer durationSeconds,
            String imagePrompt,
            String videoPrompt,
            String negativePrompt,
            List<String> referenceHints
    ) {}
}
