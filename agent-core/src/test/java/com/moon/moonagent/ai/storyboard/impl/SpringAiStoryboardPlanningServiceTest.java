package com.moon.moonagent.ai.storyboard.impl;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.storyboard.StoryboardPlanResponseMapper;
import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.config.PromptConfig;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiStoryboardPlanningServiceTest {

    @Test
    void usesStructuredResponseFormatAndReturnsMappedShots() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        GatewayModelService gateway = mock(GatewayModelService.class);
        PromptConfig promptConfig = mock(PromptConfig.class);

        when(builder.defaultOptions(any())).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(validResponse());
        when(gateway.getDefaultTextModel()).thenReturn("text-model");
        when(promptConfig.get(anyString())).thenReturn("共享分镜提示词");

        SpringAiStoryboardPlanningService service = new SpringAiStoryboardPlanningService(
                builder, gateway, new AgentAiConfigProperties(), new StoryboardPlanResponseMapper(), promptConfig);

        List<StoryboardShot> shots = service.plan("A short script");

        assertEquals(1, shots.size());
        assertEquals("shot-1", shots.getFirst().shotId());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(prompt.capture());
        assertTrue(prompt.getValue().contains("A short script"));
        assertTrue(prompt.getValue().contains("JSON Schema"));
    }

    @Test
    void invalidStructuredModelOutputFailsInsteadOfFallingBackToProseParsing() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        GatewayModelService gateway = mock(GatewayModelService.class);
        PromptConfig promptConfig = mock(PromptConfig.class);

        when(builder.defaultOptions(any())).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("I made a storyboard, but not JSON.");
        when(gateway.getDefaultTextModel()).thenReturn("text-model");
        when(promptConfig.get(anyString())).thenReturn("共享分镜提示词");

        SpringAiStoryboardPlanningService service = new SpringAiStoryboardPlanningService(
                builder, gateway, new AgentAiConfigProperties(), new StoryboardPlanResponseMapper(), promptConfig);

        BusinessException error = assertThrows(BusinessException.class, () -> service.plan("A short script"));

        assertEquals(50201, error.getCode());
        assertTrue(error.getMessage().contains("invalid JSON"));
    }

    @Test
    void understandsReferenceImagesBeforeCombiningThemWithTheScript() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient visionClient = mock(ChatClient.class);
        ChatClient textClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec visionRequest = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec textRequest = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec visionCall = mock(ChatClient.CallResponseSpec.class);
        ChatClient.CallResponseSpec textCall = mock(ChatClient.CallResponseSpec.class);
        GatewayModelService gateway = mock(GatewayModelService.class);
        PromptConfig promptConfig = mock(PromptConfig.class);

        when(builder.defaultOptions(any())).thenReturn(builder);
        when(builder.build()).thenReturn(visionClient, textClient);
        when(visionClient.prompt()).thenReturn(visionRequest);
        when(visionRequest.system(anyString())).thenReturn(visionRequest);
        when(visionRequest.messages(any(UserMessage.class))).thenReturn(visionRequest);
        when(visionRequest.call()).thenReturn(visionCall);
        when(visionCall.content()).thenReturn("{\"summary\":\"角色的红围巾必须保持一致\"}");
        when(textClient.prompt()).thenReturn(textRequest);
        when(textRequest.system(anyString())).thenReturn(textRequest);
        when(textRequest.user(anyString())).thenReturn(textRequest);
        when(textRequest.call()).thenReturn(textCall);
        when(textCall.content()).thenReturn(validResponse());
        when(gateway.getDefaultVisionModel()).thenReturn("vision-model");
        when(gateway.getDefaultTextModel()).thenReturn("text-model");
        when(promptConfig.get(anyString())).thenReturn("共享中文提示词");

        SpringAiStoryboardPlanningService service = new SpringAiStoryboardPlanningService(
                builder, gateway, new AgentAiConfigProperties(), new StoryboardPlanResponseMapper(), promptConfig);

        service.plan("A short script", List.of("data:image/png;base64,AAAA"));

        ArgumentCaptor<String> planningPrompt = ArgumentCaptor.forClass(String.class);
        verify(textRequest).user(planningPrompt.capture());
        assertTrue(planningPrompt.getValue().contains("角色的红围巾必须保持一致"));
        verify(visionRequest).messages(any(UserMessage.class));
    }

    private static String validResponse() {
        return """
                {
                  "shots": [
                    {
                      "shotId": "shot-1",
                      "segmentId": "segment-1",
                      "shotOrdinal": 1,
                      "sceneDescription": "A door opens",
                      "shotType": "wide",
                      "cameraMovement": "push in",
                      "durationSeconds": 3,
                      "imagePrompt": "door opening",
                      "videoPrompt": "camera pushes through the door",
                      "negativePrompt": "blur",
                      "referenceHints": []
                    }
                  ]
                }
                """;
    }
}
