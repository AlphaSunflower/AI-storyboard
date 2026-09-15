package com.moon.moonagent.ai.storyboard;

import java.util.List;

/**
 * Structured storyboard planning boundary.
 */
public interface StoryboardPlanningService {

    int PLAN_VERSION = 3;

    /**
     * Plans an ordered list of shots from a script.
     *
     * @param script source script
     * @return normalized shots in narrative order
     */
    List<StoryboardShot> plan(String script);

    /**
     * Plans shots with optional ordered reference images.
     *
     * @param script source script
     * @param referenceImages ordered image data URIs; empty for text-only planning
     * @return normalized shots in narrative order
     */
    List<StoryboardShot> plan(String script, List<String> referenceImages);
}
