package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.creative.CreativeModelRoles;
import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClientProperties;
import com.moon.moonagent.ai.agent.skill.ToolAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 能力上报块必须把缺席的能力显式声明为不可用，否则模型会提议一个运行时并不存在的动作。
 *
 * <p>本测试不依赖 Mockito——该环境（JDK 25）下 Mockito inline mock maker 无法初始化，
 * 因此使用手写替身，并复用生产接口 {@link ToolAvailability} 与 {@link CreativeModelRoles}。
 */
class TurnCapabilityReporterTest {

    private static final class StubTools implements ToolAvailability {
        private final Set<String> registered;

        StubTools(String... names) {
            this.registered = Set.of(names);
        }

        @Override
        public boolean hasTool(String name) {
            return registered.contains(name);
        }
    }

    /** 未配置任何角色模型，等价于「全部回退网关默认」。 */
    private static final class StubModelRoles implements CreativeModelRoles {
        @Override
        public String resolve(String role) {
            return "";
        }

        @Override
        public String resolveOrNull(String role) {
            return null;
        }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    private static MoonSandboxClient sandbox(boolean enabled) {
        MoonSandboxClientProperties properties = new MoonSandboxClientProperties();
        properties.setEnabled(enabled);
        return new MoonSandboxClient(properties);
    }

    private static TurnCapabilityReporter reporter(ToolAvailability tools,
                                                   boolean sandboxEnabled,
                                                   Object webTools,
                                                   Object documentTools) {
        return new TurnCapabilityReporter(tools, new AgentScopeProperties(),
                new StubModelRoles(), provider(sandbox(sandboxEnabled)),
                providerOfWeb(webTools), providerOfDocument(documentTools));
    }

    private static TurnCapabilityReporter reporterWithWeb(Object webTools) {
        return new TurnCapabilityReporter(new StubTools(), new AgentScopeProperties(),
                new StubModelRoles(), provider(sandbox(false)),
                providerOfWeb(webTools), providerOfDocument(null));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentScopeWebTools> providerOfWeb(Object value) {
        return (ObjectProvider<AgentScopeWebTools>) (ObjectProvider<?>) provider(value);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentScopeDocumentTools> providerOfDocument(Object value) {
        return (ObjectProvider<AgentScopeDocumentTools>) (ObjectProvider<?>) provider(value);
    }

    private static String report(String[] registeredTools, boolean sandboxEnabled) {
        return reporter(new StubTools(registeredTools), sandboxEnabled, null, null)
                .capabilityReport();
    }

    @Test
    void absentCapabilitiesAreDeclaredUnavailable() {
        String report = report(new String[0], false);

        assertThat(report).contains("此轮不可用的能力");
        assertThat(report).contains("画布设计与执行");
        assertThat(report).contains("图片生成与参考图编辑");
        assertThat(report).contains("视频生成");
        assertThat(report).contains("文档解析");
        assertThat(report).contains("网页检索");
        assertThat(report).doesNotContain("此轮可用的能力");
    }

    @Test
    void connectedCanvasMediaAndSandboxAreDeclaredAvailable() {
        String report = report(new String[]{
                "canvas_graph_get_state", "canvas_graph_layout_graph",
                AgentScopeImageTools.GENERATE_IMAGE,
                AgentScopeVideoTools.GENERATE_VIDEO}, true);

        assertThat(report).contains("此轮可用的能力");
        assertThat(report).contains("画布设计与执行");
        assertThat(report).contains("图片生成与参考图编辑");
        assertThat(report).contains("视频生成");
        assertThat(report).contains("Python 沙箱脚本执行");
        // 画布/媒体/沙箱可用，但网页与文档工具未注入，因此两个清单会同时出现。
        assertThat(report).contains("此轮不可用的能力");
        assertThat(report).contains("网页检索");
    }

    @Test
    void canvasIsAvailableWhenOnlyTheMcpMarkersAreRegistered() {
        // 生产事实：canvas_create_run 由 AgentScopeToolkitFactory 无条件注册进 Toolkit 的
        // skill 工具组，不会出现在 ToolAvailability（SkillExpander）里。把它当判据会让画布
        // 恒判不可用，模型据此拒绝使用画布——能力判定只能以 MCP 侧标记为准。
        String report = report(new String[]{
                "canvas_graph_get_state", "canvas_graph_layout_graph"}, false);

        assertThat(report).contains("此轮可用的能力");
        assertThat(report).contains("画布设计与执行");
    }

    @Test
    void partialCanvasRegistrationCountsAsUnavailable() {
        // 只注册了部分画布工具（例如 MCP 半途断开）时必须判为整体不可用。
        String report = report(new String[]{"canvas_graph_get_state"}, false);

        assertThat(report).contains("此轮不可用的能力");
        assertThat(report).contains("画布设计与执行");
    }

    @Test
    void subagentsComeFromTheRegistryNotFromThePrompt() {
        String report = report(new String[0], false);

        assertThat(report).contains("可用子智能体");
        assertThat(report).contains("creative-planner");
        assertThat(report).contains("visual-critic");
        assertThat(report).contains("continuity-editor");
    }

    @Test
    void webSearchWithoutRequiredCredentialsIsReportedUnavailable() {
        // 事故形态：bean 在容器里（开关是 true），但当前后端缺必需配置，tools() 返回空列表、
        // 一个工具都没注册。只看 bean 存在会把「网页检索」报成可用，模型随后承诺做不到的事。
        // 这里用 deepseek 后端且不给 key 来构造「缺必需配置」。
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getWebSearch().setProvider("deepseek");
        AgentScopeWebTools web = new AgentScopeWebTools(properties);

        String report = reporterWithWeb(web).capabilityReport();

        assertThat(report).contains("此轮不可用的能力").contains("网页检索");
        assertThat(report).doesNotContain("此轮可用的能力");
    }

    @Test
    void webSearchWithRequiredConfigIsReportedAvailable() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getWebSearch().setProvider("deepseek");
        properties.getWebSearch().setApiKey("sk-test");

        String report = reporterWithWeb(new AgentScopeWebTools(properties)).capabilityReport();

        assertThat(report).contains("此轮可用的能力").contains("网页检索");
        // 其它能力（画布/图片/视频/沙箱）没注册，仍应出现在不可用清单里；
        // 这里只要求「网页检索」不再被列进不可用清单。
        assertThat(report).contains("此轮不可用的能力：画布设计与执行");
    }

    @Test
    void selfHostedSearchIsAvailableWithoutAnyThirdPartyKey() {
        // 默认后端是自托管 SearXNG：没有 key 也必须报可用，否则自托管方案会被误判为不可用。
        String report = reporterWithWeb(new AgentScopeWebTools(new AgentScopeProperties()))
                .capabilityReport();

        assertThat(report).contains("此轮可用的能力").contains("网页检索");
    }
}
