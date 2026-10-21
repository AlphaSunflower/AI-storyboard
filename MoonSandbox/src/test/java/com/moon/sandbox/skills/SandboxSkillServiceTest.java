package com.moon.sandbox.skills;

import com.moon.sandbox.session.MoonSandboxProperties;
import com.moon.sandbox.session.SandboxPathException;
import com.moon.sandbox.session.SandboxPathResolver;
import com.moon.sandbox.session.SandboxSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxSkillServiceTest {

    @TempDir
    Path temp;

    @Test
    void materializesOnlyWhenCalledAndIsIdempotent() throws Exception {
        Path workspaces = temp.resolve("workspaces");
        Path bundles = temp.resolve("bundles");
        Path bundle = bundles.resolve("ppt-master");
        Files.createDirectories(bundle.resolve("scripts"));
        Files.writeString(bundle.resolve("SKILL.md"), "# skill", StandardCharsets.UTF_8);
        Files.writeString(bundle.resolve("scripts/run.py"), "print('ok')", StandardCharsets.UTF_8);

        SandboxSkillService service = service(workspaces, bundles);
        Path untouched = workspaces.resolve("other-conversation");
        assertThat(Files.exists(untouched.resolve("skills/ppt-master"))).isFalse();

        SandboxSkillResult first = service.materialize("conversation-1", "ppt-master");
        assertThat(first.installed()).isTrue();
        assertThat(first.path()).isEqualTo("skills/ppt-master");
        assertThat(first.files()).isEqualTo(2);
        assertThat(Files.readString(
                workspaces.resolve("conversation-1/skills/ppt-master/scripts/run.py")))
                .isEqualTo("print('ok')");

        SandboxSkillResult second = service.materialize("conversation-1", "ppt-master");
        assertThat(second.installed()).isFalse();
        assertThat(second.files()).isZero();
    }

    @Test
    void rejectsMissingBundleAndUnsafeSkillNames() throws Exception {
        SandboxSkillService service = service(
                temp.resolve("workspaces"), temp.resolve("bundles"));

        assertThatThrownBy(() -> service.materialize("conversation-1", "../etc"))
                .isInstanceOf(SandboxPathException.class)
                .hasMessageContaining("invalid skill name");
        assertThatThrownBy(() -> service.materialize("conversation-1", "ppt-master"))
                .isInstanceOf(SandboxPathException.class)
                .hasMessageContaining("not installed");
    }

    private static SandboxSkillService service(Path workspaces, Path bundles) throws Exception {
        Files.createDirectories(workspaces);
        Files.createDirectories(bundles);
        MoonSandboxProperties properties = MoonSandboxProperties.defaults();
        properties.setWorkspaceRoot(workspaces);
        properties.setSkillBundleRoot(bundles);
        SandboxPathResolver resolver = new SandboxPathResolver(workspaces);
        return new SandboxSkillService(new SandboxSessionService(resolver, properties), properties);
    }
}
