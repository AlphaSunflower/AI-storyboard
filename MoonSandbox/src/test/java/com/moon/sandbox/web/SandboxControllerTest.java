package com.moon.sandbox.web;

import com.moon.sandbox.execution.SandboxExecutionService;
import com.moon.sandbox.session.MoonSandboxProperties;
import com.moon.sandbox.session.SandboxPathResolver;
import com.moon.sandbox.session.SandboxSessionService;
import com.moon.sandbox.skills.SandboxSkillService;
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

    @Test
    void materializesSkillOnlyWhenRequested() throws Exception {
        MoonSandboxProperties properties = properties();
        properties.setInternalSecret("secret");
        Path bundle = root.resolve("bundles/ppt-master");
        java.nio.file.Files.createDirectories(bundle.resolve("scripts"));
        java.nio.file.Files.writeString(bundle.resolve("SKILL.md"), "# skill");
        java.nio.file.Files.writeString(bundle.resolve("scripts/run.py"), "print('ok')");
        MockMvc mvc = mvc(properties);

        mvc.perform(post("/internal/sandbox/c1/skills/ppt-master/materialize")
                        .header("X-Internal-Secret", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("skills/ppt-master"))
                .andExpect(jsonPath("$.installed").value(true));
    }

    private MockMvc mvc(MoonSandboxProperties properties) {
        SandboxSessionService sessions = new SandboxSessionService(
                new SandboxPathResolver(root), properties);
        SandboxExecutionService execution = new SandboxExecutionService(sessions, properties);
        SandboxSkillService skills = new SandboxSkillService(sessions, properties);
        return MockMvcBuilders
                .standaloneSetup(new SandboxController(sessions, execution, skills))
                .addFilters(new InternalAuthFilter(properties))
                .build();
    }

    private MoonSandboxProperties properties() {
        MoonSandboxProperties properties = MoonSandboxProperties.defaults();
        properties.setWorkspaceRoot(root);
        properties.setSkillBundleRoot(root.resolve("bundles"));
        properties.setPythonExecutable(System.getProperty("os.name")
                .toLowerCase().contains("win") ? "python" : "python3");
        return properties;
    }
}
