package com.llmgateway.vendor.video.impl;

import com.llmgateway.config.GatewayConfig;
import com.llmgateway.entity.Channel;
import com.llmgateway.vendor.video.dto.VideoVendorResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimaxVideoCancelTest {

    private final GatewayConfig config = new GatewayConfig();
    private final MinimaxVideoAdapter adapter = new MinimaxVideoAdapter(config);
    private final Channel channel = new Channel();

    @Test
    void buildCancelRequest_targetsV2Endpoint() {
        channel.setBaseUrl("https://api.minimax.cn/");
        var req = adapter.buildCancelRequest("task-123", channel, "key");
        assertNotNull(req);
        assertEquals("DELETE", req.method());
        assertTrue(req.uri().toString().contains("/v2/video_generation/task-123"));
    }

    @Test
    void buildCancelRequest_blankTaskId_returnsNull() {
        channel.setBaseUrl("https://api.minimax.cn/");
        assertNull(adapter.buildCancelRequest("  ", channel, "key"));
    }

    @Test
    void parseCancel_mapsCancelled() {
        VideoVendorResult r = adapter.parseCancel("{\"task_id\":\"t1\",\"action\":\"cancelled\",\"status\":\"cancelled\"}", "t1", channel, "key");
        assertEquals("cancelled", r.status());
        assertEquals("t1", r.taskId());
    }

    @Test
    void parseCancel_mapsDeleted() {
        VideoVendorResult r = adapter.parseCancel("{\"task_id\":\"t1\",\"action\":\"deleted\",\"status\":\"deleted\"}", "t1", channel, "key");
        assertEquals("deleted", r.status());
    }
}
