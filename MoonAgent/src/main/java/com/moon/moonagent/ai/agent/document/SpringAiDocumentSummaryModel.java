package com.moon.moonagent.ai.agent.document;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class SpringAiDocumentSummaryModel implements DocumentSummaryModel {

    private static final String CHUNK_SYSTEM = """
            你是 Moon 智能体的文档分析助手。请忠实概括给定文档片段，
            保留对后续创作有用的角色、情节、数据、表格、时间、约束和待办信息。
            只输出中文摘要，不要虚构原文没有的内容。
            """;

    private static final String MERGE_SYSTEM = """
            你是 Moon 智能体的文档分析助手。请合并多个片段摘要，
            去掉重复内容，保留关键事实、数据、结构、角色关系和约束。
            只输出一段结构清晰的中文总摘要。
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final DocumentProperties properties;
    private volatile ChatClient chatClient;

    public SpringAiDocumentSummaryModel(ChatClient.Builder chatClientBuilder,
                                       DocumentProperties properties) {
        this.chatClientBuilder = chatClientBuilder;
        this.properties = properties;
    }

    @Override
    public String summarizeChunk(String markdown) {
        return call(CHUNK_SYSTEM, markdown);
    }

    @Override
    public String merge(List<String> summaries) {
        return call(MERGE_SYSTEM, String.join("\n\n---\n\n", summaries));
    }

    private String call(String systemPrompt, String userPrompt) {
        return client().prompt()
                .options(OpenAiChatOptions.builder()
                        .model(properties.getSummaryModel())
                        .timeout(Duration.ofSeconds(Math.max(
                                1, properties.getSummaryTimeoutSeconds())))
                        .maxTokens(800)
                        .extraBody(Map.of("thinking_level", "minimal")))
                .system(systemPrompt)
                .user(userPrompt == null ? "" : userPrompt)
                .call()
                .content();
    }

    private ChatClient client() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    chatClient = chatClientBuilder.build();
                }
            }
        }
        return chatClient;
    }
}
