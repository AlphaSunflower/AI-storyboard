package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.creative.CreativeModelRouter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「默认模型切换」的生效时机回归：在途 turn 内钉住不变，只在智能体闲置时于下一轮捡起新默认。
 *
 * <p>不用 Mockito：网关模型服务用几行假实现即可，用例在本地任何环境都能真跑。
 */
class TurnModelPinTest {

    private final AtomicReference<String> gatewayDefault = new AtomicReference<>("model-a");
    private final AgentScopeProperties properties = new AgentScopeProperties();
    private final TurnModelPin pin = new TurnModelPin(
            new CreativeModelRouter(properties, gateway(gatewayDefault)), properties);

    /** 只实现网关默认文本模型相关的抽象方法，其余走接口默认实现 */
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
    void keeepsThePinnedModelWhileATurnIsInFlight() {
        assertThat(pin.beginTurn()).isTrue();
        assertThat(pin.currentName()).isEqualTo("model-a");

        // admin 在发送过程中切换默认模型：本轮必须继续用旧模型
        gatewayDefault.set("model-b");
        assertThat(pin.currentName())
                .as("在途 turn 内不得换模型")
                .isEqualTo("model-a");

        pin.endTurn();
    }

    @Test
    void picksUpTheNewDefaultOnlyWhenIdle() {
        pin.beginTurn();
        pin.endTurn();

        gatewayDefault.set("model-b");
        assertThat(pin.currentName())
                .as("没有新一轮就不该刷新")
                .isEqualTo("model-a");

        pin.beginTurn();
        assertThat(pin.currentName())
                .as("闲置后开始的新一轮应捡起新默认")
                .isEqualTo("model-b");
        pin.endTurn();
    }

    @Test
    void parallelTurnsDoNotSwitchMidFlight() {
        pin.beginTurn();                       // 会话 A 在跑
        gatewayDefault.set("model-b");
        pin.beginTurn();                       // 会话 B 开始：并非完全闲置

        assertThat(pin.currentName()).isEqualTo("model-a");
        pin.endTurn();                         // A 结束，B 仍在跑
        assertThat(pin.currentName()).isEqualTo("model-a");
        pin.endTurn();                         // 全部空闲

        pin.beginTurn();
        assertThat(pin.currentName()).isEqualTo("model-b");
        pin.endTurn();

        // 计数永远不会被压成负数
        pin.endTurn();
        assertThat(pin.beginTurn()).isTrue();
        pin.endTurn();
    }

    @Test
    void dynamicModeIsOffWhenAModelIsPinnedByConfigOrTheFlagIsDisabled() {
        properties.setModel("pinned-model");
        assertThat(pin.dynamicEnabled()).isFalse();
        assertThat(pin.beginTurn())
                .as("未启用时不参与在途计数，调用方也不会调 endTurn")
                .isFalse();

        properties.setModel("");
        properties.setDynamicDefaultModel(false);
        assertThat(pin.dynamicEnabled()).isFalse();
        assertThat(pin.beginTurn()).isFalse();

        properties.setDynamicDefaultModel(true);
        assertThat(pin.dynamicEnabled()).isTrue();
    }
}
