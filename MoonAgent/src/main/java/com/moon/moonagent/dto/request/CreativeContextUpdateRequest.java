package com.moon.moonagent.dto.request;

import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContext;

import java.util.List;
import java.util.Map;

public record CreativeContextUpdateRequest(
        String objective,
        String creativeDirection,
        String visualStyle,
        List<ProjectCreativeContext.Reference> references,
        List<ConstraintLedger.ConstraintItem> hardConstraints,
        List<ConstraintLedger.ConstraintItem> softConstraints,
        List<String> approvedAssetIds,
        List<String> rejectedDirections,
        List<String> feedback,
        Map<String, String> modelPreferences
) {
}
