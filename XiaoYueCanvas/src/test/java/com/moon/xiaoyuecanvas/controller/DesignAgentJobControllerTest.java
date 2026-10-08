package com.moon.xiaoyuecanvas.controller;

import com.moon.moonagent.service.FileStorageService;
import com.moon.xiaoyuecanvas.dto.request.CanvasLibraryImportRequest;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.moon.xiaoyuecanvas.service.CanvasAssetService;
import com.moon.xiaoyuecanvas.service.CanvasProjectLibraryService;
import com.moon.xiaoyuecanvas.service.DesignJobService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesignAgentJobControllerTest {

    @Test
    void eventsEndpointPassesAfterSeqAndLastEventId() {
        DesignJobService service = mock(DesignJobService.class);
        DesignAgentJobController controller = new DesignAgentJobController(
                service, mock(CanvasAssetService.class), mock(CanvasProjectLibraryService.class),
                mock(FileStorageService.class));
        SseEmitter expected = new SseEmitter();
        when(service.subscribeAndRun("user-1", "job-1", 4L, "job-1:3")).thenReturn(expected);

        SseEmitter actual = controller.events("user-1", "job-1", 4L, "job-1:3");

        assertSame(expected, actual);
        verify(service).subscribeAndRun("user-1", "job-1", 4L, "job-1:3");
    }

    @Test
    void groupImportEndpointDelegatesToProjectLibraryService() {
        CanvasProjectLibraryService libraryService = mock(CanvasProjectLibraryService.class);
        DesignAgentJobController controller = new DesignAgentJobController(
                mock(DesignJobService.class), mock(CanvasAssetService.class), libraryService,
                mock(FileStorageService.class));
        CanvasAsset image = new CanvasAsset();
        image.setId("canvas-image");
        CanvasAsset video = new CanvasAsset();
        video.setId("canvas-video");
        when(libraryService.importGroup("user-1", "canvas-1", "group-1", "node-1"))
                .thenReturn(List.of(image, video));

        List<CanvasAsset> imported = controller.importProjectLibraryGroup(
                "user-1", new CanvasLibraryImportRequest("canvas-1", "group-1", "node-1"));

        assertEquals(List.of(image, video), imported);
        verify(libraryService).importGroup("user-1", "canvas-1", "group-1", "node-1");
    }

    @Test
    void assetServesDurableMinioFilesThroughAuthenticatedCanvasEndpoint() {
        CanvasAssetService assets = mock(CanvasAssetService.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        CanvasAsset asset = new CanvasAsset();
        asset.setId("asset-1");
        asset.setObjectUrl("/api/files/images/reference.png?token=expired");
        asset.setType("image");
        asset.setMimeType("image/png");
        when(assets.getOwned("user-1", "asset-1")).thenReturn(asset);
        when(fileStorageService.readImage("reference.png")).thenReturn(new byte[]{1, 2, 3});
        DesignAgentJobController controller = new DesignAgentJobController(
                mock(DesignJobService.class), assets, mock(CanvasProjectLibraryService.class),
                fileStorageService);

        var response = controller.asset("user-1", "asset-1");

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof ByteArrayResource);
        assertEquals(3, ((ByteArrayResource) response.getBody()).getByteArray().length);
    }
}
