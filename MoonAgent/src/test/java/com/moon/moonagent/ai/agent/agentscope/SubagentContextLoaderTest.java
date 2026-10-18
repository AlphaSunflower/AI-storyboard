package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubagentContextLoaderTest {

    @Test
    void loadsFileUnderConfiguredRoot(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("spec.json"), "{\"fps\":25}");

        assertThat(SubagentContextLoader.load("spec.json", root.toString(), 1000))
                .isEqualTo("{\"fps\":25}");
    }

    @Test
    void rejectsTraversalAndOversize(@TempDir Path root) throws Exception {
        Path outside = root.getParent().resolve("outside-" + System.nanoTime() + ".txt");
        Files.writeString(outside, "secret");
        try {
            assertThatThrownBy(() -> SubagentContextLoader.load(
                    "../" + outside.getFileName(), root.toString(), 1000))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            Files.deleteIfExists(outside);
        }

        Files.writeString(root.resolve("big.json"), "x".repeat(200));
        assertThatThrownBy(() -> SubagentContextLoader.load("big.json", root.toString(), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
