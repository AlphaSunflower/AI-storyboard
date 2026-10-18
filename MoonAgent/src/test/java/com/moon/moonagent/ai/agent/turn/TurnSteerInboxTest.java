package com.moon.moonagent.ai.agent.turn;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TurnSteerInboxTest {

    private static TurnSteerMessage message(String id, String content) {
        return new TurnSteerMessage(id, content, OffsetDateTime.now());
    }

    @Test
    void drainReturnsEachMessageExactlyOnce() {
        TurnSteerInbox inbox = new TurnSteerInbox();
        inbox.offer(message("m1", "改成暖色"));
        inbox.offer(message("m2", "保留第一张"));

        assertThat(inbox.size()).isEqualTo(2);
        assertThat(inbox.drain()).extracting(TurnSteerMessage::messageId)
                .containsExactly("m1", "m2");
        assertThat(inbox.drain()).isEmpty();
    }

    @Test
    void blankMessagesAreIgnored() {
        TurnSteerInbox inbox = new TurnSteerInbox();
        inbox.offer(null);
        inbox.offer(message("m1", "   "));

        assertThat(inbox.size()).isZero();
        assertThat(inbox.drain()).isEmpty();
    }
}
