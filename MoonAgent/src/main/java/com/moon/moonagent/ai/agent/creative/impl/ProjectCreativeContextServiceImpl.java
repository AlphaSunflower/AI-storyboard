package com.moon.moonagent.ai.agent.creative.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.creative.ConstraintExtractionService;
import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContext;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.entity.ProjectCreativeContextEntity;
import com.moon.moonagent.mapper.ProjectCreativeContextMapper;
import com.moon.moonagent.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProjectCreativeContextServiceImpl implements ProjectCreativeContextService {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final ProjectCreativeContextMapper mapper;
    private final ProjectService projectService;
    private final ConstraintExtractionService constraints;

    @Override
    public ProjectCreativeContext get(String userId, String projectId) {
        projectService.get(userId, projectId);
        ProjectCreativeContextEntity entity = select(projectId);
        return entity == null ? empty(projectId, userId) : toView(entity);
    }

    @Override
    @Transactional
    public ProjectCreativeContext patch(String userId, String projectId,
                                        CreativeContextPatch patch) {
        projectService.get(userId, projectId);
        ProjectCreativeContextEntity entity = find(projectId, userId);
        if (patch.objective() != null) entity.setObjective(patch.objective().trim());
        if (patch.creativeDirection() != null) {
            entity.setCreativeDirection(patch.creativeDirection().trim());
        }
        if (patch.visualStyle() != null) entity.setVisualStyle(patch.visualStyle().trim());
        if (patch.references() != null) {
            entity.setReferencesJson(write(patch.references()));
        }
        if (patch.hardConstraints() != null) {
            entity.setHardConstraintsJson(write(patch.hardConstraints()));
        }
        if (patch.softConstraints() != null) {
            entity.setSoftConstraintsJson(write(patch.softConstraints()));
        }
        if (patch.approvedAssetIds() != null) {
            entity.setApprovedAssetsJson(write(patch.approvedAssetIds()));
        }
        if (patch.rejectedDirections() != null) {
            entity.setRejectedJson(write(patch.rejectedDirections()));
        }
        if (patch.feedback() != null) {
            entity.setFeedbackJson(write(patch.feedback()));
        }
        if (patch.modelPreferences() != null) {
            entity.setModelPreferencesJson(write(patch.modelPreferences()));
        }
        entity.setVersion((entity.getVersion() == null ? 0 : entity.getVersion()) + 1);
        entity.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(entity);
        return toView(entity);
    }

    @Override
    @Transactional
    public ConstraintLedger prepareTurn(String userId, String projectId, String prompt) {
        projectService.get(userId, projectId);
        ConstraintLedger extracted = constraints.extract(prompt);
        ProjectCreativeContextEntity entity = find(projectId, userId);
        List<ConstraintLedger.ConstraintItem> hard = mergeConstraints(
                readList(entity.getHardConstraintsJson(),
                        new TypeReference<List<ConstraintLedger.ConstraintItem>>() {}),
                extracted.hard());
        List<ConstraintLedger.ConstraintItem> soft = mergeConstraints(
                readList(entity.getSoftConstraintsJson(),
                        new TypeReference<List<ConstraintLedger.ConstraintItem>> () {}),
                extracted.soft());
        boolean changed = hard.size() != readList(entity.getHardConstraintsJson(),
                new TypeReference<List<ConstraintLedger.ConstraintItem>> () {}).size()
                || soft.size() != readList(entity.getSoftConstraintsJson(),
                new TypeReference<List<ConstraintLedger.ConstraintItem>> () {}).size();
        if (changed) {
            entity.setHardConstraintsJson(write(hard));
            entity.setSoftConstraintsJson(write(soft));
            entity.setVersion((entity.getVersion() == null ? 0 : entity.getVersion()) + 1);
            entity.setUpdatedAt(OffsetDateTime.now());
            mapper.updateById(entity);
        }
        return new ConstraintLedger(extracted.mode(), hard, soft, extracted.unresolved());
    }

    @Override
    public String promptContext(String projectId, String prompt) {
        if (projectId == null || projectId.isBlank()) return "";
        ProjectCreativeContextEntity entity = select(projectId);
        if (entity == null) return "";
        ProjectCreativeContext context = toView(entity);
        List<String> lines = new ArrayList<>();
        add(lines, "Project objective", context.objective());
        add(lines, "Creative direction", context.creativeDirection());
        add(lines, "Visual style", context.visualStyle());
        if (!context.hardConstraints().isEmpty()) {
            lines.add("Hard constraints (must not be silently changed): "
                    + compactConstraints(context.hardConstraints()));
        }
        if (!context.softConstraints().isEmpty()) {
            lines.add("Soft preferences: " + compactConstraints(context.softConstraints()));
        }
        if (!context.references().isEmpty()) {
            lines.add("Project references: " + context.references().stream()
                    .limit(12)
                    .map(ref -> ref.role() + "=" + ref.assetId() + " " + ref.url())
                    .toList());
        }
        if (!context.rejectedDirections().isEmpty()) {
            lines.add("Rejected directions: " + context.rejectedDirections().stream()
                    .limit(8).toList());
        }
        if (!context.feedback().isEmpty()) {
            lines.add("Recent project feedback: " + context.feedback().stream()
                    .limit(6).toList());
        }
        ConstraintLedger current = constraints.extract(prompt);
        lines.add("Current turn mode: " + current.mode());
        if (!current.unresolved().isEmpty()) {
            lines.add("Current turn needs clarification before execution: " + current.unresolved());
        }
        String rendered = String.join("\n", lines);
        return rendered.length() <= 5000 ? rendered : rendered.substring(0, 5000);
    }

    private ProjectCreativeContextEntity find(String projectId, String userId) {
        ProjectCreativeContextEntity entity = select(projectId);
        if (entity != null) return entity;
        entity = new ProjectCreativeContextEntity();
        entity.setProjectId(projectId);
        entity.setUserId(userId);
        entity.setObjective("");
        entity.setCreativeDirection("");
        entity.setVisualStyle("");
        entity.setReferencesJson("[]");
        entity.setHardConstraintsJson("[]");
        entity.setSoftConstraintsJson("[]");
        entity.setApprovedAssetsJson("[]");
        entity.setRejectedJson("[]");
        entity.setFeedbackJson("[]");
        entity.setModelPreferencesJson("{}");
        entity.setVersion(0L);
        mapper.insert(entity);
        return entity;
    }

    private static ProjectCreativeContext empty(String projectId, String userId) {
        return new ProjectCreativeContext(
                projectId, userId, "", "", "",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 0, null);
    }

    private ProjectCreativeContextEntity select(String projectId) {
        return projectId == null || projectId.isBlank() ? null : mapper.selectById(projectId);
    }

    private static ProjectCreativeContext toView(ProjectCreativeContextEntity entity) {
        return new ProjectCreativeContext(
                entity.getProjectId(),
                entity.getUserId(),
                text(entity.getObjective()),
                text(entity.getCreativeDirection()),
                text(entity.getVisualStyle()),
                readList(entity.getReferencesJson(),
                        new TypeReference<List<ProjectCreativeContext.Reference>> () {}),
                readList(entity.getHardConstraintsJson(),
                        new TypeReference<List<ConstraintLedger.ConstraintItem>> () {}),
                readList(entity.getSoftConstraintsJson(),
                        new TypeReference<List<ConstraintLedger.ConstraintItem>> () {}),
                readList(entity.getApprovedAssetsJson(), new TypeReference<List<String>> () {}),
                readList(entity.getRejectedJson(), new TypeReference<List<String>> () {}),
                readList(entity.getFeedbackJson(), new TypeReference<List<String>> () {}),
                readMap(entity.getModelPreferencesJson()),
                entity.getVersion() == null ? 0 : entity.getVersion(),
                entity.getUpdatedAt()
        );
    }

    private static List<ConstraintLedger.ConstraintItem> mergeConstraints(
            List<ConstraintLedger.ConstraintItem> existing,
            List<ConstraintLedger.ConstraintItem> incoming) {
        Map<String, ConstraintLedger.ConstraintItem> merged = new LinkedHashMap<>();
        existing.forEach(item -> merged.put(item.id(), item));
        incoming.forEach(item -> merged.put(item.id(), item));
        return List.copyOf(merged.values());
    }

    private static String compactConstraints(List<ConstraintLedger.ConstraintItem> items) {
        return items.stream()
                .limit(12)
                .map(item -> item.severity() + ":" + item.statement())
                .toList()
                .toString();
    }

    private static void add(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) lines.add(label + ": " + value);
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("creative context serialization failed", e);
        }
    }

    private static <T> List<T> readList(String value, TypeReference<List<T>> type) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return List.copyOf(JSON.readValue(value, type));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static Map<String, String> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return Map.copyOf(JSON.readValue(value, new TypeReference<Map<String, String>> () {}));
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
