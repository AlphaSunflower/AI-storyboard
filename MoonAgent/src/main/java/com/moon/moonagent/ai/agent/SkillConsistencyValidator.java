package com.moon.moonagent.ai.agent;

import com.moon.moonagent.ai.agent.handler.IntentHandler;
import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillLoader;
import com.moon.moonagent.ai.agent.IntentRecognitionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Skill ↔ Handler 一致性校验（启动 fail-fast，防漂移）。
 *
 * <p>模块化约定：一个能力 = 一个 {@code SKILL.md} + 一个 {@code IntentHandler}。
 * 若只加了 handler 忘了写 skill（或反之），路由推导（规则前置表/澄清菜单）会与 handler 脱节，
 * 此处启动即报错，避免「能力声明了却没实现/没知识」的静默故障。
 *
 * <p>校验项：
 * <ol>
 *   <li>每个 {@link IntentHandler#intentType()} 都有对应 SKILL.md（reverse 同理——每个 skill 都有 handler）；</li>
 *   <li>非 other 能力必须声明 triggers / label / hitl（缺了路由或卡就废）；</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class SkillConsistencyValidator {

    private static final Logger log = LoggerFactory.getLogger(SkillConsistencyValidator.class);

    private final List<IntentHandler> handlers;
    private final SkillLoader skillLoader;

    @PostConstruct
    public void validate() {
        Set<String> handledTypes = new HashSet<>();
        for (IntentHandler h : handlers) {
            String type = h.intentType();
            handledTypes.add(type);
            AgentSkill s = skillLoader.byIntent(type);
            if (s == null) {
                throw fail("IntentHandler {} 缺少对应 SKILL.md（intentType={}）", h.getClass().getSimpleName(), type);
            }
            if (!IntentRecognitionService.FALLBACK_TYPE.equals(type)) {
                if (s.triggers().isEmpty()) {
                    throw fail("SKILL.md {} 缺少 triggers（intentType={}）——规则前置无法识别", s.name(), type);
                }
                if (s.label() == null || s.label().isBlank()) {
                    throw fail("SKILL.md {} 缺少 label（intentType={}）——澄清菜单无文案", s.name(), type);
                }
                if (s.hitlManifest().isEmpty()) {
                    throw fail("SKILL.md {} 缺少数 hitl 按钮（intentType={}）——HITL 卡无按钮", s.name(), type);
                }
            }
        }
        for (AgentSkill s : skillLoader.all()) {
            if (!handledTypes.contains(s.intentType())) {
                throw fail("SKILL.md {} 声明了能力 {}，但无对应 IntentHandler", s.name(), s.intentType());
            }
        }
        log.info("Skill 一致性校验通过：{} 个 Handler ↔ {} 个 SKILL.md 一一对应", handledTypes.size(), skillLoader.all().size());
    }

    private IllegalStateException fail(String fmt, Object... args) {
        String msg = "Skill 一致性校验失败：" + String.format(fmt, args);
        log.error(msg);
        return new IllegalStateException(msg);
    }
}
