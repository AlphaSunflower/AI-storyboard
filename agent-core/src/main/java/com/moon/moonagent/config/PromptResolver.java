package com.moon.moonagent.config;

import org.apache.commons.text.StringSubstitutor;

import java.util.Map;

/**
 * 提示词变量替换统一入口：用 {@link StringSubstitutor} 处理 {@code {{var}}} 占位符。
 *
 * <p>SKILL.md body 路径（{@code AgentOrchestratorSupport.prompt}）与 prompt YAML 路径
 * （{@link PromptConfig#get(String, Map)}）共用本工具，保证两套来源的变量替换语义一致——
 * 替换 {@code {{key}}} → 值；不在 vars 中的占位符原样保留；template 或 vars 为空时原样返回。
 *
 * <p>设计定案：只做共享，不改变占位符语义（仍为 {@code {{var}}}），让「改提示词不用碰 Java」
 * 在 skill-first 边界下保持统一。
 */
public final class PromptResolver {

    private PromptResolver() {
    }

    /** 替换模板中的 {@code {{var}}} 占位符（与前 {@code PromptConfig.get(name, vars)} 行为一致）。 */
    public static String resolve(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty() || vars == null || vars.isEmpty()) {
            return template;
        }
        StringSubstitutor sub = new StringSubstitutor(vars);
        sub.setVariablePrefix("{{");
        sub.setVariableSuffix("}}");
        return sub.replace(template);
    }
}
