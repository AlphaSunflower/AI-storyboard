package com.moon.moonagent.ai.agent.recovery;

import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckpointResumeServiceTest {

    @Test
    void claimsV3CheckpointAndPersistsResumePayload() {
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        AgentRecoveryLeaseService leases = mock(AgentRecoveryLeaseService.class);
        AgentCheckpoint checkpoint = checkpoint("pending", 3, "AGENTSCOPE");
        when(mapper.selectOne(any())).thenReturn(checkpoint);
        when(mapper.update(any(), any())).thenReturn(1);
        when(leases.tryClaim(anyString(), anyString(), anyString())).thenReturn(1);
        CheckpointResumeService service = new CheckpointResumeService(mapper, leases);

        CheckpointClaim claim = service.claim("c1", "token-1",
                new ResumeCommand("generate_image", "", Map.of(), List.of(), "", ""));

        assertThat(claim.checkpoint().getStatus()).isEqualTo("claimed");
        assertThat(claim.checkpoint().getResumePayload()).contains("generate_image");
        verify(mapper).update(any(), any());
    }

    @Test
    void rejectsPendingV2AgentScopeCheckpoint() {
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        AgentRecoveryLeaseService leases = mock(AgentRecoveryLeaseService.class);
        when(mapper.selectOne(any())).thenReturn(checkpoint("pending", 2, "AGENTSCOPE"));
        CheckpointResumeService service = new CheckpointResumeService(mapper, leases);

        assertThatThrownBy(() -> service.claim("c1", "token-1",
                new ResumeCommand("agree", "", Map.of(), List.of(), "", "")))
                .isInstanceOf(CheckpointResumeException.class)
                .hasMessageContaining("确认已失效");
    }

    @Test
    void completesAndReleasesLease() {
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        AgentRecoveryLeaseService leases = mock(AgentRecoveryLeaseService.class);
        AgentCheckpoint checkpoint = checkpoint("claimed", 3, "AGENTSCOPE");
        CheckpointClaim claim = new CheckpointClaim(checkpoint,
                new ResumeCommand("agree", "", Map.of(), List.of(), "", ""), "owner-1");
        CheckpointResumeService service = new CheckpointResumeService(mapper, leases);
        when(mapper.transitionIfLeased("cp-1", "used", "owner-1")).thenReturn(1);

        assertThat(service.complete(claim)).isTrue();

        verify(mapper).transitionIfLeased("cp-1", "used", "owner-1");
        verify(leases).release(any(RecoveryCandidate.class), org.mockito.ArgumentMatchers.eq("owner-1"));
    }

    @Test
    void staleLeaseOwnerCannotCompleteCheckpoint() {
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        AgentRecoveryLeaseService leases = mock(AgentRecoveryLeaseService.class);
        AgentCheckpoint checkpoint = checkpoint("claimed", 3, "AGENTSCOPE");
        CheckpointClaim claim = new CheckpointClaim(checkpoint,
                new ResumeCommand("agree", "", Map.of(), List.of(), "", ""), "stale-owner");
        CheckpointResumeService service = new CheckpointResumeService(mapper, leases);

        assertThat(service.complete(claim)).isFalse();

        verify(leases, never()).release(any(RecoveryCandidate.class), anyString());
    }

    private static AgentCheckpoint checkpoint(String status, int version, String owner) {
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setId("cp-1");
        checkpoint.setConversationId("c1");
        checkpoint.setFormToken("token-1");
        checkpoint.setStatus(status);
        checkpoint.setOwnerEngine(owner);
        checkpoint.setStateSchemaVersion(version);
        checkpoint.setExpirationTime(OffsetDateTime.now().plusMinutes(10));
        return checkpoint;
    }
}
