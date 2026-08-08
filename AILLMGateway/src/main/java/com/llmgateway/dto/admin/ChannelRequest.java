package com.llmgateway.dto.admin;

import lombok.Data;

/** 渠道创建/更新请求（apiKey 仅在传新值时重新加密） */
@Data
public class ChannelRequest {
    private String name;
    /** openai_compatible | gemini | minimax，为空默认 openai_compatible */
    private String type;
    private String baseUrl;
    /** 明文 Key，服务端 AES 加密后落库 */
    private String apiKey;
    private Boolean enabled;
    private Integer priority;
}
