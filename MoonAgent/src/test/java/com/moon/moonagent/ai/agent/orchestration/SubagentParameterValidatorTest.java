package com.moon.moonagent.ai.agent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentParameterValidatorTest {

    @Test
    void acceptsConsistentParameterTables() {
        SubagentGraphResult result = result(
                params(25, 14, "16:9", 5600, 28, 5.6, 3.0, 1.5, 4.5, 7.0, 2.4, 0.4),
                params(25, 14, "16:9", 5600, 28, 5.6, 3.0, 1.5, 4.5, 7.0, 2.4, 0.4));

        assertThat(SubagentParameterValidator.validate(GRAPH, result)).isEmpty();
    }

    @Test
    void rejectsFpsMismatchAndOpenGeometry() {
        SubagentGraphResult result = result(
                params(25, 14, "16:9", 5600, 28, 5.6, 3.0, 1.5, 4.5, 7.0, 2.4, 0.4),
                params(24, 14, "16:9", 5600, 28, 5.6, 3.0, 1.2, 4.5, 7.0, 2.4, 0.4));

        List<String> violations = SubagentParameterValidator.validate(GRAPH, result);

        assertThat(violations).anyMatch(item -> item.contains("fps"));
        assertThat(violations).anyMatch(item -> item.contains("推进距离不闭合"));
    }

    @Test
    void rejectsLensGeometryThatDoesNotAddUp() {
        SubagentGraphResult result = result(
                params(25, 14, "16:9", 5600, 28, 5.6, 3.0, 1.5, 4.5, 7.0, 2.4, 0.7),
                params(25, 14, "16:9", 5600, 28, 5.6, 3.0, 1.5, 4.5, 7.0, 2.4, 0.7));

        assertThat(SubagentParameterValidator.validate(GRAPH, result))
                .anyMatch(item -> item.contains("焦段/距离/门洞占高不自洽"));
    }

    private static final SubagentTaskGraph GRAPH = new SubagentTaskGraph("params", List.of(
            new SubagentTask("script", "screenwriter", "写", List.of(), List.of(), List.of(),
                    6, "parameter-table"),
            new SubagentTask("visual", "visual-director", "画面", List.of("script"),
                    List.of(), List.of(), 6, "parameter-table")), 2, false);

    private static SubagentGraphResult result(String scriptParams, String visualParams) {
        return new SubagentGraphResult("params", Map.of(
                "script", SubagentTaskResult.completed("script", scriptParams, 1, 1),
                "visual", SubagentTaskResult.completed("visual", visualParams, 1, 1)),
                true, 2, 2, false);
    }

    private static String params(int fps, int duration, String aspect, int color,
                                 double focal, double aperture, double start, double move,
                                 double end, double distance, double doorHeight, double ratio) {
        return "[PARAMS]{\"fps\":" + fps
                + ",\"durationSeconds\":" + duration
                + ",\"aspectRatio\":\"" + aspect + "\""
                + ",\"colorTemperatureK\":" + color
                + ",\"shots\":[{\"id\":1,\"focalLengthMm\":" + focal
                + ",\"aperture\":" + aperture
                + ",\"cameraHeightM\":1.2"
                + ",\"startDistanceM\":" + start
                + ",\"moveDistanceM\":" + move
                + ",\"endDistanceM\":" + end
                + ",\"doorHeightM\":" + doorHeight
                + ",\"doorHeightRatio\":" + ratio
                + ",\"doorDistanceM\":" + distance + "}]}\nbody";
    }
}
