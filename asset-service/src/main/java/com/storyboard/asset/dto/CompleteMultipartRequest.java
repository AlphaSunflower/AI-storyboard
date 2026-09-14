package com.storyboard.asset.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CompleteMultipartRequest(@NotEmpty List<Part> parts) {

    public record Part(int partNumber, String etag) {
    }
}
