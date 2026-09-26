package com.moon.moonagent.ai.storyboard.production;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RoleKind;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RoleProfile;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class StoryboardRoleRegistry {

    private static final Logger log = LoggerFactory.getLogger(StoryboardRoleRegistry.class);
    private static final int MAX_DOMAIN_ROLES = 3;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, RoleDefinition> roles = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        try (InputStream input = new ClassPathResource("storyboard/roles.json").getInputStream()) {
            List<RoleDefinition> configured = objectMapper.readValue(input, new TypeReference<>() {});
            for (RoleDefinition role : configured) {
                if (role != null && role.id() != null && !role.id().isBlank()) {
                    roles.put(role.id(), role);
                }
            }
        } catch (Exception e) {
            log.warn("storyboard role registry load failed, using built-in fallback: {}", e.getMessage());
        }
        if (roles.isEmpty()) {
            for (RoleDefinition role : fallbackRoles()) roles.put(role.id(), role);
        }
    }

    public List<RoleProfile> resolve(String script, List<String> hints, boolean multiSegment) {
        String haystack = (script == null ? "" : script).toLowerCase(Locale.ROOT);
        Set<String> requested = new LinkedHashSet<>();
        if (hints != null) {
            hints.stream().filter(item -> item != null && !item.isBlank())
                    .map(item -> item.trim().toLowerCase(Locale.ROOT)).forEach(requested::add);
        }

        List<RoleProfile> result = new ArrayList<>();
        add(result, "screenwriter");
        add(result, "director");
        if (multiSegment) add(result, "continuity-editor");

        int domainCount = 0;
        for (RoleDefinition role : roles.values()) {
            if (role.kind() != RoleKind.DOMAIN || domainCount >= MAX_DOMAIN_ROLES) continue;
            boolean requestedRole = requested.contains(role.id().toLowerCase(Locale.ROOT))
                    || requested.contains(role.label().toLowerCase(Locale.ROOT));
            boolean matched = role.keywords().stream().anyMatch(keyword -> !keyword.isBlank()
                    && haystack.contains(keyword.toLowerCase(Locale.ROOT)));
            if (requestedRole || matched) {
                add(result, role.id());
                domainCount++;
            }
        }
        if (domainCount == 0) add(result, "domain-expert");
        return List.copyOf(result);
    }

    public List<RoleProfile> defaults(boolean multiSegment) {
        List<RoleProfile> result = new ArrayList<>();
        add(result, "screenwriter");
        add(result, "director");
        if (multiSegment) add(result, "continuity-editor");
        add(result, "domain-expert");
        return List.copyOf(result);
    }

    private void add(List<RoleProfile> output, String id) {
        RoleDefinition role = roles.get(id);
        if (role == null || output.stream().anyMatch(item -> item.id().equals(role.id()))) return;
        output.add(new RoleProfile(role.id(), role.label(), role.kind(), role.instructions()));
    }

    private static List<RoleDefinition> fallbackRoles() {
        return List.of(
                new RoleDefinition("screenwriter", "编剧", RoleKind.CREATIVE,
                        "强化人物动机、因果关系、冲突推进与情绪弧光，避免工具人。", List.of()),
                new RoleDefinition("director", "导演", RoleKind.CREATIVE,
                        "确保叙事可拍、镜头连续、视觉节奏可执行。", List.of()),
                new RoleDefinition("continuity-editor", "连续性编辑", RoleKind.CREATIVE,
                        "检查跨段人物、时间线、道具、空间和伏笔一致性。", List.of()),
                new RoleDefinition("domain-expert", "领域专家", RoleKind.DOMAIN,
                        "根据剧情职业校验专业行为、术语、流程和现实约束；不确定时明确保留。", List.of())
        );
    }

    private record RoleDefinition(
            String id,
            String label,
            RoleKind kind,
            String instructions,
            List<String> keywords
    ) {
        private RoleDefinition {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }
}
