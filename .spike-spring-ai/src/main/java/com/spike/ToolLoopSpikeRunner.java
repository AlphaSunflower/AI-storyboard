package com.spike;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Phase 0 spike：@Tool 工具循环运行时验证（真实 LLM 网关）。
 *
 * 验证点：
 *  0.1 @Tool 注册 + ChatClient 自动工具调用循环（工具返回固定结果，LLM 连续调用后给出最终答案）
 *  0.2 工具异常传递（工具抛异常，自动模式行为）
 *  0.3 手动循环模式（ToolCallingManager + while loop）与自动模式对比
 *
 * 运行：--tool-loop=true 触发本 runner（默认 false 不干扰既有装配验证）。
 */
@Component
@ConditionalOnProperty(name = "tool-loop", havingValue = "true")
public class ToolLoopSpikeRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolLoopSpikeRunner.class);
    private final ChatClient.Builder builder;
    private final ChatModel chatModel;

    public ToolLoopSpikeRunner(ChatClient.Builder builder, ChatModel chatModel) {
        this.builder = builder;
        this.chatModel = chatModel;
    }

    @Service
    public static class WeatherTools {
        /** 工具调用计数（验证循环确实走了 N 次） */
        public static final AtomicInteger CALLS = new AtomicInteger();

        @Tool(description = "查询指定城市的天气")
        public String getWeather(@ToolParam(description = "城市名") String city) {
            CALLS.incrementAndGet();
            return city + "：晴，25 度";
        }

        @Tool(description = "故意抛异常的测试工具")
        public String boom(@ToolParam(description = "任意输入") String input) {
            throw new IllegalStateException("工具内部爆炸: " + input);
        }
    }

    private List<ToolCallback> toolCallbacks() {
        return List.of(MethodToolCallbackProvider.builder()
                .toolObjects(new WeatherTools())
                .build()
                .getToolCallbacks());
    }

    /** 响应是否包含工具调用请求 */
    private static boolean isToolCalling(ChatResponse resp) {
        return resp.getResult() != null && resp.getResult().getOutput() != null
                && resp.getResult().getOutput().getToolCalls() != null
                && !resp.getResult().getOutput().getToolCalls().isEmpty();
    }

    @Override
    public void run(String... args) {
        // ===== 0.1 自动模式：多轮工具调用循环 =====
        log.info("===== 0.1 自动模式：@Tool 注册 + 自动循环（真实网关） =====");
        WeatherTools.CALLS.set(0);
        ChatClient auto = builder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gemini-3-flash-preview")
                        .timeout(Duration.ofSeconds(120)))
                .build();
        String autoAnswer = auto.prompt()
                .user("请分别查询北京、上海两个城市的天气，然后告诉我哪个更热。")
                .tools(new WeatherTools())
                .call()
                .content();
        log.info("自动模式工具调用次数: {}", WeatherTools.CALLS.get());
        log.info("自动模式最终回答: {}", autoAnswer);

        // ===== 0.2 工具异常传递 =====
        log.info("===== 0.2 工具异常传递 =====");
        try {
            String boomAnswer = auto.prompt()
                    .user("请调用 boom 工具，输入'测试'。")
                    .tools(new WeatherTools())
                    .call()
                    .content();
            log.info("异常场景回答（LLM 可能自行消化错误）: {}", boomAnswer);
        } catch (Exception e) {
            log.info("异常场景：向上抛出 {}: {}", e.getClass().getName(), e.getMessage());
        }

        // ===== 0.3 手动循环模式（ToolCallingManager + while loop）=====
        log.info("===== 0.3 手动循环：ToolCallingManager + while loop =====");
        WeatherTools.CALLS.set(0);
        ToolCallingManager manager = DefaultToolCallingManager.builder().build();
        List<ToolCallback> callbacks = toolCallbacks();
        log.info("手动模式解析到工具: {}", callbacks.stream()
                .map(c -> c.getToolDefinition().name()).toList());

        // 标准手动循环：call → 若有工具调用则执行工具 → 拼接历史再 call，直到无工具调用
        // 注意：① 每次 call 的 options 都必须带 toolCallbacks（LLM 才能看到工具）
        //       ② 必须自己维护 messages 列表：初始含 user 消息，循环里 append conversationHistory()
        //          —— conversationHistory() 不含原始 user 消息，直接用它发第二次请求会导致
        //             请求以 assistant 开头，上游 400
        int steps = 0;
        var messages = new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();
        messages.add(new org.springframework.ai.chat.messages.UserMessage(
                "请分别查询北京、上海两个城市的天气，然后告诉我哪个更热。你必须调用 getWeather 工具获取数据，不能直接编造。"));
        ChatResponse resp = chatModel.call(new Prompt(messages,
                OpenAiChatOptions.builder()
                        .model("gemini-3-flash-preview")
                        .timeout(Duration.ofSeconds(120))
                        .toolCallbacks(callbacks)
                        .build()));
        // 模型偶尔会跳过工具直接回答（随机性）→ 重试提醒，最多 2 次
        for (int retry = 0; retry < 2 && !isToolCalling(resp); retry++) {
            log.info("手动模式：模型未调用工具，第 {} 次提醒重试", retry + 1);
            messages.add(new org.springframework.ai.chat.messages.UserMessage(
                    "你还没有调用工具。请先调用 getWeather 查询北京和上海的天气，再回答。"));
            resp = chatModel.call(new Prompt(messages,
                    OpenAiChatOptions.builder()
                            .model("gemini-3-flash-preview")
                            .timeout(Duration.ofSeconds(120))
                            .toolCallbacks(callbacks)
                            .build()));
        }
        log.info("手动模式：初始响应 results={} isToolCalling={}",
                resp.getResults().size(), isToolCalling(resp));
        if (!isToolCalling(resp)) {
            log.info("手动模式：重试后仍无工具调用，模型直接回答: {}",
                    resp.getResult().getOutput().getText());
        }
        for (var g : resp.getResults()) {
            var out = g.getOutput();
            log.info("手动模式：generation output.text={} toolCalls={} metadata={}",
                    out.getText() == null ? null : out.getText().substring(0, Math.min(40, out.getText().length())),
                    out.getToolCalls() == null ? null : out.getToolCalls().size(),
                    g.getMetadata());
        }
        // 手动复现 executeToolCalls 的 filter（字节码级一致）
        boolean filterHit = resp.getResults().stream()
                .anyMatch(g -> !org.springframework.util.CollectionUtils.isEmpty(g.getOutput().getToolCalls()));
        var firstHit = resp.getResults().stream()
                .filter(g -> !org.springframework.util.CollectionUtils.isEmpty(g.getOutput().getToolCalls()))
                .findFirst();
        log.info("手动模式：复现 filter 命中={} findFirst={}", filterHit, firstHit.isPresent());
        while (steps < 5 && isToolCalling(resp)) {
            ToolExecutionResult result = manager.executeToolCalls(
                    new Prompt(List.of(), DefaultToolCallingChatOptions.builder()
                            .toolCallbacks(callbacks).build()),
                    resp);
            if (result.returnDirect()) {
                break;
            }
            steps++;
            messages.addAll(result.conversationHistory());
            resp = chatModel.call(new Prompt(messages,
                    OpenAiChatOptions.builder()
                            .model("gemini-3-flash-preview")
                            .timeout(Duration.ofSeconds(120))
                            .toolCallbacks(callbacks)
                            .build()));
        }
        log.info("手动模式循环步数: {}", steps);
        log.info("手动模式工具调用次数: {}", WeatherTools.CALLS.get());
        log.info("手动模式最终回答: {}", resp.getResult().getOutput().getText());
        log.info("[SPIKE-DONE] tool loop spike finished");
    }
}
