package com.moon.xiaoyuecanvas.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanvasJobReplayCursorTest {

    @Test
    void explicitAfterSeqWinsOverLastEventId() {
        assertEquals(12L, CanvasJobReplayCursor.resolve(12L, "job-1:99"));
    }

    @Test
    void parsesLastEventIdJobIdAndSeq() {
        assertEquals(7L, CanvasJobReplayCursor.resolve(null, "job-1:7"));
    }

    @Test
    void invalidLastEventIdStartsFromBeginning() {
        assertEquals(0L, CanvasJobReplayCursor.resolve(null, "not-a-sequence"));
    }
}
