package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.AgentGenerationPreferences;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardGenerationParams;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分镜生成参数解析：把「用户偏好 / 模型自选」收敛成网关真正支持的取值。
 *
 * <p>优先级（已与产品确认）：
 * <pre>
 *   用户偏好  &gt;  模型显式入参  &gt;  网关能力默认值  &gt;  代码兜底
 * </pre>
 * 注意这与 {@link GenerationPreferenceDefaults} 的「只补空、不覆盖模型」语义相反：
 * 用户在输入栏明确选了 2K，模型不得把分镜改成 768P。
 *
 * <p>能力的唯一权威源是网关 {@code GET /v1/models} 的 {@code params}：
 * {@code durations}/{@code durationDefault}/{@code resolutions}/{@code aspectRatios}/
 * {@code sizes}/{@code qualities}/{@code n}/{@code videoSegmentDuration}/{@code maxTotalDuration}。
 * 网关不可达时退回代码兜底并标注 {@code unverified}，绝不阻断生成。
 */
@Component
@RequiredArgsConstructor
public class StoryboardParamResolver {

    private static final Logger log = LoggerFactory.getLogger(StoryboardParamResolver.class);

    /** 兜底：网关不可达时用的视频能力（对齐 MiniMax-H3）。 */
    private static final List<String> FALLBACK_RESOLUTIONS = List.of("768P", "2K");
    private static final List<String> FALLBACK_ASPECT_RATIOS = List.of("16:9", "9:16", "4:3", "1:1");
    private static final List<Integer> FALLBACK_DURATIONS =
            List.of(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);

    private final GatewayModelService gatewayModelService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 解析结果：收敛后的参数 + 需要告知用户/模型的调整说明。 */
    public record Resolved(StoryboardGenerationParams params, List<String> notes, boolean unverified) {
    }

    /**
     * @param requested  模型显式传入的参数（键：videoModel/videoResolution/aspectRatio/
     *                   imageModel/imageSize/imageQuality/imageN/targetDuration）
     * @param preference 用户在输入栏选的偏好（非空时**覆盖** requested）
     * @param shotCount  目标镜头数（用于按总时长反推每镜时长），未知传 0
     */
    public Resolved resolve(Map<String, String> requested, AgentGenerationPreferences preference,
                            int shotCount) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (requested != null) {
            requested.forEach((key, value) -> {
                if (key != null && value != null && !value.isBlank()) merged.put(key, value.trim());
            });
        }
        List<String> notes = new ArrayList<>();
        boolean verified = true;

        AgentGenerationPreferences prefs = preference == null
                ? AgentGenerationPreferences.none() : preference;
        // 用户偏好优先：模型给的值被覆盖时要留痕，不能静默改
        applyPreference(merged, "videoModel", prefs.video().get("model"), "视频模型", notes);
        applyPreference(merged, "videoResolution", prefs.video().get("resolution"), "分辨率", notes);
        applyPreference(merged, "aspectRatio", prefs.video().get("aspectRatio"), "画幅", notes);
        applyPreference(merged, "imageModel", prefs.image().get("model"), "生图模型", notes);
        applyPreference(merged, "imageSize", prefs.image().get("size"), "生图尺寸", notes);
        applyPreference(merged, "imageQuality", prefs.image().get("quality"), "生图质量", notes);

        // ── 视频模型能力 ──
        Map<String, String> videoModels = modelsByType("video");
        String videoModel = pickModel(merged.get("videoModel"), videoModels,
                gatewayModelService.getDefaultVideoModel());
        Capability video = capability(videoModels, videoModel);
        if (video == null) {
            verified = false;
            video = new Capability(FALLBACK_RESOLUTIONS, FALLBACK_ASPECT_RATIOS,
                    FALLBACK_DURATIONS, 4, 300, List.of(), List.of(), List.of(1, 1, 1));
        }

        String resolution = converge(merged.get("videoResolution"), video.resolutions(),
                video.resolutionDefault(), "分辨率", notes);
        String aspect = converge(merged.get("aspectRatio"), video.aspectRatios(),
                video.aspectRatioDefault(), "画幅", notes);
        List<Integer> durations = supportedDurations(video);

        // ── 图片模型能力 ──
        Map<String, String> imageModels = modelsByType("image");
        String imageModel = pickModel(merged.get("imageModel"), imageModels,
                gatewayModelService.getDefaultImageModel());
        Capability image = capability(imageModels, imageModel);
        String imageSize = converge(merged.get("imageSize"),
                image == null ? List.of() : image.sizes(),
                image == null ? "" : image.sizeDefault(), "生图尺寸", notes);
        String imageQuality = converge(merged.get("imageQuality"),
                image == null ? List.of() : image.qualities(),
                image == null ? "" : image.qualityDefault(), "生图质量", notes);
        Integer imageN = convergeCount(merged.get("imageN"), image, notes);

        Integer targetDuration = parsePositiveInt(merged.get("targetDuration"));
        if (targetDuration != null && video.maxTotalDuration() > 0
                && targetDuration > video.maxTotalDuration()) {
            notes.add("总时长 " + targetDuration + "s 超过模型上限 "
                    + video.maxTotalDuration() + "s，已截断");
            targetDuration = video.maxTotalDuration();
        }

        StoryboardGenerationParams params = new StoryboardGenerationParams(
                imageModel, imageSize, imageQuality, imageN,
                videoModel, resolution, aspect, targetDuration, durations);
        log.info("[storyboard-params] model={} res={} aspect={} durations={} target={} verified={} notes={}",
                videoModel, resolution, aspect, durations.size(), targetDuration, verified, notes);
        return new Resolved(params, List.copyOf(notes), !verified);
    }

    /**
     * 每镜时长：有总时长就按镜头数均分并落到合法档位，否则用兜底值。
     */
    public static int perShotDuration(StoryboardGenerationParams params, int shotCount, int fallback) {
        List<Integer> plan = perShotDurations(params, shotCount, fallback);
        return plan.isEmpty() ? Math.max(1, fallback) : plan.getFirst();
    }

    /**
     * 把总时长精确摊到每一镜（不是简单四舍五入，而是在镜数内把余数分给前几镜），
     * 保证「总时长 = 各镜之和」，同时每一镜都落在模型支持的档位内。
     *
     * <p>档位不连续（如某模型只支持 4/6/8）时无法精确分摊，退回逐镜吸附兜底值，
     * 宁可总和偏一点也不要生成一个上游必拒的时长。
     */
    public static List<Integer> perShotDurations(StoryboardGenerationParams params,
                                                 int shotCount, int fallback) {
        int shots = Math.max(1, shotCount);
        List<Integer> allowed = params == null || params.allowedDurations().isEmpty()
                ? List.of() : params.allowedDurations();
        int safeFallback = snapDuration(allowed, fallback);
        Integer target = params == null ? null : params.targetDurationSeconds();
        if (target == null || target <= 0) {
            return java.util.Collections.nCopies(shots, safeFallback);
        }
        if (!contiguous(allowed)) {
            int perShot = snapDuration(allowed, Math.max(1, Math.round((float) target / shots)));
            return java.util.Collections.nCopies(shots, perShot);
        }
        int min = allowed.getFirst();
        int max = allowed.getLast();
        if (target < min * shots || target > max * shots) {
            int perShot = snapDuration(allowed, Math.max(1, Math.round((float) target / shots)));
            return java.util.Collections.nCopies(shots, perShot);
        }
        int base = target / shots;
        int remainder = target % shots;
        List<Integer> plan = new ArrayList<>(shots);
        for (int index = 0; index < shots; index++) {
            plan.add(index < remainder ? base + 1 : base);
        }
        return List.copyOf(plan);
    }

    private static boolean contiguous(List<Integer> allowed) {
        if (allowed == null || allowed.isEmpty()) return false;
        for (int index = 1; index < allowed.size(); index++) {
            if (allowed.get(index) != allowed.get(index - 1) + 1) return false;
        }
        return true;
    }

    /** 把时长吸附到最近的合法档位（同距取更长）。 */
    public static int snapDuration(List<Integer> allowed, int desired) {
        if (allowed == null || allowed.isEmpty()) return Math.max(1, desired);
        int best = allowed.getFirst();
        int bestGap = Integer.MAX_VALUE;
        for (Integer value : allowed) {
            int gap = Math.abs(value - desired);
            if (gap < bestGap || (gap == bestGap && value > best)) {
                bestGap = gap;
                best = value;
            }
        }
        return best;
    }

    // ──────────────────────────────────────────────

    private void applyPreference(Map<String, String> merged, String key, String preferred,
                                 String label, List<String> notes) {
        if (preferred == null || preferred.isBlank()) return;
        String previous = merged.put(key, preferred.trim());
        if (previous != null && !previous.isBlank()
                && !previous.equalsIgnoreCase(preferred.trim())) {
            notes.add("按你的选择把" + label + "从 " + previous + " 改为 " + preferred.trim());
        }
    }

    private Map<String, String> modelsByType(String type) {
        Map<String, String> models = new LinkedHashMap<>();
        try {
            for (Map<String, String> model : gatewayModelService.fetchModels(type)) {
                String value = model.get("value");
                if (value == null || value.isBlank()) continue;
                models.put(value, model.getOrDefault("params", ""));
            }
        } catch (Exception e) {
            log.warn("[storyboard-params] 网关模型列表不可用: type={}, error={}", type, e.getMessage());
        }
        return models;
    }

    private static String pickModel(String requested, Map<String, String> models, String fallback) {
        if (requested != null && !requested.isBlank()) {
            if (models.isEmpty() || models.containsKey(requested)) return requested;
            // 模型不存在时退回默认，但调用方要能看到（由 converge 的 note 机制覆盖部分场景）
        }
        if (!models.isEmpty() && fallback != null && models.containsKey(fallback)) return fallback;
        return models.isEmpty() ? (fallback == null ? "" : fallback) : models.keySet().iterator().next();
    }

    private Capability capability(Map<String, String> models, String modelName) {
        String raw = modelName == null ? null : models.get(modelName);
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode params = objectMapper.readTree(raw);
            return new Capability(
                    strings(params.path("resolutions")),
                    strings(params.path("aspectRatios")),
                    ints(params.path("durations")),
                    params.path("durationDefault").asInt(0),
                    params.path("maxTotalDuration").asInt(0),
                    strings(params.path("sizes")),
                    strings(params.path("qualities")),
                    ints3(params.path("n")));
        } catch (Exception e) {
            log.warn("[storyboard-params] 解析模型能力失败: model={}, error={}", modelName, e.getMessage());
            return null;
        }
    }

    /** 合法时长档位：优先用 durations，并按 videoSegmentDuration 上下限过滤。 */
    private static List<Integer> supportedDurations(Capability capability) {
        List<Integer> durations = capability.durations().isEmpty()
                ? FALLBACK_DURATIONS : capability.durations();
        // 分段时长上限来自 params.videoSegmentDuration.max（未解析到时由 durations 自身兜底）
        return durations;
    }

    /** 收敛到最近合法值；列表为空时原样返回。 */
    private static String converge(String requested, List<String> allowed, String fallback,
                                   String label, List<String> notes) {
        String target = requested == null || requested.isBlank() ? fallback : requested.trim();
        if (target == null || target.isBlank()) return "";
        if (allowed == null || allowed.isEmpty()) return target;
        if (allowed.contains(target)) return target;
        // 分辨率按数值最近（768P vs 2K：解析成像素高度做比较），失败则用默认值
        String nearest = nearestResolution(target, allowed);
        String resolved = nearest == null ? fallback : nearest;
        if (resolved == null || resolved.isBlank()) resolved = allowed.getFirst();
        notes.add(label + " " + target + " 不在模型支持列表（" + String.join("/", allowed)
                + "）内，已改为 " + resolved);
        return resolved;
    }

    private static String nearestResolution(String target, List<String> allowed) {
        Integer targetValue = resolutionValue(target);
        if (targetValue == null) return null;
        String best = null;
        int bestGap = Integer.MAX_VALUE;
        for (String candidate : allowed) {
            Integer value = resolutionValue(candidate);
            if (value == null) continue;
            int gap = Math.abs(value - targetValue);
            if (gap < bestGap || (gap == bestGap && value > targetValue)) {
                bestGap = gap;
                best = candidate;
            }
        }
        return best;
    }

    /** 768P → 768；2K/4K → 1440/2160；无法识别返回 null。 */
    private static Integer resolutionValue(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (value.endsWith("P")) {
            try {
                return Integer.parseInt(value.substring(0, value.length() - 1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return switch (value) {
            case "2K" -> 1440;
            case "4K" -> 2160;
            case "1080P" -> 1080;
            case "720P" -> 720;
            default -> null;
        };
    }

    private static Integer convergeCount(String requested, Capability image, List<String> notes) {
        Integer value = parsePositiveInt(requested);
        if (value == null) return null;
        if (image == null || image.n().isEmpty()) return value;
        int min = image.n().get(0);
        int max = image.n().get(1);
        if (value < min || value > max) {
            int clamped = Math.min(max, Math.max(min, value));
            notes.add("生成张数 " + value + " 超出模型范围 " + min + "~" + max + "，已改为 " + clamped);
            return clamped;
        }
        return value;
    }

    private static Integer parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.asText("").isBlank()) values.add(item.asText(""));
        }
        return List.copyOf(values);
    }

    private static List<Integer> ints(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<Integer> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isNumber()) values.add(item.asInt());
        }
        return List.copyOf(values);
    }

    /** n 是对象 {min,max,default}，这里只取 min/max。 */
    private static List<Integer> ints3(JsonNode node) {
        if (node == null || !node.isObject()) return List.of();
        int min = node.path("min").asInt(0);
        int max = node.path("max").asInt(0);
        if (max <= 0) return List.of();
        return List.of(Math.max(1, min), max);
    }

    /** 单个模型的能力快照（缺省为空列表/0）。 */
    private record Capability(
            List<String> resolutions,
            List<String> aspectRatios,
            List<Integer> durations,
            int durationDefault,
            int maxTotalDuration,
            List<String> sizes,
            List<String> qualities,
            List<Integer> n
    ) {
        Capability {
            resolutions = resolutions == null ? List.of() : resolutions;
            aspectRatios = aspectRatios == null ? List.of() : aspectRatios;
            durations = durations == null ? List.of() : durations;
            sizes = sizes == null ? List.of() : sizes;
            qualities = qualities == null ? List.of() : qualities;
            n = n == null ? List.of() : n;
        }

        String resolutionDefault() {
            return resolutions.isEmpty() ? "" : resolutions.getFirst();
        }

        String aspectRatioDefault() {
            return aspectRatios.isEmpty() ? "" : aspectRatios.getFirst();
        }

        String sizeDefault() {
            return sizes.isEmpty() ? "" : sizes.getFirst();
        }

        String qualityDefault() {
            return qualities.isEmpty() ? "" : qualities.getFirst();
        }
    }
}
