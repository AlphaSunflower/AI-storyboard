package com.storyboard.asset.dto;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record MultipartInitRequest(
        String projectId,
        AssetScope scope,
        @NotBlank String fileName,
        String relativePath,
        @NotBlank String mimeType,
        @Positive long sizeBytes,
        @NotBlank String sha256
) {
}
