package com.moon.moonagent.ai.agent.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RouteChangeServiceTest {

    private final RouteChangeService service = new RouteChangeService();

    @Test
    void explicitStoryboardWinsOverVideoContext() {
        assertEquals("intent-aisplit", service.detectTargetIntent("我要分镜，不是视频"));
    }

    @Test
    void ordinaryStoryboardClarificationDoesNotSwitch() {
        assertNull(service.detectTargetIntent("根据这个资产做一个多图转场"));
    }

    @Test
    void switchToImageIsDetected() {
        assertEquals("intent-pic", service.detectTargetIntent("切换到图片，不要视频"));
    }
}
