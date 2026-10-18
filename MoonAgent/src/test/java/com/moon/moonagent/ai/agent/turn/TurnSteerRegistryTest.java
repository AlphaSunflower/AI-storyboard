package com.moon.moonagent.ai.agent.turn;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TurnSteerRegistryTest {

    @Test
    void openCloseAndStaleTurnGuard() {
        TurnSteerRegistry registry = new TurnSteerRegistry();
        ActiveTurnSteer first = registry.open(
                "c1", "t1", mock(AgentTurnEventPublisher.class));

        assertThat(registry.find("c1")).isSameAs(first);
        assertThat(registry.inboxFor("c1")).isSameAs(first.inbox());

        // 旧回合的 finally 不能删掉新回合
        registry.close("c1", "t0");
        assertThat(registry.find("c1")).isSameAs(first);

        registry.close("c1", "t1");
        assertThat(registry.find("c1")).isNull();
        assertThat(registry.inboxFor("c1")).isNull();
    }

    @Test
    void openingTheSameConversationReplacesThePreviousEntry() {
        TurnSteerRegistry registry = new TurnSteerRegistry();
        registry.open("c1", "t1", mock(AgentTurnEventPublisher.class));
        ActiveTurnSteer second = registry.open(
                "c1", "t2", mock(AgentTurnEventPublisher.class));

        assertThat(registry.find("c1")).isSameAs(second);
        assertThat(registry.find("c1").turnId()).isEqualTo("t2");
    }

    @Test
    void unconsumedSteerCarriesToTheNextTurn() {
        TurnSteerRegistry registry = new TurnSteerRegistry();
        ActiveTurnSteer first = registry.open(
                "c1", "t1", mock(AgentTurnEventPublisher.class));
        first.inbox().offer(new TurnSteerMessage(
                "m1", "上一轮没读到的补充", OffsetDateTime.now()));

        registry.close("c1", "t1");
        assertThat(registry.pendingCount("c1")).isEqualTo(1);

        ActiveTurnSteer second = registry.open(
                "c1", "t2", mock(AgentTurnEventPublisher.class));
        assertThat(second.inbox().drain())
                .extracting(TurnSteerMessage::messageId)
                .containsExactly("m1");
        assertThat(registry.pendingCount("c1")).isZero();
    }
}
