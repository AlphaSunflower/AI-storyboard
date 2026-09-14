package com.moon.moonagent.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 编排配置（独立前缀 {@code ai.agent}，与 ai.laozhang / ai.gateway 平级）。
 *
 * <p>编排层行为参数：意图低置信度阈值、澄清追问上限等。
 * 对应 application.yml 中 {@code ai.agent.*} 配置段。
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "ai.agent")
public class AgentAiConfigProperties {

    /** 意图识别低置信度阈值：低于该值不硬路由，触发澄清分支（默认 0.6） */
    private double intentThreshold = 0.6;

    /** 澄清追问最大轮数：连续追问达到该值后直接给默认方案让用户选（默认 2，第 3 次兜底） */
    private int maxClarifyRounds = 3;

    /** 分镜不满意调整最大轮数：连续「不满意→调整→重生成」达到该值后不再弹调整卡片，提示直接输入新需求（默认 3） */
    private int maxRegenerateRounds = 3;

    /**
     * 编排类 LLM 调用默认超时（秒）：剧本优化、方案设计、意图判定等。
     * 与分镜脚本生成 {@code ai.script-timeout} 分开，避免所有任务共用同一个超时上限。
     */
    private int planTimeoutSeconds = 600;

    /** 分镜参数推荐超时（秒）：这是可降级步骤，超时后直接使用模型默认参数。 */
    private int recommendTimeoutSeconds = 20;

    /** 素材绑定/分镜方案设计超时（秒）：慢模型优先等待完整优解，超时后才进入上层回退。 */
    private int materialTimeoutSeconds = 600;

    /** 分镜剧情骨架专用快模型：显式配置，禁止回退到网关默认强模型。 */
    private String skeletonModel = "mimo-v2.5-pro";

    /** 多智能体并行上限：同时运行的批次/绑定/审查 worker 数量。 */
    private int maxParallelAgents = 4;

    /** 轻量意图判定超时（秒）：例如取消意图判断，超时按不取消处理。 */
    private int intentTimeoutSeconds = 10;

    /**
     * SSE 异步连接最长存活时间（秒）：覆盖长剧本分批生成全过程。
     * 该值只控制 Spring MVC 异步请求生命周期，不替代 Spring AI 单次模型调用超时。
     */
    private int sseTimeoutSeconds = 1800;

    /**
     * 历史上下文总字符预算（软限制）：按最近消息优先保留；{@code <=0} 表示不限制。
     * 仅用于模型输入，不影响数据库中的完整消息。
     */
    private int historyContextMaxChars = 10_000;

    /**
     * 单条历史消息字符预算（软限制）：先截断单条消息，再受总预算约束；{@code <=0} 表示不限制。
     */
    private int historyMessageMaxChars = 2_000;

    /**
     * 自动审查链：链首意图（如 aisplit）写库成功后自动 handoff 给 intent-scene-review 出审查卡。
     * 空=关闭（默认，行为与现状一致）；示例 {@code [aisplit]}。
     */
    private java.util.List<String> autoReviewChains = java.util.List.of();
}
