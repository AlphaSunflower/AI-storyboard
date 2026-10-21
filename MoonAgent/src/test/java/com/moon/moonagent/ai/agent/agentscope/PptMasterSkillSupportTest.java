package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import com.moon.moonagent.ai.agent.sandbox.SandboxSkillResult;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PptMasterSkillSupportTest {

    @Test
    void detectsExplicitReferencesAndNaturalPptRequests() {
        assertThat(PptMasterSkillSupport.isReferenced("用 ppt-master 技能做一份汇报")).isTrue();
        assertThat(PptMasterSkillSupport.isReferenced("/ppt")).isTrue();
        assertThat(PptMasterSkillSupport.isReferenced("帮我做个PPT")).isTrue();
        assertThat(PptMasterSkillSupport.isReferenced("把这份 PPTX 美化一下")).isTrue();
        assertThat(PptMasterSkillSupport.isReferenced("生成十页幻灯片")).isTrue();
        assertThat(PptMasterSkillSupport.isReferenced("整理成演示文稿")).isTrue();
        assertThat(PptMasterSkillSupport.isReferenced("写一段 Java 代码")).isFalse();
    }

    @Test
    void doesNotTouchTheSandboxWhenPptIsNotReferenced() {
        MoonSandboxClient client = mock(MoonSandboxClient.class);
        when(client.enabled()).thenReturn(true);
        when(client.read(anyString(), anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("file does not exist"));
        PptMasterSkillSupport support = new PptMasterSkillSupport(client);

        assertThat(support.skillsForTurn("conversation-1", "写一段 Java 代码")).isEmpty();
        verify(client, never()).materializeSkill(anyString(), anyString());
    }

    @Test
    void materializesOnFirstReferenceAndStaysWarmForTheConversation() {
        MoonSandboxClient client = mock(MoonSandboxClient.class);
        when(client.enabled()).thenReturn(true);
        when(client.materializeSkill("conversation-1", PptMasterSkillSupport.SKILL_ID))
                .thenReturn(new SandboxSkillResult(
                        PptMasterSkillSupport.SKILL_ID, "skills/ppt-master",
                        true, 2, 10, "v1"));
        PptMasterSkillSupport support = new PptMasterSkillSupport(client);

        assertThat(support.skillsForTurn("conversation-1", "帮我做个PPT"))
                .isEqualTo(Set.of(PptMasterSkillSupport.SKILL_ID));
        // 后续回合正文不再出现 PPT，也不会丢技能（HITL 续流/继续编辑）。
        assertThat(support.skillsForTurn("conversation-1", "确认，继续"))
                .isEqualTo(Set.of(PptMasterSkillSupport.SKILL_ID));

        verify(client).materializeSkill("conversation-1", PptMasterSkillSupport.SKILL_ID);
    }

    @Test
    void doesNotExposeTheSkillWhenMaterializationFails() {
        MoonSandboxClient client = mock(MoonSandboxClient.class);
        when(client.enabled()).thenReturn(true);
        when(client.read(anyString(), anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("file does not exist"));
        when(client.materializeSkill(anyString(), eq(PptMasterSkillSupport.SKILL_ID)))
                .thenThrow(new RuntimeException("skill bundle is not installed"));
        PptMasterSkillSupport support = new PptMasterSkillSupport(client);

        assertThat(support.skillsForTurn("conversation-2", "帮我做个PPT")).isEmpty();
    }
}
