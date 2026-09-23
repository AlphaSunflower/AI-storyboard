package com.moon.moonagent.orchestration;

public record AssetRef(String assetId, String mediaId, String url, String role) {
    public AssetRef {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("asset url is required");
        }
        if (url.startsWith("data:") || url.startsWith("/") || url.startsWith("file:")) {
            throw new IllegalArgumentException("only controlled HTTP(S) asset references are accepted");
        }
        role = role == null ? "REFERENCE" : role.trim().toUpperCase();
    }
}
