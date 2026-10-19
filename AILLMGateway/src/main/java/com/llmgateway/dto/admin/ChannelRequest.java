package com.llmgateway.dto.admin;

import lombok.Data;

/** 渠道创建/更新请求（apiKey 仅在传新值时重新加密） */
@Data
public class ChannelRequest {
    private String name;
    /** openai_compatible | gemini | minimax | ollama，为空默认 openai_compatible */
    private String type;
    private String baseUrl;
    /** 明文 Key，服务端 AES 加密后落库 */
    private String apiKey;
    /** 该渠道支持的模型名（逗号分隔，可空），测试弹窗下拉候选 */
    private String models;
    private Boolean enabled;
    private Integer priority;
    /** 同优先级负载权重（默认 1） */
    private Integer weight;
    /** 单请求超时覆盖（毫秒，空=全局默认） */
    private Integer timeoutMs;
    /** 渠道级额外重试次数（默认 0） */
    private Integer maxRetries;
}
