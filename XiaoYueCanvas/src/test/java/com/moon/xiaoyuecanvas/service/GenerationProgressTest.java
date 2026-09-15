package com.moon.xiaoyuecanvas.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationProgressTest {

    @Test
    void readsProviderPercentAndFractionProgress() {
        assertEquals(50, GenerationProgress.fromProvider("50", 5));
        assertEquals(42, GenerationProgress.fromProvider("0.42", 5));
        assertEquals(75, GenerationProgress.fromProvider("75%", 5));
    }

    @Test
    void fallsBackToIncrementalProgressWhenProviderHasNoProgress() {
        assertEquals(10, GenerationProgress.fromProvider(null, 5));
        assertEquals(95, GenerationProgress.fromProvider(null, 94));
    }

    @Test
    void neverMovesBackwardOrBeyondTheBusyCeiling() {
        assertEquals(60, GenerationProgress.fromProvider("30", 60));
        assertEquals(95, GenerationProgress.fromProvider("100", 90));
        assertEquals(90, GenerationProgress.tick(88, 90, 5));
    }
}
