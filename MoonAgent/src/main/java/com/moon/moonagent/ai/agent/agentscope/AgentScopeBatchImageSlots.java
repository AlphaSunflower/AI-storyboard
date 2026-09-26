package com.moon.moonagent.ai.agent.agentscope;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AgentScopeBatchImageSlots {

    private AgentScopeBatchImageSlots() {
    }

    record Slot(
            int index,
            String label,
            String prompt,
            String status,
            String url,
            String assetId,
            String error,
            OffsetDateTime completedAt) {

        static Slot pending(int index, String prompt) {
            return pending(index, "", prompt);
        }

        static Slot pending(int index, String label, String prompt) {
            return new Slot(index, safe(label), safe(prompt), "pending", "", "", "", null);
        }

        Slot running() {
            return new Slot(index, label, prompt, "running", "", "", "", null);
        }

        Slot complete(String url, String assetId, OffsetDateTime completedAt) {
            return new Slot(index, label, prompt, "completed", safe(url), safe(assetId), "",
                    completedAt == null ? OffsetDateTime.now() : completedAt);
        }

        Slot fail(String error, OffsetDateTime completedAt) {
            return new Slot(index, label, prompt, "failed", "", "", safe(error),
                    completedAt == null ? OffsetDateTime.now() : completedAt);
        }

        Slot cancel() {
            return new Slot(index, label, prompt, "cancelled", "", "", "", OffsetDateTime.now());
        }

        boolean completed() {
            return "completed".equals(status) && !url.isBlank();
        }

        boolean terminal() {
            return completed() || "failed".equals(status) || "cancelled".equals(status);
        }
    }

    record Snapshot(
            String status,
            int progress,
            String resultSummary,
            String error,
            List<String> resultUrls,
            List<String> assetIds,
            String content) {
    }

    static List<Slot> initial(List<String> prompts, List<Integer> requestedIndices) {
        return initial(prompts, requestedIndices, List.of());
    }

    static List<Slot> initial(List<String> prompts, List<Integer> requestedIndices,
                              List<String> labels) {
        List<String> safePrompts = prompts == null ? List.of() : prompts;
        List<String> safeLabels = labels == null ? List.of() : labels;
        List<Integer> indices = requestedIndices == null || requestedIndices.isEmpty()
                ? java.util.stream.IntStream.range(0, safePrompts.size()).boxed().toList()
                : requestedIndices;
        List<Slot> out = new ArrayList<>();
        for (int position = 0; position < safePrompts.size(); position++) {
            int index = position < indices.size() ? indices.get(position) : position;
            String label = position < safeLabels.size() ? safeLabels.get(position) : "";
            out.add(Slot.pending(index, label, safePrompts.get(position)));
        }
        return sorted(out);
    }

    static List<Map<String, Object>> toMaps(List<Slot> values) {
        return sorted(values).stream().map(slot -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("index", slot.index());
            out.put("label", slot.label());
            out.put("prompt", slot.prompt());
            out.put("status", slot.status());
            out.put("url", slot.url());
            out.put("assetId", slot.assetId());
            out.put("error", slot.error());
            out.put("completedAt", slot.completedAt() == null ? "" : slot.completedAt().toString());
            return out;
        }).toList();
    }

    static List<Slot> fromMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Slot> slots = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            slots.add(new Slot(
                    integer(map.get("index"), slots.size()),
                    safe(string(map.get("label"))),
                    safe(string(map.get("prompt"))),
                    safe(string(map.get("status"))),
                    safe(string(map.get("url"))),
                    safe(string(map.get("assetId"))),
                    safe(string(map.get("error"))),
                    offsetDateTime(map.get("completedAt"))));
        }
        return sorted(slots);
    }

    static Snapshot snapshot(List<Slot> values, boolean terminal) {
        List<Slot> slots = sorted(values);
        int completed = (int) slots.stream().filter(Slot::completed).count();
        int failed = (int) slots.stream().filter(slot -> "failed".equals(slot.status())).count();
        int cancelled = (int) slots.stream().filter(slot -> "cancelled".equals(slot.status())).count();
        int processed = (int) slots.stream().filter(Slot::terminal).count();
        int total = slots.size();
        String status;
        if (!terminal) {
            status = "running";
        } else if (completed == total && total > 0) {
            status = "completed";
        } else if (completed > 0) {
            status = "partial";
        } else {
            status = "failed";
        }
        String summary = total == 0
                ? "未提交图片槽位"
                : switch (status) {
                    case "completed" -> "已生成 " + completed + " 张图片";
                    case "partial" -> "已生成 " + completed + "/" + total
                            + " 张图片，" + (total - completed) + " 张失败";
                    case "failed" -> "批量生图失败，0/" + total + " 张成功";
                    default -> "已生成 " + completed + "/" + total + " 张图片";
                };
        String error = slots.stream()
                .map(Slot::error)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
        List<String> urls = slots.stream()
                .filter(Slot::completed)
                .map(Slot::url)
                .toList();
        List<String> assetIds = slots.stream()
                .filter(Slot::completed)
                .map(Slot::assetId)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        String content = slots.stream()
                .filter(Slot::completed)
                .sorted(Comparator
                        .comparing(Slot::completedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(Slot::index))
                .map(slot -> "![生成图片](" + slot.url() + ")")
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        int progress = total == 0 ? 100 : Math.min(100, processed * 100 / total);
        return new Snapshot(status, progress, summary, error, urls, assetIds, content);
    }

    static List<Integer> failedOrMissingIndices(List<Slot> values) {
        return sorted(values).stream()
                .filter(slot -> !slot.completed())
                .map(Slot::index)
                .toList();
    }

    static List<String> promptsAt(List<Slot> values, List<Integer> indices) {
        Map<Integer, Slot> byIndex = byIndex(values);
        return indices.stream()
                .map(byIndex::get)
                .filter(java.util.Objects::nonNull)
                .map(Slot::prompt)
                .toList();
    }

    static List<String> labelsAt(List<Slot> values, List<Integer> indices) {
        Map<Integer, Slot> byIndex = byIndex(values);
        return indices.stream()
                .map(byIndex::get)
                .filter(java.util.Objects::nonNull)
                .map(Slot::label)
                .toList();
    }

    static List<Slot> merge(List<Slot> parent, List<Slot> retry) {
        Map<Integer, Slot> merged = new LinkedHashMap<>(byIndex(parent));
        byIndex(retry).forEach((index, retrySlot) -> {
            if (retrySlot.completed()) {
                merged.put(index, retrySlot);
            } else if (merged.get(index) == null || !merged.get(index).completed()) {
                merged.put(index, retrySlot);
            }
        });
        return sorted(new ArrayList<>(merged.values()));
    }

    private static Map<Integer, Slot> byIndex(List<Slot> values) {
        Map<Integer, Slot> out = new LinkedHashMap<>();
        if (values != null) {
            values.forEach(slot -> out.put(slot.index(), slot));
        }
        return out;
    }

    private static List<Slot> sorted(List<Slot> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream()
                .sorted(Comparator.comparingInt(Slot::index))
                .toList();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static OffsetDateTime offsetDateTime(Object value) {
        String text = string(value);
        if (text.isBlank()) return null;
        try {
            return OffsetDateTime.parse(text);
        } catch (Exception ignored) {
            return null;
        }
    }
}
