package com.moon.sandbox.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class SandboxSessionServiceTest {

    @TempDir
    Path root;

    @Test
    void writesReadsAndListsFiles() {
        MoonSandboxProperties properties = MoonSandboxProperties.defaults();
        SandboxSessionService service = new SandboxSessionService(
                new SandboxPathResolver(root), properties);

        service.write("c1", "work/notes.md", "# Title\n", WriteMode.OVERWRITE);
        service.write("c1", "work/notes.md", "body\n", WriteMode.APPEND);

        var read = service.read("c1", "work/notes.md", 0, 100);
        var files = service.list("c1", "");

        assertThat(read.content()).isEqualTo("# Title\nbody\n");
        assertThat(read.nextOffset()).isEqualTo(read.content().length());
        assertThat(files).extracting(FileEntry::path)
                .contains("work/notes.md");
    }

    @Test
    void createDoesNotOverwriteExistingFile() {
        MoonSandboxProperties properties = MoonSandboxProperties.defaults();
        SandboxSessionService service = new SandboxSessionService(
                new SandboxPathResolver(root), properties);
        service.write("c1", "work/a.txt", "one", WriteMode.CREATE);

        assertThatThrownBy(() -> service.write("c1", "work/a.txt", "two", WriteMode.CREATE))
                .isInstanceOf(SandboxPathException.class)
                .hasMessageContaining("exists");
    }

    @Test
    void cleanupRemovesSessionDirectory() {
        MoonSandboxProperties properties = MoonSandboxProperties.defaults();
        SandboxSessionService service = new SandboxSessionService(
                new SandboxPathResolver(root), properties);
        service.write("c1", "work/a.txt", "one", WriteMode.CREATE);

        assertThat(service.cleanup("c1")).isTrue();
        assertThat(root.resolve("c1")).doesNotExist();
    }

    @Test
    void listsArbitraryFilesWithInferredMimeTypes() {
        MoonSandboxProperties properties = MoonSandboxProperties.defaults();
        SandboxSessionService service = new SandboxSessionService(
                new SandboxPathResolver(root), properties);
        service.write("c1", "output/tool.py", "print(1)", WriteMode.OVERWRITE);
        service.write("c1", "output/clip.mp4", "fake-video", WriteMode.OVERWRITE);
        service.write("c1", "output/notes.weird", "x", WriteMode.OVERWRITE);

        var files = service.listOutput("c1");

        assertThat(files)
                .extracting(FileEntry::path, FileEntry::mimeType)
                .containsExactlyInAnyOrder(
                        tuple("output/clip.mp4", "video/mp4"),
                        tuple("output/tool.py", "text/plain"),
                        tuple("output/notes.weird", "application/octet-stream"));
    }
}
