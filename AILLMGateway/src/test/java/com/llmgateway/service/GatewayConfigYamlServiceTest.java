package com.llmgateway.service;

import com.llmgateway.mapper.ChannelKeyMapper;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayConfigYamlServiceTest {

    @Mock private ChannelMapper channelMapper;
    @Mock private ChannelKeyMapper channelKeyMapper;
    @Mock private ModelRouteMapper routeMapper;
    @Mock private ModelParamsMapper modelParamsMapper;
    @Mock private ChannelService channelService;
    @Mock private ChannelKeyService channelKeyService;
    @Mock private ModelParamsService modelParamsService;
    @Mock private ModelRouteService routeService;
    @Mock private ModelFallbackService fallbackService;
    @InjectMocks private GatewayConfigYamlService service;

    private static final String VALID = """
            version: 1
            channels:
              - name: main
                type: openai_compatible
                base_url: https://api.example.com
                enabled: true
                priority: 10
                weight: 2
            models:
              - name: public-model
                type: image
                schema:
                  request:
                    - key: size
                      type: enum
                      options: ["1K", "2K"]
                      default: "2K"
                params:
                  context_window: 100000
                routes:
                  - channel: main
                    upstream_model: vendor-model
                    enabled: true
                    priority: 0
                    weight: 3
                    param_overrides:
                      delete: ["watermark"]
                fallbacks: [backup-model]
            """;

    @Test
    void validConfigPasses() {
        when(channelMapper.selectList(any())).thenReturn(List.of());
        GatewayConfigYamlService.ValidationResult result = service.validate(VALID);
        assertTrue(result.valid(), () -> String.join("; ", result.errors()));
    }

    @Test
    void invalidSchemaDefaultIsReported() {
        when(channelMapper.selectList(any())).thenReturn(List.of());
        String yaml = VALID.replace("default: \"2K\"", "default: \"4K\"");
        GatewayConfigYamlService.ValidationResult result = service.validate(yaml);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("models[0].schema")));
    }

    @Test
    void unknownChannelReferenceIsReported() {
        when(channelMapper.selectList(any())).thenReturn(List.of());
        String yaml = VALID.replace("channel: main", "channel: missing");
        GatewayConfigYamlService.ValidationResult result = service.validate(yaml);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("不存在的渠道")));
    }

    @Test
    void yamlSyntaxErrorIsReported() {
        GatewayConfigYamlService.ValidationResult result = service.validate("channels: [oops");
        assertFalse(result.valid());
        assertTrue(result.errors().get(0).contains("YAML"));
    }

    @Test
    void invalidParamOverridesIsReported() {
        when(channelMapper.selectList(any())).thenReturn(List.of());
        String yaml = VALID.replace("delete: [\"watermark\"]", "delete: \"watermark\"");
        GatewayConfigYamlService.ValidationResult result = service.validate(yaml);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("param_overrides")));
    }
}
