package com.moon.moonagent.ai.agent.specialist;

import com.moon.moonagent.ai.agent.handler.IntentHandler;
import com.moon.moonagent.ai.agent.skill.SkillManager;
import com.moon.moonagent.ai.agent.specialist.impl.HandlerDelegatingSpecialist;
import com.moon.moonagent.ai.agent.specialist.impl.GenericSkillSpecialist;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 专家注册表 —— intentType → SpecialistAgent 映射。
 *
 * <p>自动策略：有 SpecialistAgent 实现 → 用它；没有 → 自动包装为 HandlerDelegatingSpecialist。
 * 新增专家 = 新实现 SpecialistAgent + @Component，核心零改动。
 */
@Component
@RequiredArgsConstructor
public class SpecialistAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(SpecialistAgentRegistry.class);

    private final List<IntentHandler> intentHandlers;
    private final List<SpecialistAgent> specialists;
    private final SkillManager skillManager;
    private final GenericSkillSpecialist genericSkillSpecialist;

    private Map<String, SpecialistAgent> registry = Map.of();

    @jakarta.annotation.PostConstruct
    void init() {
        var map = new ConcurrentHashMap<String, SpecialistAgent>();

        // 1. 先注册显式 SpecialistAgent 实现（优先级高）
        for (SpecialistAgent s : specialists) {
            map.put(s.intentType(), s);
        }

        // 2. 无 SpecialistAgent 的 handler → 自动包装
        for (IntentHandler h : intentHandlers) {
            map.computeIfAbsent(h.intentType(), k -> {
                log.info("SpecialistAgentRegistry: {} 无显式 specialist，自动包装 HandlerDelegatingSpecialist", k);
                return new HandlerDelegatingSpecialist(h);
            });
        }

        registry = map;
        log.info("SpecialistAgentRegistry: 已注册 {} 个专家: {}", registry.size(), registry.keySet());
    }

    public Optional<SpecialistAgent> get(String intentType) {
        SpecialistAgent specialist = registry.get(intentType);
        if (specialist != null) {
            return Optional.of(specialist);
        }
        // 无专属 specialist/handler 但已注册技能 → 兜底通用执行器（handler-less 技能）
        if (intentType != null && !intentType.isBlank() && skillManager.byIntent(intentType).isPresent()) {
            log.info("SpecialistAgentRegistry: {} 无专属 specialist，回退 GenericSkillSpecialist", intentType);
            return Optional.of(genericSkillSpecialist);
        }
        return Optional.empty();
    }
}
