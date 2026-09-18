package com.moon.xiaoyuecanvas.controller;

import com.moon.moonagent.ai.GatewayModelService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayModelsControllerTest {

    @Test
    void returnsFallbackDefaultWhenGatewayListIsEmpty() {
        GatewayModelService service = mock(GatewayModelService.class);
        when(service.fetchModels("image")).thenReturn(List.of());
        when(service.getDefaultImageModel()).thenReturn("gpt-image-2");

        List<Map<String, String>> models = new GatewayModelsController(service).models("image");

        assertEquals(1, models.size());
        assertEquals("gpt-image-2", models.getFirst().get("value"));
        assertEquals("true", models.getFirst().get("default"));
    }

    @Test
    void keepsGatewayModelsWhenAvailable() {
        GatewayModelService service = mock(GatewayModelService.class);
        when(service.fetchModels("image")).thenReturn(List.of(Map.of(
                "value", "model-a",
                "label", "Model A"
        )));

        List<Map<String, String>> models = new GatewayModelsController(service).models("image");

        assertEquals(List.of(Map.of("value", "model-a", "label", "Model A")), models);
    }
}
