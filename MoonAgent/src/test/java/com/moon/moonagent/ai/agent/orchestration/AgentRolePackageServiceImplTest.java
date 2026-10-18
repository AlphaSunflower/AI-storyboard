package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.entity.AgentInstalledPackage;
import com.moon.moonagent.mapper.AgentInstalledPackageMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRolePackageServiceImplTest {

    @Test
    void parsesEnabledRolesAndSkipsInvalidTools() {
        AgentInstalledPackageMapper mapper = mock(AgentInstalledPackageMapper.class);
        AgentInstalledPackage valid = row("p1", "researcher", """
                {"name":"researcher","description":"动态","prompt":"只读","tools":["recall_memory"],"maxTurns":4}
                """);
        AgentInstalledPackage invalid = row("p2", "bad", """
                {"name":"bad","description":"坏","prompt":"坏","tools":["not_a_tool"],"maxTurns":4}
                """);
        when(mapper.selectList(any())).thenReturn(List.of(valid, invalid));
        AgentRolePackageServiceImpl service = new AgentRolePackageServiceImpl(mapper);

        List<SubagentRole> roles = service.enabledRoles();

        assertThat(roles).extracting(SubagentRole::name).containsExactly("researcher");
        assertThat(roles.getFirst().maxTurns()).isEqualTo(4);
    }

    @Test
    void skipsManifestNameMismatch() {
        AgentInstalledPackageMapper mapper = mock(AgentInstalledPackageMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(row("p1", "researcher", """
                {"name":"other","description":"x","prompt":"x","tools":[],"maxTurns":4}
                """)));
        AgentRolePackageServiceImpl service = new AgentRolePackageServiceImpl(mapper);

        assertThat(service.enabledRoles()).isEmpty();
    }

    private static AgentInstalledPackage row(String id, String name, String manifest) {
        AgentInstalledPackage row = new AgentInstalledPackage();
        row.setId(id);
        row.setKind("role");
        row.setName(name);
        row.setVersion("1");
        row.setManifestJson(manifest);
        row.setEnabled(true);
        return row;
    }
}
