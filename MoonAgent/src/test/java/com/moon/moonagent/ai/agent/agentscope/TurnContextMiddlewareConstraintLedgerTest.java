package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowService;
import com.moon.moonagent.ai.agent.creative.ConstraintExtractionService;
import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.ConstraintSeverity;
import com.moon.moonagent.ai.agent.creative.CreativeMode;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.ai.agent.document.AgentDocumentService;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentConversation;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 约束账本注入块：把机器判定结果原样交给模型，替代提示词里「自行区分 HARD 与 SOFT」的散文化要求。
 *
 * <p>本测试使用手写测试替身，不依赖 Mockito——该环境（JDK 25）下 Mockito inline mock maker 无法初始化。
 */
class TurnContextMiddlewareConstraintLedgerTest {

    /** 只覆写中间件会调用的两个方法。 */
    private static ConstraintExtractionService constraintsOf(ConstraintLedger ledger) {
        return prompt -> ledger;
    }

    private static ProjectCreativeContextService creativeContext() {
        return new ProjectCreativeContextService() {
            @Override
            public com.moon.moonagent.ai.agent.creative.ProjectCreativeContext get(
                    String userId, String projectId) {
                return null;
            }

            @Override
            public com.moon.moonagent.ai.agent.creative.ProjectCreativeContext patch(
                    String userId, String projectId,
                    com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService
                            .CreativeContextPatch patch) {
                return null;
            }

            @Override
            public ConstraintLedger prepareTurn(String userId, String projectId, String prompt) {
                return null;
            }

            @Override
            public String promptContext(String projectId, String prompt) {
                return "";
            }
        };
    }

    private static final class StubWorkflows extends AgentWorkflowService {
        StubWorkflows() {
            super(null, null, null);
        }

        @Override
        public String activeContext(String conversationId) {
            return "";
        }
    }

    /**
     * 文档服务只被调用于「有文档附件」分支；本轮无附件，返回空摘要列表即可。
     * 构造器需要非空 {@code DocumentProperties}（内部据此建缓存），其余依赖本轮不会被触达。
     */
    private static AgentDocumentService documents() {
        return new AgentDocumentService(null, null, null, null, null,
                new com.moon.moonagent.ai.agent.document.DocumentProperties()) {
            @Override
            public List<com.moon.moonagent.ai.agent.document.DocumentSummaryView> recentSummaries(
                    String conversationId, int requestedLimit) {
                return List.of();
            }
        };
    }

    private static String promptFor(ConstraintLedger ledger) {
        return promptFor(ledger, null);
    }

    private static String promptFor(ConstraintLedger ledger, TurnCapabilityReporter reporter) {
        TurnContextMiddleware middleware = new TurnContextMiddleware(
                new StubWorkflows(), creativeContext(), constraintsOf(ledger), documents(),
                reporter);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        OrchestrationRequest request =
                new OrchestrationRequest(conversation, "按这个画", List.of(), null);
        RuntimeContext context = RuntimeContext.builder()
                .put(AgentScopeRunContext.class, new AgentScopeRunContext(
                        conversation, request, null, new AgentScopeTurnState()))
                .build();
        return middleware.onSystemPrompt(null, context, "SYSTEM").block();
    }

    @Test
    void capabilityBlockIsInjectedWhenReporterIsWired() {
        TurnCapabilityReporter reporter = new TurnCapabilityReporter(
                name -> false, new AgentScopeProperties(), noModelRoles(),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
                emptyProvider());

        String prompt = promptFor(new ConstraintLedger(CreativeMode.BALANCED,
                List.of(), List.of(), List.of()), reporter);

        assertThat(prompt).contains("<capabilities>");
        assertThat(prompt).contains("</capabilities>");
        assertThat(prompt).contains("此轮不可用的能力");
    }

    private static com.moon.moonagent.ai.agent.creative.CreativeModelRoles noModelRoles() {
        return new com.moon.moonagent.ai.agent.creative.CreativeModelRoles() {
            @Override
            public String resolve(String role) {
                return "";
            }

            @Override
            public String resolveOrNull(String role) {
                return null;
            }
        };
    }

    /** 能力上报器在本测试里只走「bean 缺失」分支，因此只需支持 getIfAvailable。 */
    private static <T> org.springframework.beans.factory.ObjectProvider<T> emptyProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getObject() {
                return null;
            }

            @Override
            public T getObject(Object... args) {
                return null;
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
    }

    @Test
    void capabilityBlockIsAbsentWhenReporterIsUnwired() {
        String prompt = promptFor(new ConstraintLedger(CreativeMode.BALANCED,
                List.of(), List.of(), List.of()));

        assertThat(prompt).doesNotContain("<capabilities>");
        assertThat(prompt).contains("<constraint-ledger mode=\"BALANCED\">");
    }

    @Test
    void hardAndSoftConstraintsAreInjectedAsAnExplicitLedger() {
        ConstraintLedger ledger = new ConstraintLedger(CreativeMode.STRICT,
                List.of(new ConstraintLedger.ConstraintItem(
                        "c1", "主角必须是短发女性", ConstraintSeverity.HARD,
                        "user", "vision", "")),
                List.of(new ConstraintLedger.ConstraintItem(
                        "c2", "冷色调", ConstraintSeverity.SOFT, "user", "", "")),
                List.of());

        String prompt = promptFor(ledger);

        assertThat(prompt).contains("<constraint-ledger mode=\"STRICT\">");
        assertThat(prompt).contains("Hard (不可修改、不可替换、不可删除):");
        assertThat(prompt).contains("- 主角必须是短发女性");
        assertThat(prompt).contains("Soft (可调整，但需说明理由):");
        assertThat(prompt).contains("- 冷色调");
        assertThat(prompt).contains("</constraint-ledger>");
    }

    @Test
    void unverifiableConstraintsRequestClarification() {
        ConstraintLedger ledger = new ConstraintLedger(CreativeMode.BALANCED,
                List.of(), List.of(), List.of("品牌 Logo 是否必须与官方一致"));

        String prompt = promptFor(ledger);

        assertThat(prompt).contains("mode=\"CLARIFY\"");
        assertThat(prompt).contains("Needs clarification (生成前必须与用户确认):");
        assertThat(prompt).contains("- 品牌 Logo 是否必须与官方一致");
    }

    @Test
    void emptyLedgerStatesThatNoConstraintWasIdentified() {
        ConstraintLedger ledger = new ConstraintLedger(CreativeMode.BALANCED,
                List.of(), List.of(), List.of());

        String prompt = promptFor(ledger);

        assertThat(prompt).contains("<constraint-ledger mode=\"BALANCED\">");
        assertThat(prompt).contains("本轮没有识别到可验证的硬约束或软约束");
        assertThat(prompt).doesNotContain("mode=\"CLARIFY\"");
    }
}
