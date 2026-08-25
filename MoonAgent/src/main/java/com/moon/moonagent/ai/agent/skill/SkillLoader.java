package com.moon.moonagent.ai.agent.skill;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill 加载器：扫描 classpath:skills 各子目录下的 SKILL.md，解析其 YAML frontmatter + markdown body。
 * <pre>
 * ---
 * name: aisplit
 * description: 用户要求生成/优化/查看分镜剧本时使用
 * label: 生成分镜
 * model_role: text
 * tools: [writeScenes, replaceScenes]
 * triggers: ["分镜","到本板","剧本"]        # 有序触发正则/关键词
 * priority: 30                              # 数字越小越先匹配（delete 须先于 aisplit 的「分镜」）
 * hitl: [{id: agree, title: 满意}, ...]
 * ---
 * # 分镜生成知识
 * ### script_optimize
 * ...
 * </pre>
 *
 * <p>职责：解析 → {@link AgentSkill} record 注册表；供意图识别规则前置（triggers/priority）、
 * 澄清菜单（label）、模型角色（model_role）、HITL 卡 manifest（hitl）消费。Graph 流程仍在代码，不进 Skill。
 */
@Component
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    /** name → AgentSkill */
    private final Map<String, AgentSkill> skills = new ConcurrentHashMap<>();

    /** 按 priority 升序（先匹配）的 skill 列表，供规则前置遍历 */
    private volatile List<AgentSkill> ordered = List.of();

    @PostConstruct
    void loadAll() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:skills/*/SKILL.md");
            Yaml yaml = new Yaml();
            for (Resource res : resources) {
                try {
                    String content = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
                    Split split = splitFrontmatter(content);
                    if (split.yaml() == null || split.yaml().isBlank()) {
                        log.warn("[SkillLoader] 跳过无 frontmatter 的 SKILL.md: {}", res.getFilename());
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> frontmatter = yaml.load(split.yaml());
                    if (frontmatter == null || frontmatter.get("name") == null) {
                        log.warn("[SkillLoader] 跳过无 name 字段的 SKILL.md: {}", res.getFilename());
                        continue;
                    }
                    String name = String.valueOf(frontmatter.get("name"));
                    AgentSkill skill = AgentSkill.of(name, frontmatter, parseNodePrompts(split.body()));
                    skills.put(name, skill);
                    log.info("[SkillLoader] 已加载 Skill: name={} intent={} priority={} triggers={}",
                            name, skill.intentType(), skill.priority(), skill.triggers());
                } catch (Exception e) {
                    log.warn("[SkillLoader] 解析 SKILL.md 失败: {} — {}", res.getFilename(), e.getMessage());
                }
            }
            ordered = skills.values().stream()
                    .sorted(Comparator.comparingInt(AgentSkill::priority))
                    .toList();
            log.info("[SkillLoader] 共加载 {} 个 Skill: {}", skills.size(), ordered.stream().map(AgentSkill::name).toList());
        } catch (IOException e) {
            log.error("[SkillLoader] 扫描 skills/ 目录失败", e);
        }
    }

    /** 按 priority 升序（先匹配）的全部 skill */
    public List<AgentSkill> all() {
        return ordered;
    }

    /** 按 menu_order 升序（澄清菜单显示顺序）的全部 skill */
    public List<AgentSkill> allMenuOrdered() {
        return skills.values().stream()
                .sorted(Comparator.comparingInt(AgentSkill::menuOrder))
                .toList();
    }

    /** 按 name 取 skill */
    public AgentSkill get(String name) {
        return skills.get(name);
    }

    /** 按 intentType 取 skill（无则 null） */
    public AgentSkill byIntent(String intentType) {
        return skills.values().stream()
                .filter(s -> s.intentType().equals(intentType))
                .findFirst().orElse(null);
    }

    /** 切分 frontmatter（YAML）与 body（markdown）：{@code ---\n{yaml}\n---\n{body}} */
    private static Split splitFrontmatter(String content) {
        // 仅匹配行首的 --- 分隔符（frontmatter 边界）
        String s = content == null ? "" : content;
        if (!s.startsWith("---")) return new Split("", s);
        int first = s.indexOf('\n', 3); // 首个 --- 后换行
        if (first < 0) return new Split("", s);
        int second = s.indexOf("\n---", first + 1); // 第二个 --- 行首
        if (second < 0) return new Split("", s);
        String yaml = s.substring(first + 1, second).trim();
        int bodyStart = s.indexOf('\n', second + 4); // 第二个 --- 后的换行
        String body = bodyStart < 0 ? "" : s.substring(bodyStart + 1);
        return new Split(yaml, body);
    }

    /** markdown body 按 {@code ### 步骤id} 分段 → {nodeId: promptText}（无 ### 分段则整段归 name） */
    static Map<String, String> parseNodePrompts(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return out;
        Matcher m = Pattern.compile("^###\\s+([\\w-]+)\\s*\\n(.*?)(?=^###\\s+[\\w-]+|\\z)", Pattern.MULTILINE | Pattern.DOTALL)
                .matcher(body);
        while (m.find()) {
            out.put(m.group(1).trim(), m.group(2).trim());
        }
        // 无 ### 分段：整段 body 作为默认知识
        return out.isEmpty() ? Map.of("_body", body.trim()) : out;
    }

    /** frontmatter + body 切分结果 */
    private record Split(String yaml, String body) {}
}
