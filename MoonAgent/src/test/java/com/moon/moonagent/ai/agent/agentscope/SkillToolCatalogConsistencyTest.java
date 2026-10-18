package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKILL.md 的 {@code allowed-tools} 只是给模型看的技能说明；真正的门控在 {@link ToolCatalog}。
 * 这个测试锁住两边不漂移：SKILL 里点名的工具必须在 catalog（或已知的框架/画布工具）里存在。
 */
class SkillToolCatalogConsistencyTest {

    private static final Set<String> FRAMEWORK_TOOLS = Set.of(
            "read_file", "write_file", "edit_file", "grep_files", "glob_files", "list_files",
            "execute", "deliver_artifact", "task_list", "task_output", "wait_async_results",
            "agent_list", "load_skill_through_path", "reset_equipped_tools");

    private static final List<String> SKILLS = List.of(
            "storyboard", "image", "video", "canvas-design", "canvas-run", "general");

    @Test
    void everyToolNamedByASkillExistsInTheCatalog() throws IOException {
        for (String skill : SKILLS) {
            for (String tool : allowedTools(skill)) {
                boolean known = ToolCatalog.spec(tool).isPresent()
                        || FRAMEWORK_TOOLS.contains(tool)
                        || tool.startsWith("canvas_");
                assertThat(known)
                        .as("%s/SKILL.md 的 allowed-tools 指向了不存在的工具: %s", skill, tool)
                        .isTrue();
            }
        }
    }

    private static List<String> allowedTools(String skill) throws IOException {
        String resource = "/skills/" + skill + "/SKILL.md";
        try (InputStream in = SkillToolCatalogConsistencyTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("缺少技能资源 %s", resource).isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseAllowedTools(text);
        }
    }

    private static List<String> parseAllowedTools(String markdown) {
        List<String> tools = new ArrayList<>();
        String[] lines = markdown.split("\\R");
        boolean collecting = false;
        for (String line : lines) {
            if (!collecting && line.startsWith("allowed-tools:")) {
                collecting = true;
                collect(line.substring("allowed-tools:".length()), tools);
                continue;
            }
            if (collecting) {
                if (line.trim().equals("---")) {
                    break;
                }
                collect(line, tools);
            }
        }
        return tools;
    }

    private static void collect(String line, List<String> tools) {
        for (String token : line.trim().split("\\s+")) {
            if (!token.isBlank() && !tools.contains(token)) {
                tools.add(token);
            }
        }
    }
}
