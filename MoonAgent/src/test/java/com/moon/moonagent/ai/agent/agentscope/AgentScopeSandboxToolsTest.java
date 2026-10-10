package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClientProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentScopeSandboxToolsTest {

    @Test
    void registersSandboxToolsWithExpectedReadOnlyPolicy() {
        MoonSandboxClient client = mock(MoonSandboxClient.class);
        when(client.enabled()).thenReturn(true);
        MoonSandboxClientProperties properties = new MoonSandboxClientProperties();
        properties.setEnabled(true);
        AgentScopeSandboxTools tools = new AgentScopeSandboxTools(
                client, properties, mock(AgentScopeAsyncTaskService.class));

        var registered = tools.tools();

        assertThat(registered).extracting(AgentScopeTool::getName)
                .containsExactly("write_file", "read_file", "list_files", "run_python_script");
        assertThat(registered).filteredOn(tool -> tool.getName().equals("read_file"))
                .allMatch(AgentScopeTool::isReadOnly);
        assertThat(registered).filteredOn(tool -> tool.getName().equals("list_files"))
                .allMatch(AgentScopeTool::isReadOnly);
    }
}
