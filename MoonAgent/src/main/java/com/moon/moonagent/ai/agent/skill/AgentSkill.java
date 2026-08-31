package com.moon.moonagent.ai.agent.skill;

import java.util.List;
import java.util.Map;

/**
 * 单个 Agent 能力的声明式知识包（Skill = YAML frontmatter 元数据 + markdown body 知识）。
 *
 * <p>设计定案（双轴分离）：
 * <ul>
 *   <li><b>YAML frontmatter</b>（本 record 承载）——能力的<b>声明式元数据</b>：路由触发（triggers/priority）、
 *       澄清菜单显示名（label）、模型角色（model_role）、工具清单（tools）、上限（limits）、HITL 卡按钮清单（hitl）。</li>
 *   <li><b>markdown body</b>（{@link #nodePrompts()}）——按 {@code ### 步骤id} 分段的知识/系统提示词。
 *       由 {@code GenericSkillSpecialist}（无 handler 通用执行器）作为系统提示词数据源消费；
 *       有 handler 的能力仍走 {@code PromptConfig}（prompts/**）。</li>
 * </ul>
 *
 * <p>Graph 仍是代码（{@code PlanGraph}）管流程/HITL/循环；Skill 只是被节点代码消费的 data，
 * 不声明 graph 拓扑（防「Skill 声明 Graph」反模式）。
 *
 * @param intentType 意图标识（intent-aisplit / intent-pic / ...），由 name 加 "intent-" 前缀推导
 * @param label     澄清卡片显示名（用户可点选的目标能力标题）
 * @param modelRole 模型角色（text / vision / image / video），AgentNodeClients / 各 LLM 调用据此选客户端
 * @param tools     声明式工具清单（AgentTools @Tool 名）
 * @param limits    行为上限（max_clarify_rounds / max_regenerate_rounds 等）
 * @param triggers  路由触发正则/关键词（有序，priority 升序先查——delete 须先于 aisplit 的「分镜」）
 * @param priority  触发优先级（数字越小越先匹配；当前 delete=10/scene-review=20/aisplit=30/video=40/pic=50）
 * @param menuOrder 澄清菜单显示顺序（数字越小越靠前；当前 aisplit=10/pic=20/video=30/scene-review=40/delete=50/other=60）
 * @param hitlManifest HITL 卡按钮清单（[{id, title}]；LLM 声明卡的 id 必须落在该清单内，保留 resume 路由确定性）
 * @param nodePrompts markdown body 按 {@code ### 步骤id} 分段的知识提示词（由 {@link #systemPrompt()} 消费，
 *                    供无 handler 的 {@code GenericSkillSpecialist} 作为系统提示词；有 handler 的能力仍走 PromptConfig）
 * @param flow handler-less 技能的可选「多步澄清流程」步骤 id 列表（顺序执行；缺省=单发）。每个 id 对应 body
 *             {@code ### <id>} 段，由 {@code GenericSkillSpecialist} 的步骤机消费。
 */
public record AgentSkill(
        String intentType,
        String name,
        String description,
        String label,
        String modelRole,
        List<String> tools,
        Map<String, Integer> limits,
        List<String> triggers,
        int priority,
        int menuOrder,
        List<Map<String, Object>> hitlManifest,
        Map<String, String> nodePrompts,
        List<String> flow) {

    /** 意图前缀：Skill name 与 intentType 的映射规则（handler.intentType() = "intent-" + name） */
    private static final String INTENT_PREFIX = "intent-";

    /** 从 name + frontmatter + body 构建（name 推导 intentType；body 仅作知识归属，暂不消费） */
    public static AgentSkill of(String name, Map<String, Object> frontmatter, Map<String, String> nodePrompts) {
        String label = str(frontmatter.get("label"));
        if (label.isBlank()) label = str(frontmatter.get("description"));
        @SuppressWarnings("unchecked")
        List<String> tools = toStrList(frontmatter.get("tools"));
        @SuppressWarnings("unchecked")
        List<String> triggers = toStrList(frontmatter.get("triggers"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hitl = (List<Map<String, Object>>) frontmatter.getOrDefault("hitl", List.of());
        return new AgentSkill(
                INTENT_PREFIX + name,
                name,
                str(frontmatter.get("description")),
                label,
                str(frontmatter.get("model_role")).isBlank() ? "text" : str(frontmatter.get("model_role")),
                tools,
                toIntMap(frontmatter.get("limits")),
                triggers,
                intVal(frontmatter.get("priority"), 100),
                intVal(frontmatter.get("menu_order"), 100),
                hitl == null ? List.of() : hitl,
                nodePrompts == null ? Map.of() : nodePrompts,
                toStrList(frontmatter.get("flow")));
    }

    /** 澄清菜单最终 id（与 handler.intentType() 对齐，resume/clarify 共用） */
    public String intentId() {
        return intentType;
    }

    /** 通用执行器系统提示词：描述 + body（{@code ### stepId} 段拼接）；无分段时用 {@code _body}。 */
    public String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        if (description != null && !description.isBlank()) {
            sb.append(description).append("\n\n");
        }
        if (nodePrompts != null && !nodePrompts.isEmpty()) {
            if (nodePrompts.containsKey("_body")) {
                sb.append(nodePrompts.get("_body"));
            } else {
                nodePrompts.values().forEach(p -> {
                    if (p != null && !p.isBlank()) sb.append(p).append("\n\n");
                });
            }
        }
        return sb.toString().trim();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intVal(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static List<String> toStrList(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(x -> !x.isBlank()).toList();
    }

    private static Map<String, Integer> toIntMap(Object o) {
        if (!(o instanceof Map<?, ?> m)) return Map.of();
        java.util.LinkedHashMap<String, Integer> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (v instanceof Number n) out.put(String.valueOf(k), n.intValue());
            else if (v instanceof String s) {
                try { out.put(String.valueOf(k), Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
            }
        });
        return out;
    }
}
