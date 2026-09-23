package com.moon.moonagent.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolsMcpSchemaTest {

    @Test
    void optionalMediaParamsAreNotRequired() throws Exception {
        Method refineImage = AgentTools.class.getMethod("refineImage",
                String.class, String.class, List.class, String.class,
                String.class, String.class, String.class);
        for (int i = 2; i < refineImage.getParameterCount(); i++) {
            assertThat(refineImage.getParameters()[i].getAnnotation(McpToolParam.class).required())
                    .as(refineImage.getParameters()[i].getName())
                    .isFalse();
        }

        Method generateVideo = AgentTools.class.getMethod("generateVideo",
                String.class, String.class, String.class, String.class,
                List.class, String.class, String.class);
        for (int i = 2; i < generateVideo.getParameterCount(); i++) {
            assertThat(generateVideo.getParameters()[i].getAnnotation(McpToolParam.class).required())
                    .as(generateVideo.getParameters()[i].getName())
                    .isFalse();
        }
    }
}
