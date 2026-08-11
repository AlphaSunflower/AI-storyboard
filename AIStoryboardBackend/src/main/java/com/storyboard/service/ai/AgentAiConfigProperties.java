package com.storyboard.service.ai;

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
    private int maxClarifyRounds = 2;

    /** 分镜不满意调整最大轮数：连续「不满意→调整→重生成」达到该值后不再弹调整卡片，提示直接输入新需求（默认 3） */
    private int maxRegenerateRounds = 3;
}
