package com.moon.sandbox;

import com.moon.sandbox.session.MoonSandboxProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "moon.sandbox.workspace-root=${java.io.tmpdir}/moon-sandbox-context-test",
        "moon.sandbox.cleanup-initial-delay=1h",
        "moon.sandbox.cleanup-delay=1h"
})
class MoonSandboxApplicationTest {

    @Autowired
    MoonSandboxProperties properties;

    @Test
    void contextLoads() {
        assertThat(properties.getWorkspaceRoot()).isNotNull();
    }
}
