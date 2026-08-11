package com.storyboard.service.agent;

/**
 * 提示词优化服务。
 *
 * 职责：把用户输入的需求草稿（≥6 字符）优化为专业的分镜提示词。
 * 优化方向由 LLM 自行判断（草稿可能是剧情脚本、图片设计或视频设计需求，也可能是综合需求），
 * 输出一段优化后的提示词文本（不强制 JSON 结构，规避解析失败风险）。
 *
 * 实现说明：已从手写 JDK HttpClient 直连网关 /v1/chat/completions 改为
 * Spring AI ChatClient（spring.ai.openai.base-url 已指向网关 /v1，纯文本调用，无结构化输出）。
 *
 * <p>实现：{@link com.storyboard.service.agent.impl.PromptOptimizeServiceImpl}。
 */
public interface PromptOptimizeService {

    /** 优化草稿为专业提示词（LLM 自判类型，单文本输出）。失败抛 RuntimeException（Controller 统一转错误码）。 */
    String optimize(String content);
}
