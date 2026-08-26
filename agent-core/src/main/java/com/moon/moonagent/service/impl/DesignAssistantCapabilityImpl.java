package com.moon.moonagent.service.impl;

import com.moon.moonagent.service.AgentConversationService;
import com.moon.moonagent.service.DesignAssistantCapability;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class DesignAssistantCapabilityImpl implements DesignAssistantCapability {

    private static final String SYSTEM_PROMPT = """
            你是资深视觉设计助理，服务于创意画布（Morrow）。你的职责：
            - 用简洁、专业的中文回答用户的版式/视觉/设计问题；
            - 需要生成图片时，引导用户使用"生成图片/重绘"能力；
            - 只对设计本身负责，不讨论无关话题。
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final AgentConversationService conversationService;

    @Override
    public String answer(String prompt, String conversationId, Consumer<String> onDelta) {
        if (conversationId != null) {
            conversationService.persistMessage(conversationId, "user", prompt);
        }
        ChatClient chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultOptions(OpenAiChatOptions.builder().model("mimo-v2.5").timeout(Duration.ofSeconds(120)))
                .build();
        StringBuffer full = new StringBuffer();
        chatClient.prompt().user(prompt).stream().content().toStream()
                .forEach(token -> {
                    String t = token.replace("\u0000", "");
                    if (!t.isEmpty()) {
                        full.append(t);
                        if (onDelta != null) {
                            onDelta.accept(t);
                        }
                    }
                });
        if (conversationId != null && !full.isEmpty()) {
            conversationService.persistMessage(conversationId, "assistant", full.toString());
        }
        return full.toString();
    }
}
