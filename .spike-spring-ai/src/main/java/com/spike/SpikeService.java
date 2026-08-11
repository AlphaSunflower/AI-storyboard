package com.spike;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Spring AI 2.0.0 spike：验证 ChatClient / @Tool / 结构化输出三个核心 API 形状能否在
 * Spring Boot 4.0.0 + spring-ai-bom 2.0.0 下编译。
 * 不运行，只编译 —— 版本兼容性验证。
 */
@Service
public class SpikeService {

    private final ChatClient chatClient;

    public SpikeService(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("你是一个分镜师，回答简洁。")
                .build();
    }

    /** 普通对话 */
    public String chat(String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }

    /** 流式对话 */
    public String stream(String question) {
        return chatClient.prompt()
                .user(question)
                .stream()
                .content()
                .collectList()
                .block()
                .stream()
                .reduce("", String::concat);
    }

    /** 结构化输出（意图识别形状） */
    public record IntentResult(String type, String reason) {}

    public IntentResult intent(String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .call()
                .entity(IntentResult.class);
    }

    /** 工具调用：@Tool 注解方法 */
    @Component
    public static class StoryboardTools {

        @Tool(description = "根据剧本生成分镜列表")
        public Map<String, Object> writeScript(
                @ToolParam(description = "项目 ID") String projectId,
                @ToolParam(description = "剧本内容") String script) {
            return Map.of("projectId", projectId, "sceneCount", 3);
        }
    }

    /** 带工具的对话（工具注册形状） */
    public String chatWithTools(String question, StoryboardTools tools) {
        return chatClient.prompt()
                .user(question)
                .tools(tools)
                .call()
                .content();
    }
}
