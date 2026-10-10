package com.moon.sandbox.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SandboxPathResolverTest {

    @TempDir
    Path root;

    @Test
    void resolvesRelativeWorkPathUnderSession() throws Exception {
        SandboxPathResolver resolver = new SandboxPathResolver(root);
        Path session = resolver.ensureSession("conversation-1");

        Path resolved = resolver.resolve(session, "work/report.md");

        assertThat(resolved).isEqualTo(session.resolve("work/report.md").normalize());
        assertThat(Files.isDirectory(session.resolve("work"))).isTrue();
        assertThat(Files.isDirectory(session.resolve("output"))).isTrue();
        assertThat(Files.isDirectory(session.resolve("vendor"))).isTrue();
    }

    @Test
    void rejectsParentTraversal() throws Exception {
        SandboxPathResolver resolver = new SandboxPathResolver(root);
        Path session = resolver.ensureSession("conversation-1");

        assertThatThrownBy(() -> resolver.resolve(session, "../outside.txt"))
                .isInstanceOf(SandboxPathException.class)
                .hasMessageContaining("escape");
    }

    @Test
    void rejectsSymlinkEscape() throws Exception {
        SandboxPathResolver resolver = new SandboxPathResolver(root);
        Path session = resolver.ensureSession("conversation-1");
        Path outside = Files.createDirectory(root.resolve("outside"));
        try {
            Files.createSymbolicLink(session.resolve("work/link"), outside);
        } catch (FileSystemException unavailable) {
            assumeTrue(false, "symbolic links are unavailable on this host");
        }

        assertThatThrownBy(() -> resolver.resolve(session, "work/link/secret.txt"))
                .isInstanceOf(SandboxPathException.class)
                .hasMessageContaining("escape");
    }
}
