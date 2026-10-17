package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 「默认模型」动态包装器：agent 只构建一次并持有这一个 {@link Model} 实例，
 * 真实模型在每次调用时按 {@link TurnModelPin} 的当前值解析。
 *
 * <p>因此切换网关默认模型<b>不需要重建 AgentScope agent、也不需要重启 MoonAgent</b>：
 * 生效时机由 pin 保证 —— 在途 turn 内钉住不变，只在智能体闲置时于下一轮开始时替换。
 *
 * <p>为什么这样可行（对 AgentScope 2.0.4 源码核实）：
 * <ul>
 *   <li>{@code Model} 只有 3 个抽象方法（{@code stream} / {@code getModelName} /
 *       {@code getContextWindowSize}），可实现；</li>
 *   <li>请求用的模型名取自 {@code OpenAIChatModel.stream} 内部的
 *       {@code effectiveOptions.getModelName()}，而 {@code Model.getModelName()} 只被
 *       ReasoningEvent / SummaryEvent / Trace 用于日志与遥测，不参与请求构建；</li>
 *   <li>core / harness 内没有任何 {@code instanceof OpenAIChatModel} 之类的具体类型判断。</li>
 * </ul>
 *
 * <p>同名模型复用同一个 delegate（工厂只调用一次），避免每次模型调用都重建 HttpClient 配置。
 * {@code getContextWindowSize()} 转发当前 delegate，因此压缩阈值跟随实际生效的模型。
 */
@RequiredArgsConstructor
public class GatewayDefaultModel implements Model {

    private final TurnModelPin pin;

    /** 按模型名构建真实模型（复用 {@link AgentScopeModelProvider} 的构建逻辑：apiKey/baseUrl/stream/窗口同一套） */
    private final Function<String, Model> factory;

    private final ConcurrentHashMap<String, Model> delegates = new ConcurrentHashMap<>();

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return delegate().stream(messages, tools, options);
    }

    @Override
    public String getModelName() {
        return pin.currentName();
    }

    @Override
    public int getContextWindowSize() {
        return delegate().getContextWindowSize();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate().supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate().supportsNativeStructuredOutputWithTools();
    }

    /** 当前生效模型对应的真实实现；模型名未变时复用缓存实例 */
    private Model delegate() {
        String name = pin.currentName();
        return delegates.computeIfAbsent(name, factory);
    }
}
