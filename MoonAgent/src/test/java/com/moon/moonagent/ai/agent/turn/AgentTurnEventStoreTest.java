package com.moon.moonagent.ai.agent.turn;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentTurnEntity;
import com.moon.moonagent.entity.AgentTurnEventEntity;
import com.moon.moonagent.mapper.AgentTurnEventMapper;
import com.moon.moonagent.mapper.AgentTurnMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTurnEventStoreTest {

    @Test
    void startsAndAdvancesTurnSequence() {
        AgentTurnMapper turnMapper = mock(AgentTurnMapper.class);
        AgentTurnEventMapper eventMapper = mock(AgentTurnEventMapper.class);
        AgentTurnEventStore store = new AgentTurnEventStore(turnMapper, eventMapper);

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        AgentTurnEntity turn = store.start(conversation, "hello");
        when(turnMapper.selectById(turn.getId())).thenReturn(turn);

        AgentTurnEventEnvelope event =
                store.append(turn.getId(), "TEXT_BLOCK_DELTA", Map.of("delta", "hi"));

        assertThat(event.seq()).isEqualTo(1);
        assertThat(event.type()).isEqualTo("TEXT_BLOCK_DELTA");
        assertThat(event.payload()).containsEntry("delta", "hi");
        assertThat(turn.getLastSequence()).isEqualTo(1);
        verify(eventMapper).insert(any(AgentTurnEventEntity.class));
        verify(turnMapper).updateById(turn);
    }

    @Test
    void replaysOnlyEventsAfterRequestedSequence() {
        AgentTurnMapper turnMapper = mock(AgentTurnMapper.class);
        AgentTurnEventMapper eventMapper = mock(AgentTurnEventMapper.class);
        AgentTurnEventStore store = new AgentTurnEventStore(turnMapper, eventMapper);
        AgentTurnEventEntity first = event(1, "{\"delta\":\"a\"}");
        AgentTurnEventEntity second = event(2, "{\"delta\":\"b\"}");
        when(eventMapper.selectList(any())).thenReturn(List.of(first, second));

        List<AgentTurnEventEnvelope> events = store.eventsAfter("turn-1", 1);

        assertThat(events).extracting(AgentTurnEventEnvelope::seq).containsExactly(1, 2);
        assertThat(events.get(1).payload()).containsEntry("delta", "b");
    }

    private static AgentTurnEventEntity event(int sequence, String payload) {
        AgentTurnEventEntity event = new AgentTurnEventEntity();
        event.setTurnId("turn-1");
        event.setSequence(sequence);
        event.setEventType("TEXT_BLOCK_DELTA");
        event.setPayload(payload);
        event.setCreatedAt(OffsetDateTime.now());
        return event;
    }
}

