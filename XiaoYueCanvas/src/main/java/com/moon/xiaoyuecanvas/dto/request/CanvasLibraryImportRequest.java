package com.moon.xiaoyuecanvas.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CanvasLibraryImportRequest(
        @NotBlank String canvasId,
        @NotBlank String libraryAssetId,
        String producerNodeId
) {
}
