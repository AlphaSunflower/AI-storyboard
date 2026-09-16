package com.moon.moonagent.ai.agent.specialist.impl;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OtherSpecialistModelSwitchTests {

    @Test
    void usesTheLatestGatewayTextModelOnEachRequest() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        GatewayModelService gateway = mock(GatewayModelService.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);

        when(gateway.getDefaultTextModel()).thenReturn("model-a", "model-b");
        when(builder.defaultOptions(any(OpenAiChatOptions.Builder.class))).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.messages(any(UserMessage.class))).thenReturn(spec);
        when(spec.options(any(OpenAiChatOptions.Builder.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(messageMapper.selectList(any())).thenReturn(List.of());

        AssistantMessage output = mock(AssistantMessage.class);
        when(output.getText()).thenReturn("ok");
        when(output.getMetadata()).thenReturn(Map.of());
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(output);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        when(callSpec.chatResponse()).thenReturn(response);

        OtherSpecialist specialist = new OtherSpecialist(
                builder,
                gateway,
                messageMapper,
                mock(FileStorageService.class),
                mock(AgentOrchestratorSupport.class));
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        SpecialistContext context = new SpecialistContext(
                conversation, "hello", List.of(), null, null, null,
                "", "", Map.of(), List.of(), "", "");

        specialist.execute(context);
        specialist.execute(context);

        ArgumentCaptor<OpenAiChatOptions.Builder> options =
                ArgumentCaptor.forClass(OpenAiChatOptions.Builder.class);
        verify(spec, org.mockito.Mockito.times(2)).options(options.capture());
        assertEquals(List.of("model-a", "model-b"),
                options.getAllValues().stream().map(optionBuilder -> optionBuilder.build().getModel()).toList());
    }
}
