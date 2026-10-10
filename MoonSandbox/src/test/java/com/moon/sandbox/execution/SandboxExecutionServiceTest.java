package com.moon.sandbox.execution;

import com.moon.sandbox.session.MoonSandboxProperties;
import com.moon.sandbox.session.SandboxPathResolver;
import com.moon.sandbox.session.SandboxSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxExecutionServiceTest {

    @TempDir
    Path root;

    @Test
    void executesPythonAndReportsOutputFiles() {
        MoonSandboxProperties properties = MoonSandboxProperties.defaults();
        properties.setPythonExecutable(defaultPython());
        SandboxSessionService sessions = new SandboxSessionService(
                new SandboxPathResolver(root), properties);
        SandboxExecutionService execution = new SandboxExecutionService(
                sessions, properties);

        var result = execution.execute("c1",
                "from pathlib import Path\n"
                        + "print('hello')\n"
                        + "Path('output/result.txt').write_text('ok', encoding='utf-8')\n",
                List.of(), 30);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello");
        assertThat(result.files()).extracting(FileEntryView::path)
                .contains("output/result.txt");
    }

    private static String defaultPython() {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? "python" : "python3";
    }
}
