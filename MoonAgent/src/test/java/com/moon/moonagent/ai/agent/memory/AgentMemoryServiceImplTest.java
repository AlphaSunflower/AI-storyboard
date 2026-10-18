package com.moon.moonagent.ai.agent.memory;

import com.moon.moonagent.entity.AgentMemory;
import com.moon.moonagent.mapper.AgentMemoryMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryServiceImplTest {

    @Test
    void rememberInsertsNormalizedFact() {
        AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(AgentMemory.class))).thenReturn(1);
        AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(mapper);

        AgentMemory memory = service.remember("u1", "p1", "c1", "style",
                "  电影感低饱和  ", "agent", 0.9, null);

        assertThat(memory.getProjectId()).isEqualTo("p1");
        assertThat(memory.getKind()).isEqualTo("style");
        assertThat(memory.getContent()).isEqualTo("电影感低饱和");
        assertThat(memory.getContentHash()).hasSize(64);
        verify(mapper).insert(any(AgentMemory.class));
    }

    @Test
    void promptContextFormatsRecalledMemories() {
        AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
        AgentMemory memory = new AgentMemory();
        memory.setId("m1");
        memory.setProjectId("p1");
        memory.setKind("constraint");
        memory.setContent("门洞必须 1.8×1.2m");
        memory.setConfidence(0.95);
        memory.setUpdatedAt(OffsetDateTime.now());
        when(mapper.selectList(any())).thenReturn(List.of(memory));
        AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(mapper);

        String prompt = service.promptContext("p1", "门洞", 5);

        assertThat(prompt)
                .contains("[constraint]")
                .contains("门洞必须 1.8×1.2m");
    }

    @Test
    void updateForUserRewritesContentAndHash() {
        AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
        AgentMemory memory = new AgentMemory();
        memory.setId("m1");
        memory.setUserId("u1");
        memory.setProjectId("p1");
        memory.setKind("style");
        memory.setContent("旧内容");
        memory.setContentHash("old");
        when(mapper.selectOne(any())).thenReturn(memory, null);
        when(mapper.updateById(any(AgentMemory.class))).thenReturn(1);
        AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(mapper);

        AgentMemory updated = service.updateForUser(
                "u1", "m1", "constraint", "新内容", 0.9, null);

        assertThat(updated.getKind()).isEqualTo("constraint");
        assertThat(updated.getContent()).isEqualTo("新内容");
        assertThat(updated.getContentHash()).hasSize(64);
        verify(mapper).updateById(any(AgentMemory.class));
    }

    @Test
    void deleteIsProjectScoped() {
        AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
        when(mapper.delete(any())).thenReturn(1);
        AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(mapper);

        assertThat(service.delete("p1", "m1")).isTrue();

        verify(mapper).delete(any());
    }
}
