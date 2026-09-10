package com.moon.moonagent.ai.agent.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentGenerationServiceImplClassificationTest {

    @Test
    void classifyImageByReference() {
        assertEquals("text_to_image", AgentGenerationServiceImpl.classifyImageGenType(null, null, null));
        assertEquals("image_to_image", AgentGenerationServiceImpl.classifyImageGenType(null, null, "/api/files/images/a.png"));
        assertEquals("image_to_image", AgentGenerationServiceImpl.classifyImageGenType(null, List.of("ref"), null));
        assertEquals("multi_image_to_image", AgentGenerationServiceImpl.classifyImageGenType("edit", List.of("a", "b"), null));
        assertEquals("multi_image_to_multi_image",
                AgentGenerationServiceImpl.classifyImageGenType("edit", List.of("a", "b"), null, 2));
        assertEquals("image_to_image", AgentGenerationServiceImpl.classifyImageGenType("edit", null, null));
    }

    @Test
    void classifyVideoByReference() {
        assertEquals("text_to_video", AgentGenerationServiceImpl.classifyVideoGenType(null, null));
        assertEquals("ref_to_video", AgentGenerationServiceImpl.classifyVideoGenType(List.of("a"), null));
        assertEquals("ref_to_video", AgentGenerationServiceImpl.classifyVideoGenType(null, "/api/files/images/a.png"));
    }
}
