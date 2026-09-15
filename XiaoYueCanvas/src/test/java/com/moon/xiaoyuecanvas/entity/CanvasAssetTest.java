package com.moon.xiaoyuecanvas.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanvasAssetTest {

    @Test
    void defaultsToReadyStatus() {
        CanvasAsset asset = new CanvasAsset();
        asset.setId("asset-1");

        assertEquals("ready", asset.getStatus());
    }
}
