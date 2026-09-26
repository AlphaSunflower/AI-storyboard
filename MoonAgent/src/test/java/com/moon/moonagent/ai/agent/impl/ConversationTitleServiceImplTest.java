package com.moon.moonagent.ai.agent.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.config.PromptConfig;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentConversationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationTitleServiceImplTest {

    @BeforeAll
    static void initMyBatisMetadata() {
        if (TableInfoHelper.getTableInfo(AgentConversation.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    AgentConversation.class);
        }
    }

    @Test
    void fallbackTitleUsesFirstSentenceAndRemovesMarkdown() {
        assertThat(ConversationTitleServiceImpl.fallbackTitle(
                "![封面](https://example.com/a.png) **赛博猫咪**。第二句不进入标题"))
                .isEqualTo("赛博猫咪");
    }

    @Test
    void fallbackTitleTruncatesLongUnpunctuatedMessage() {
        assertThat(ConversationTitleServiceImpl.fallbackTitle(
                "帮我制作一个非常非常长的赛博朋克风格猫咪冒险短片并自动生成全部镜头"))
                .isEqualTo("帮我制作一个非常非常长的赛博朋克风格猫咪冒险短片");
    }

    @Test
    void configuredTitleModelIsUsedWithNoThinkingMode() {
        AgentConversationMapper mapper = mock(AgentConversationMapper.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        AgentAiConfigProperties config = new AgentAiConfigProperties();
        config.setTitleModel("title-model");
        ConversationTitleServiceImpl service = service(mapper, client, spec, call, config);
        when(call.content()).thenReturn("赛博猫咪短片");
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.renameOnFirstMessage("conversation-1", "帮我做一个赛博朋克猫咪短片");

        ArgumentCaptor<OpenAiChatOptions.Builder> options =
                ArgumentCaptor.forClass(OpenAiChatOptions.Builder.class);
        verify(spec, times(1)).options(options.capture());
        OpenAiChatOptions built = options.getValue().build();
        assertThat(built.getModel()).isEqualTo("title-model");
        assertThat(built.getMaxTokens()).isEqualTo(256);
        assertThat(built.getExtraBody()).containsEntry("thinking_level", "minimal");
    }

    @Test
    void fallsBackToFirstSentenceWhenModelReturnsEmptyContent() {
        AgentConversationMapper mapper = mock(AgentConversationMapper.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        AgentAiConfigProperties config = new AgentAiConfigProperties();
        ConversationTitleServiceImpl service = service(mapper, client, spec, call, config);
        when(call.content()).thenReturn("");
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.renameOnFirstMessage("conversation-1", "帮我做一个赛博朋克猫咪短片");

        verify(mapper, times(1)).update(isNull(), any());
    }

    @Test
    void appliesGeneratedTitleAfterFallback() {
        AgentConversationMapper mapper = mock(AgentConversationMapper.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        AgentAiConfigProperties config = new AgentAiConfigProperties();
        ConversationTitleServiceImpl service = service(mapper, client, spec, call, config);
        when(call.content()).thenReturn("霓虹猫影");
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.renameOnFirstMessage("conversation-1", "帮我做一个赛博朋克猫咪短片");

        verify(mapper, times(2)).update(isNull(), any());
    }

    private static ConversationTitleServiceImpl service(
            AgentConversationMapper mapper,
            ChatClient client,
            ChatClient.ChatClientRequestSpec spec,
            ChatClient.CallResponseSpec call,
            AgentAiConfigProperties config) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        GatewayModelService gateway = mock(GatewayModelService.class);
        PromptConfig prompts = mock(PromptConfig.class);
        when(builder.defaultOptions(any(OpenAiChatOptions.Builder.class))).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.options(any(OpenAiChatOptions.Builder.class))).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(prompts.get("services/conversation-title")).thenReturn("生成标题");
        return new ConversationTitleServiceImpl(mapper, builder, gateway, config, prompts);
    }
}
