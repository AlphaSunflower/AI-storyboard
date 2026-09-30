package com.moon.moonagent.ai.agent.creative.impl;

import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.entity.ProjectCreativeContextEntity;
import com.moon.moonagent.mapper.ProjectCreativeContextMapper;
import com.moon.moonagent.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProjectCreativeContextServiceImplTest {

    @Test
    void prepareTurnPersistsConstraintsInsideTheSameProject() {
        ProjectCreativeContextMapper mapper = mock(ProjectCreativeContextMapper.class);
        ProjectService projects = mock(ProjectService.class);
        ProjectCreativeContextServiceImpl service = new ProjectCreativeContextServiceImpl(
                mapper, projects, new ConstraintExtractionServiceImpl());

        ProjectCreativeContextEntity entity = new ProjectCreativeContextEntity();
        entity.setProjectId("p1");
        entity.setUserId("u1");
        entity.setHardConstraintsJson("[]");
        entity.setSoftConstraintsJson("[]");
        entity.setVersion(1L);
        when(mapper.selectById("p1")).thenReturn(entity);

        ConstraintLedger ledger = service.prepareTurn(
                "u1", "p1", "必须保留红色主色，希望构图更简洁");

        verify(projects).get("u1", "p1");
        assertThat(ledger.hard()).isNotEmpty();
        assertThat(ledger.soft()).isNotEmpty();
        ArgumentCaptor<ProjectCreativeContextEntity> captor =
                ArgumentCaptor.forClass(ProjectCreativeContextEntity.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getHardConstraintsJson()).contains("必须保留红色主色");
        assertThat(captor.getValue().getSoftConstraintsJson()).contains("希望构图更简洁");
        assertThat(captor.getValue().getVersion()).isEqualTo(2L);
    }
}
