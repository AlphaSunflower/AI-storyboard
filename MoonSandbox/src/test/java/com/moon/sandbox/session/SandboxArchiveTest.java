package com.moon.sandbox.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxArchiveTest {

    @TempDir
    Path root;

    @Test
    void exportsAndReimportsWorkspace() {
        MoonSandboxProperties properties = MoonSandboxProperties.defaults();
        properties.setWorkspaceRoot(root);
        SandboxSessionService sessions = new SandboxSessionService(
                new SandboxPathResolver(root), properties);

        sessions.write("c1", "output/report.md", "hello", WriteMode.OVERWRITE);
        byte[] archive = sessions.exportArchive("c1");
        sessions.cleanup("c1");

        sessions.importArchive("c1", archive);

        ReadFileResult read = sessions.read("c1", "output/report.md", 0, 100);
        assertThat(read.content()).isEqualTo("hello");
    }

    @Test
    void writesRawBytesForNativeUpload() {
        MoonSandboxProperties properties = MoonSandboxProperties.defaults();
        properties.setWorkspaceRoot(root);
        SandboxSessionService sessions = new SandboxSessionService(
                new SandboxPathResolver(root), properties);

        byte[] content = "binary".getBytes(StandardCharsets.UTF_8);
        sessions.writeBytes("c1", "output/data.bin", content, WriteMode.OVERWRITE);

        assertThat(sessions.readBytes("c1", "output/data.bin")).isEqualTo(content);
    }
}
