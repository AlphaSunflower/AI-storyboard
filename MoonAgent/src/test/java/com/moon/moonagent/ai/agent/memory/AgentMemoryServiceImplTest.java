package com.moon.moonagent.ai.agent.memory;

import com.moon.moonagent.entity.AgentGlobalMemory;
import com.moon.moonagent.entity.AgentMemory;
import com.moon.moonagent.entity.AgentMemorySetting;
import com.moon.moonagent.mapper.AgentGlobalMemoryMapper;
import com.moon.moonagent.mapper.AgentMemoryMapper;
import com.moon.moonagent.mapper.AgentMemorySettingMapper;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryServiceImplTest {

    private static AgentMemoryServiceImpl service(AgentMemoryMapper mapper) {
        return new AgentMemoryServiceImpl(mapper,
                mock(AgentGlobalMemoryMapper.class), mock(AgentMemorySettingMapper.class));
    }

    @Test
    void rememberInsertsNormalizedFact() {
        AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(AgentMemory.class))).thenReturn(1);
        AgentMemoryServiceImpl service = service(mapper);

        AgentMemory memory = service.remember("u1", "p1", "c1", "style",
                "  电影感低饱和  ", "agent", 0.9, null);

        assertThat(memory.getProjectId()).isEqualTo("p1");
        assertThat(memory.getKind()).isEqualTo("style");
        assertThat(memory.getContent()).isEqualTo("电影感低饱和");
        assertThat(memory.getContentHash()).hasSize(64);
        verify(mapper).insert(any(AgentMemory.class));
    }

    @Test
    void rememberIsRejectedWhenMemoryIsDisabled() {
        AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
        AgentMemorySettingMapper settings = mock(AgentMemorySettingMapper.class);
        AgentMemorySetting disabled = new AgentMemorySetting();
        disabled.setEnabled(false);
        when(settings.selectById("u1")).thenReturn(disabled);
        AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(
                mapper, mock(AgentGlobalMemoryMapper.class), settings);

        assertThatThrownBy(() -> service.remember(
                "u1", "p1", "c1", "style", "关闭后不该写入", "agent", 0.8, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("记忆管理已关闭");
        verify(mapper, never()).insert(any(AgentMemory.class));
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
        AgentMemoryServiceImpl service = service(mapper);

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
        AgentMemoryServiceImpl service = service(mapper);

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
        AgentMemoryServiceImpl service = service(mapper);

        assertThat(service.delete("p1", "m1")).isTrue();

        verify(mapper).delete(any());
    }

    @Test
    void rememberStopsAtProjectCapacity() {
        AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.selectCount(any())).thenReturn((long) AgentMemoryLimits.PROJECT_MAX);
        AgentMemoryServiceImpl service = service(mapper);

        assertThatThrownBy(() -> service.remember(
                "u1", "p1", "c1", "decision", "新的项目事实", "agent", 0.8, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("项目记忆已达上限");
        verify(mapper, never()).insert(any(AgentMemory.class));
    }

    @Test
    void rememberStopsAtUserCapacityEvenWhenProjectHasRoom() {
        AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.selectCount(any())).thenReturn(0L, (long) AgentMemoryLimits.USER_MAX);
        AgentMemoryServiceImpl service = service(mapper);

        assertThatThrownBy(() -> service.remember(
                "u1", "p1", "c1", "style", "跨项目已满", "agent", 0.8, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号记忆已达上限");
        verify(mapper, never()).insert(any(AgentMemory.class));
    }

    @Test
    void mergingAnExistingFactDoesNotConsumeCapacity() {
        AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
        AgentMemory existing = new AgentMemory();
        existing.setId("m1");
        existing.setUserId("u1");
        existing.setProjectId("p1");
        existing.setContentHash("old");
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any(AgentMemory.class))).thenReturn(1);
        AgentMemoryServiceImpl service = service(mapper);

        AgentMemory merged = service.remember(
                "u1", "p1", "c1", "style", "电影感低饱和", "agent", 0.9, null);

        assertThat(merged.getId()).isEqualTo("m1");
        verify(mapper).updateById(any(AgentMemory.class));
        verify(mapper, never()).selectCount(any());
    }

    @Test
    void limitsExposeTheEnforcedCaps() {
        AgentMemoryServiceImpl service = service(mock(AgentMemoryMapper.class));

        assertThat(service.limits().project()).isEqualTo(AgentMemoryLimits.PROJECT_MAX);
        assertThat(service.limits().user()).isEqualTo(AgentMemoryLimits.USER_MAX);
        assertThat(service.limits().contentChars()).isEqualTo(AgentMemoryLimits.CONTENT_MAX_CHARS);
    }

    @Test
    void missingSettingMeansEnabled() {
        AgentMemorySettingMapper settings = mock(AgentMemorySettingMapper.class);
        when(settings.selectById("u1")).thenReturn(null);
        AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(
                mock(AgentMemoryMapper.class), mock(AgentGlobalMemoryMapper.class), settings);

        assertThat(service.enabled("u1")).isTrue();
    }

    @Test
    void setEnabledInsertsThenUpdatesTheSwitch() {
        AgentMemorySettingMapper settings = mock(AgentMemorySettingMapper.class);
        when(settings.selectById("u1")).thenReturn(null, new AgentMemorySetting());
        AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(
                mock(AgentMemoryMapper.class), mock(AgentGlobalMemoryMapper.class), settings);

        service.setEnabled("u1", false);
        service.setEnabled("u1", true);

        verify(settings).insert(any(AgentMemorySetting.class));
        verify(settings).updateById(any(AgentMemorySetting.class));
    }

    @Test
    void saveGlobalCreatesTheSingleGlobalMemory() {
        AgentGlobalMemoryMapper global = mock(AgentGlobalMemoryMapper.class);
        when(global.selectById("u1")).thenReturn(null);
        AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(
                mock(AgentMemoryMapper.class), global, mock(AgentMemorySettingMapper.class));

        AgentGlobalMemory saved = service.saveGlobal("u1", "  所有项目都用中文回复  ");

        assertThat(saved.getContent()).isEqualTo("所有项目都用中文回复");
        assertThat(saved.getContentHash()).hasSize(64);
        verify(global).insert(any(AgentGlobalMemory.class));
    }

    @Test
    void saveGlobalOverwritesTheExistingRow() {
        AgentGlobalMemoryMapper global = mock(AgentGlobalMemoryMapper.class);
        AgentGlobalMemory existing = new AgentGlobalMemory();
        existing.setUserId("u1");
        existing.setContent("旧偏好");
        when(global.selectById("u1")).thenReturn(existing);
        when(global.updateById(any(AgentGlobalMemory.class))).thenReturn(1);
        AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(
                mock(AgentMemoryMapper.class), global, mock(AgentMemorySettingMapper.class));

        AgentGlobalMemory saved = service.saveGlobal("u1", "新偏好");

        assertThat(saved.getContent()).isEqualTo("新偏好");
        verify(global).updateById(any(AgentGlobalMemory.class));
        verify(global, never()).insert(any(AgentGlobalMemory.class));
    }

    @Test
    void saveGlobalIsRejectedWhenDisabled() {
        AgentMemorySettingMapper settings = mock(AgentMemorySettingMapper.class);
        AgentMemorySetting disabled = new AgentMemorySetting();
        disabled.setEnabled(false);
        when(settings.selectById("u1")).thenReturn(disabled);
        AgentGlobalMemoryMapper global = mock(AgentGlobalMemoryMapper.class);
        AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(
                mock(AgentMemoryMapper.class), global, settings);

        assertThatThrownBy(() -> service.saveGlobal("u1", "关闭后不能写"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("记忆管理已关闭");
        verify(global, never()).insert(any(AgentGlobalMemory.class));
    }

    @Test
    void clearGlobalDeletesByUser() {
        AgentGlobalMemoryMapper global = mock(AgentGlobalMemoryMapper.class);
        when(global.deleteById("u1")).thenReturn(1);
        AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(
                mock(AgentMemoryMapper.class), global, mock(AgentMemorySettingMapper.class));

        assertThat(service.clearGlobal("u1")).isTrue();
        verify(global).deleteById("u1");
    }

    @Test
    void globalPromptContextIsEmptyWhenDisabled() {
        AgentMemorySettingMapper settings = mock(AgentMemorySettingMapper.class);
        AgentMemorySetting disabled = new AgentMemorySetting();
        disabled.setEnabled(false);
        when(settings.selectById("u1")).thenReturn(disabled);
        AgentGlobalMemoryMapper global = mock(AgentGlobalMemoryMapper.class);
        AgentGlobalMemory memory = new AgentGlobalMemory();
        memory.setContent("所有项目都用中文回复");
        when(global.selectById("u1")).thenReturn(memory);
        AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(
                mock(AgentMemoryMapper.class), global, settings);

        assertThat(service.globalPromptContext("u1")).isEmpty();
    }

    @Test
    void globalPromptContextReturnsUserPreferenceWhenEnabled() {
        AgentGlobalMemoryMapper global = mock(AgentGlobalMemoryMapper.class);
        AgentGlobalMemory memory = new AgentGlobalMemory();
        memory.setContent(" 所有项目都用中文回复 ");
        when(global.selectById("u1")).thenReturn(memory);
        AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(
                mock(AgentMemoryMapper.class), global, mock(AgentMemorySettingMapper.class));

        assertThat(service.globalPromptContext("u1")).isEqualTo("所有项目都用中文回复");
    }
}
