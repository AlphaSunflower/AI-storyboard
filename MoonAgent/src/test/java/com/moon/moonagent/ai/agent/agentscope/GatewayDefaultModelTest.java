package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.creative.CreativeModelRouter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态默认模型包装器：agent 只持有这一个 Model 实例，真实模型在每次调用时按 pin 解析。
 * 这里验证「切默认后 stream 打到新 delegate、且同名复用同一实例」。
 */
class GatewayDefaultModelTest {

    private final AtomicReference<String> gatewayDefault = new AtomicReference<>("model-a");
    private final AgentScopeProperties properties = new AgentScopeProperties();
    private final TurnModelPin pin = new TurnModelPin(
            new CreativeModelRouter(properties, gateway(gatewayDefault)), properties);
    private final List<String> builtNames = new ArrayList<>();
    private final List<String> streamed = new ArrayList<>();

    private GatewayDefaultModel dynamicModel() {
        return new GatewayDefaultModel(pin, name -> {
            builtNames.add(name);
            return fakeModel(name, "model-b".equals(name) ? 200_000 : 100_000);
        });
    }

    private Model fakeModel(String name, int contextWindow) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                streamed.add(name);
                return Flux.empty();
            }

            @Override
            public String getModelName() {
                return name;
            }

            @Override
            public int getContextWindowSize() {
                return contextWindow;
            }
        };
    }

    private static GatewayModelService gateway(AtomicReference<String> current) {
        return new GatewayModelService() {
            @Override
            public List<Map<String, String>> fetchModels(String type) {
                return List.of();
            }

            @Override
            public String getDefaultImageModel() {
                return current.get();
            }

            @Override
            public String getDefaultVideoModel() {
                return current.get();
            }

            @Override
            public String getDefaultVisionModel() {
                return current.get();
            }

            @Override
            public String getDefaultTextModel() {
                return current.get();
            }
        };
    }

    @Test
    void streamsToThePinnedModelAndSwitchesAfterAnIdleTurn() {
        Model model = dynamicModel();

        pin.beginTurn();
        model.stream(List.of(), null, null).blockLast();
        assertThat(model.getModelName()).isEqualTo("model-a");
        assertThat(model.getContextWindowSize()).as("窗口跟随当前模型").isEqualTo(100_000);

        // 在途切换默认：仍然打旧模型
        gatewayDefault.set("model-b");
        model.stream(List.of(), null, null).blockLast();
        assertThat(streamed).containsExactly("model-a", "model-a");

        pin.endTurn();
        pin.beginTurn();
        model.stream(List.of(), null, null).blockLast();
        assertThat(streamed).containsExactly("model-a", "model-a", "model-b");
        assertThat(model.getModelName()).isEqualTo("model-b");
        assertThat(model.getContextWindowSize()).isEqualTo(200_000);

        pin.endTurn();
        pin.beginTurn();
        model.stream(List.of(), null, null).blockLast();
        pin.endTurn();

        assertThat(builtNames)
                .as("同名模型只构建一次，不重复创建客户端配置")
                .containsExactly("model-a", "model-b");
    }

    @Test
    void exposesTheDelegateCapabilities() {
        Model model = dynamicModel();

        assertThat(model.supportsNativeStructuredOutput()).isFalse();
        assertThat(model.supportsNativeStructuredOutputWithTools()).isFalse();
    }
}
