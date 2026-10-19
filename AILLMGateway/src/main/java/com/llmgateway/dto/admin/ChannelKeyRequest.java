package com.llmgateway.dto.admin;

import lombok.Data;

/** 渠道 Key 池创建/更新请求；更新时 apiKey 留空=不更换 */
@Data
public class ChannelKeyRequest {
    private String label;
    private String apiKey;
    private Integer weight;
    private Boolean enabled;
}
