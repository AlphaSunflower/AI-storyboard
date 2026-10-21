package com.moon.sandbox.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

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
    void cleanupTransientRemovesStaleTmpAndRunnerFilesOnly() throws Exception {
        MoonSandboxProperties properties = MoonSandboxProperties.defaults();
        properties.setTransientTtl(Duration.ofMinutes(10));
        SandboxSessionService service = new SandboxSessionService(
                new SandboxPathResolver(root), properties);
        service.write("c1", "work/.runner/old.py", "print(1)", WriteMode.OVERWRITE);
        service.write("c1", "work/.runner/new.py", "print(2)", WriteMode.OVERWRITE);
        service.write("c1", "tmp/old.bin", "old", WriteMode.OVERWRITE);
        service.write("c1", "tmp/new.bin", "new", WriteMode.OVERWRITE);
        service.write("c1", "output/deck.pptx", "keep", WriteMode.OVERWRITE);

        Path session = root.resolve("c1");
        FileTime stale = FileTime.from(Instant.now().minus(Duration.ofHours(1)));
        Files.setLastModifiedTime(session.resolve("work/.runner/old.py"), stale);
        Files.setLastModifiedTime(session.resolve("tmp/old.bin"), stale);

        int removed = service.cleanupTransient();

        assertThat(removed).isEqualTo(2);
        assertThat(session.resolve("work/.runner/old.py")).doesNotExist();
        assertThat(session.resolve("tmp/old.bin")).doesNotExist();
        assertThat(session.resolve("work/.runner/new.py")).exists();
        assertThat(session.resolve("tmp/new.bin")).exists();
        // 用户产物不能被临时文件清理碰到
        assertThat(session.resolve("output/deck.pptx")).exists();
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
