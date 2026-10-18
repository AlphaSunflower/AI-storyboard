package com.moon.moonagent.ai.agent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 参数表的确定性校验：不再让 reviewer 做算术。
 *
 * <p>只校验能量化的部分：fps/时长/画幅/色温是否一致、镜1 推进距离是否闭合、
 * 焦段/距离/门洞高度/占高是否能在全画幅 24mm 传感器上同时成立、光圈是否在合理范围。
 * 艺术判断仍交给 reviewer。
 */
public final class SubagentParameterValidator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double SENSOR_HEIGHT_MM = 24.0;
    private static final double DISTANCE_TOLERANCE_M = 0.2;
    private static final double RATIO_TOLERANCE = 0.10;

    private SubagentParameterValidator() {
    }

    public static List<String> validate(SubagentTaskGraph graph, SubagentGraphResult result) {
        List<String> violations = new ArrayList<>();
        JsonNode baseline = null;
        String baselineId = null;

        for (SubagentTask task : graph.nodes()) {
            if (!"parameter-table".equals(task.outputSchema())) continue;
            SubagentTaskResult taskResult = result.result(task.id());
            if (taskResult == null || !taskResult.succeeded()) continue;
            JsonNode params = parameters(taskResult.output());
            if (params == null || !params.isObject() || params.isEmpty()) {
                // 参数表已降级为可选：canonical context 才是生成参数真源。
                continue;
            }
            for (String key : List.of("fps", "durationSeconds", "aspectRatio", "colorTemperatureK")) {
                if (!params.hasNonNull(key)) {
                    violations.add(task.id() + " 缺少 " + key);
                }
            }
            if (baseline == null) {
                baseline = params;
                baselineId = task.id();
            } else {
                compare(violations, baselineId, baseline, task.id(), params, "fps");
                compare(violations, baselineId, baseline, task.id(), params, "durationSeconds");
                compare(violations, baselineId, baseline, task.id(), params, "aspectRatio");
                compare(violations, baselineId, baseline, task.id(), params, "colorTemperatureK");
            }
            validateShots(violations, task.id(), params.path("shots"));
            validateTimeline(violations, task.id(), params);
        }
        return violations;
    }

    private static void compare(List<String> violations, String aId, JsonNode a,
                                String bId, JsonNode b, String key) {
        JsonNode left = a.get(key);
        JsonNode right = b.get(key);
        if (left == null || right == null) return;
        if (left.isNumber() && right.isNumber()) {
            double diff = Math.abs(left.asDouble() - right.asDouble());
            double allowed = "durationSeconds".equals(key) ? 0.01 : 0.001;
            if (diff > allowed) {
                violations.add(aId + " 与 " + bId + " 的 " + key + " 不一致: "
                        + left + " vs " + right);
            }
        } else if (!left.asText().equals(right.asText())) {
            violations.add(aId + " 与 " + bId + " 的 " + key + " 不一致: "
                    + left + " vs " + right);
        }
    }

    private static void validateTimeline(List<String> violations, String taskId, JsonNode params) {
        double fps = params.path("fps").asDouble(0);
        double duration = params.path("durationSeconds").asDouble(0);
        double totalFrames = params.path("totalFrames").asDouble(0);
        if (fps > 0 && duration > 0 && totalFrames > 0
                && Math.abs(totalFrames - fps * duration) > 1) {
            violations.add(taskId + " 总帧数与时长/fps 不一致: " + totalFrames
                    + " vs " + (fps * duration));
        }
        double renderFrames = params.path("renderFrames").asDouble(0);
        double extraFrames = params.path("extraFrames").asDouble(0);
        if (totalFrames > 0 && renderFrames > 0
                && Math.abs(renderFrames - (totalFrames + extraFrames)) > 1) {
            violations.add(taskId + " renderFrames 与 totalFrames+extraFrames 不一致: "
                    + renderFrames + " vs " + (totalFrames + extraFrames));
        }

        JsonNode shots = params.path("shots");
        if (!shots.isArray()) return;
        double sumDuration = 0;
        double previousDistance = Double.NaN;
        java.util.Set<Double> focals = new java.util.LinkedHashSet<>();
        for (JsonNode shot : shots) {
            double shotDuration = shot.path("durationSeconds").asDouble(0);
            sumDuration += shotDuration;
            double rise = shot.path("riseM").asDouble(0);
            double riseSpeed = shot.path("riseSpeedMps").asDouble(0);
            if (rise > 0 && riseSpeed > 0 && shotDuration > 0
                    && Math.abs(rise - riseSpeed * shotDuration) > 0.1) {
                violations.add(taskId + " 镜" + shot.path("id").asText("?")
                        + " 升程与速度×时长不一致: " + rise + " vs "
                        + (riseSpeed * shotDuration));
            }
            double focal = shot.path("focalLengthMm").asDouble(0);
            if (focal > 0) {
                focals.add(focal);
            }
            double distance = shot.path("distanceM").asDouble(Double.NaN);
            if (!Double.isNaN(distance)) {
                if (!Double.isNaN(previousDistance) && distance < previousDistance - 0.01) {
                    violations.add(taskId + " distanceM 未单调递增: "
                            + previousDistance + " -> " + distance);
                }
                previousDistance = distance;
            }
        }
        if (duration > 0 && sumDuration > 0 && Math.abs(sumDuration - duration) > 0.01) {
            violations.add(taskId + " 三镜时长之和 " + sumDuration + " != 总时长 " + duration);
        }
        boolean zoomAllowed = params.path("zoomAllowed").asBoolean(false);
        if (!zoomAllowed && focals.size() > 1) {
            violations.add(taskId + " 未声明 zoomAllowed 却出现多个焦段: " + focals);
        }
    }

    private static void checkDoorProjection(List<String> violations, String taskId,
                                            String shotId, double focal, double doorHeight,
                                            double ratio, double distance, String label) {
        if (focal <= 0 || distance <= 0 || doorHeight <= 0 || ratio <= 0) return;
        double projected = doorHeight * focal / (distance * SENSOR_HEIGHT_MM);
        if (Math.abs(projected - ratio) > RATIO_TOLERANCE) {
            violations.add(taskId + " 镜" + shotId + " " + label
                    + " 焦段/距离/门洞占高不自洽: 计算 "
                    + String.format("%.2f", projected) + " vs 声明 " + ratio);
        }
    }

    private static void validateShots(List<String> violations, String taskId, JsonNode shots) {
        if (!shots.isArray()) return;
        for (JsonNode shot : shots) {
            String id = shot.path("id").asText("?");
            double focal = shot.path("focalLengthMm").asDouble(0);
            double aperture = shot.path("aperture").asDouble(0);
            double cameraHeight = shot.path("cameraHeightM").asDouble(0);
            double start = shot.path("startDistanceM").asDouble(Double.NaN);
            double move = shot.path("moveDistanceM").asDouble(Double.NaN);
            double end = shot.path("endDistanceM").asDouble(Double.NaN);
            double doorHeight = shot.path("doorHeightM").asDouble(0);
            double fallbackRatio = shot.path("doorHeightRatio").asDouble(0);
            double ratioStart = shot.path("doorHeightRatioStart").asDouble(fallbackRatio);
            double ratioEnd = shot.path("doorHeightRatioEnd").asDouble(fallbackRatio);
            double fallbackDistance = shot.path("doorDistanceM").asDouble(0);
            double doorDistanceStart = shot.path("doorDistanceStartM").asDouble(fallbackDistance);
            double doorDistanceEnd = shot.path("doorDistanceEndM").asDouble(fallbackDistance);

            if (focal > 0 && focal < 8 || focal > 400) {
                violations.add(taskId + " 镜" + id + " 焦距不合理: " + focal + "mm");
            }
            if (aperture > 0 && (aperture < 1.0 || aperture > 32)) {
                violations.add(taskId + " 镜" + id + " 光圈不合理: f/" + aperture);
            }
            if (cameraHeight > 0 && (cameraHeight < 0.2 || cameraHeight > 10)) {
                violations.add(taskId + " 镜" + id + " 机位高度不合理: " + cameraHeight + "m");
            }
            if (!Double.isNaN(start) && !Double.isNaN(move) && !Double.isNaN(end)
                    && Math.abs(start + move - end) > DISTANCE_TOLERANCE_M) {
                violations.add(taskId + " 镜" + id + " 推进距离不闭合: "
                        + start + "+" + move + " != " + end);
            }
            checkDoorProjection(violations, taskId, id, focal, doorHeight,
                    ratioStart, doorDistanceStart, "Start");
            checkDoorProjection(violations, taskId, id, focal, doorHeight,
                    ratioEnd, doorDistanceEnd, "End");
        }
    }

    private static JsonNode parameters(String output) {
        if (output == null) return null;
        int marker = output.indexOf("[PARAMS]");
        if (marker < 0) return null;
        int start = marker + "[PARAMS]".length();
        int end = output.indexOf('\n', start);
        String raw = end < 0 ? output.substring(start) : output.substring(start, end);
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
