package com.llmgateway.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.vendor.video.dto.VendorCreateInput;
import com.llmgateway.vendor.video.model.ReferenceImageRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VideoGatewayServiceToCreateInputTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void stringArray_parsedAsReferenceRole() {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("referenceImages");
        arr.add("http://x/a.png");
        arr.add("http://x/b.png");

        VendorCreateInput in = VideoGatewayServiceImpl.toCreateInput(body);

        assertEquals(2, in.referenceImages().size());
        assertEquals("http://x/a.png", in.referenceImages().get(0).url());
        assertEquals(ReferenceImageRole.REFERENCE, in.referenceImages().get(0).role());
        assertEquals(ReferenceImageRole.REFERENCE, in.referenceImages().get(1).role());
    }

    @Test
    void objectArray_parsedEachRole() {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("referenceImages");
        ObjectNode o1 = arr.addObject();
        o1.put("url", "http://x/a.png");
        o1.put("role", "FIRST_FRAME");
        ObjectNode o2 = arr.addObject();
        o2.put("url", "http://x/b.png");
        o2.put("role", "LAST_FRAME");

        VendorCreateInput in = VideoGatewayServiceImpl.toCreateInput(body);

        assertEquals(2, in.referenceImages().size());
        assertEquals(ReferenceImageRole.FIRST_FRAME, in.referenceImages().get(0).role());
        assertEquals(ReferenceImageRole.LAST_FRAME, in.referenceImages().get(1).role());
    }

    @Test
    void objectWithoutRole_defaultsToReference() {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("referenceImages");
        arr.addObject().put("url", "http://x/a.png");

        VendorCreateInput in = VideoGatewayServiceImpl.toCreateInput(body);

        assertEquals(1, in.referenceImages().size());
        assertEquals(ReferenceImageRole.REFERENCE, in.referenceImages().get(0).role());
    }

    @Test
    void parseReferenceImageRole_invalidReturnsNull() {
        assertNull(VideoGatewayServiceImpl.parseReferenceImageRole("BOGUS"));
        assertNull(VideoGatewayServiceImpl.parseReferenceImageRole(""));
        assertEquals(ReferenceImageRole.FIRST_FRAME, VideoGatewayServiceImpl.parseReferenceImageRole("first_frame"));
    }
}
