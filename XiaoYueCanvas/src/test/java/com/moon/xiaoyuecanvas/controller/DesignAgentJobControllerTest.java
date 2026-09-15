package com.moon.xiaoyuecanvas.controller;

import com.moon.moonagent.service.FileStorageService;
import com.moon.xiaoyuecanvas.service.CanvasAssetService;
import com.moon.xiaoyuecanvas.service.DesignJobService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesignAgentJobControllerTest {

    @Test
    void eventsEndpointPassesAfterSeqAndLastEventId() {
        DesignJobService service = mock(DesignJobService.class);
        DesignAgentJobController controller = new DesignAgentJobController(
                service, mock(CanvasAssetService.class), mock(FileStorageService.class));
        SseEmitter expected = new SseEmitter();
        when(service.subscribeAndRun("user-1", "job-1", 4L, "job-1:3")).thenReturn(expected);

        SseEmitter actual = controller.events("user-1", "job-1", 4L, "job-1:3");

        assertSame(expected, actual);
        verify(service).subscribeAndRun("user-1", "job-1", 4L, "job-1:3");
    }
}
