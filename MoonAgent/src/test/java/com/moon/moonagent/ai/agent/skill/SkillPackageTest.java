package com.moon.moonagent.ai.agent.skill;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SkillPackageTest {

    private static final Path SKILLS_ROOT = Path.of("src/main/resources/skills");

    @Test
    void loadsOnlyTheSixProductionSkills() throws Exception {
        Set<String> names;
        try (Stream<Path> paths = Files.list(SKILLS_ROOT)) {
            names = paths.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }

        assertThat(names).containsExactlyInAnyOrder(
                "storyboard", "image", "video", "canvas-design", "canvas-run", "general");
    }

    @Test
    void usesStandardFrontmatterAndMoonMetadata() throws Exception {
        Map<String, Object> image = frontmatter(SKILLS_ROOT.resolve("image/SKILL.md"));

        assertThat(image).containsKeys("name", "description", "metadata", "allowed-tools");
        assertThat(image).doesNotContainKeys("triggers", "priority", "menu_order");
        assertThat(String.valueOf(image.get("allowed-tools")))
                .contains("generate_image", "generate_image_batch");
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) image.get("metadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> moon = (Map<String, Object>) metadata.get("moon");
        assertThat(moon).containsEntry("model_role", "image");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> frontmatter(Path path) throws Exception {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        int start = content.indexOf("---");
        int end = content.indexOf("\n---", start + 3);
        assertThat(start).isZero();
        assertThat(end).isGreaterThan(start);
        return new Yaml().load(content.substring(start + 3, end));
    }
}
