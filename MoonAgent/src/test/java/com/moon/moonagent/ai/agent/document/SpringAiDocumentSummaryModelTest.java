package com.moon.moonagent.ai.agent.document;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiDocumentSummaryModelTest {

    @Test
    void delegatesChunkAndMergePromptsToChatClient() {
        DocumentProperties properties = new DocumentProperties();
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.build().prompt().options(any()).system(any(String.class)).user(any(String.class))
                .call().content()).thenReturn("模型摘要");
        SpringAiDocumentSummaryModel model = new SpringAiDocumentSummaryModel(builder, properties);

        assertThat(model.summarizeChunk("# 标题\n正文")).isEqualTo("模型摘要");
        assertThat(model.merge(List.of("A", "B"))).isEqualTo("模型摘要");
    }
}
