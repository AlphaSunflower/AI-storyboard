package com.moon.moonagent.ai.agent.agentscope.workflow;

import java.util.List;

public final class AgentWorkflowCapabilityCatalog {

    public record Capability(
            String toolName,
            String category,
            String output,
            boolean parallelSafe
    ) {
    }

    private static final List<Capability> CAPABILITIES = List.of(
            new Capability("start_storyboard_production", "storyboard", "storyboard_run", false),
            new Capability("list_storyboard_draft", "storyboard", "storyboard_draft", true),
            new Capability("list_project_storyboard", "storyboard", "project_storyboard", true),
            new Capability("update_project_storyboard_shot", "storyboard", "updated_shot", false),
            new Capability("generate_storyboard_image", "image", "image_asset", false),
            new Capability("generate_image", "image", "image_asset", false),
            new Capability("generate_storyboard_video", "video", "video_asset", false),
            new Capability("generate_video", "video", "video_asset", false),
            new Capability("merge_storyboard_videos", "video", "merged_video", false)
    );

    private AgentWorkflowCapabilityCatalog() {
    }

    public static List<Capability> all() {
        return CAPABILITIES;
    }

    public static boolean contains(String toolName) {
        return CAPABILITIES.stream().anyMatch(item -> item.toolName().equals(toolName));
    }

    public static String promptText() {
        return CAPABILITIES.stream()
                .map(item -> "- " + item.toolName() + " | category=" + item.category()
                        + " | output=" + item.output()
                        + " | parallelSafe=" + item.parallelSafe())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }
}
