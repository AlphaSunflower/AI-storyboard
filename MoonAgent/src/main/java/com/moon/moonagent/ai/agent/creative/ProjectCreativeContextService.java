package com.moon.moonagent.ai.agent.creative;

import java.util.List;
import java.util.Map;

public interface ProjectCreativeContextService {

    ProjectCreativeContext get(String userId, String projectId);

    ProjectCreativeContext patch(String userId, String projectId, CreativeContextPatch patch);

    ConstraintLedger prepareTurn(String userId, String projectId, String prompt);

    String promptContext(String projectId, String prompt);

    record CreativeContextPatch(
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
}
