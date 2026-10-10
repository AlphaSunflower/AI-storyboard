package com.moon.sandbox.web;

import com.moon.sandbox.execution.SandboxExecutionService;
import com.moon.sandbox.session.MoonSandboxProperties;
import com.moon.sandbox.session.SandboxPathResolver;
import com.moon.sandbox.session.SandboxSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SandboxControllerTest {

    @TempDir
    Path root;

    @Test
    void rejectsRequestWithoutInternalSecret() throws Exception {
        MoonSandboxProperties properties = properties();
        properties.setInternalSecret("secret");
        MockMvc mvc = mvc(properties);

        mvc.perform(get("/internal/sandbox/c1/files"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void writesAndReadsFileWithInternalSecret() throws Exception {
        MoonSandboxProperties properties = properties();
        properties.setInternalSecret("secret");
        MockMvc mvc = mvc(properties);

        mvc.perform(post("/internal/sandbox/c1/files/write")
                        .header("X-Internal-Secret", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"path":"work/note.md","content":"hello","mode":"OVERWRITE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("work/note.md"));

        mvc.perform(post("/internal/sandbox/c1/files/read")
                        .header("X-Internal-Secret", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"path":"work/note.md","offset":0,"maxChars":100}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("hello"));

        mvc.perform(get("/internal/sandbox/c1/files/download")
                        .header("X-Internal-Secret", "secret")
                        .param("path", "work/note.md"))
                .andExpect(status().isOk());
    }

    private MockMvc mvc(MoonSandboxProperties properties) {
        SandboxSessionService sessions = new SandboxSessionService(
                new SandboxPathResolver(root), properties);
        SandboxExecutionService execution = new SandboxExecutionService(sessions, properties);
        return MockMvcBuilders
                .standaloneSetup(new SandboxController(sessions, execution))
                .addFilters(new InternalAuthFilter(properties))
                .build();
    }

    private MoonSandboxProperties properties() {
        MoonSandboxProperties properties = MoonSandboxProperties.defaults();
        properties.setWorkspaceRoot(root);
        properties.setPythonExecutable(System.getProperty("os.name")
                .toLowerCase().contains("win") ? "python" : "python3");
        return properties;
    }
}
