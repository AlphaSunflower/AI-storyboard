package com.moon.moonagent.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 会话资产组绑定请求。 */
public record AgentAssetGroupBindingRequest(@NotBlank String groupId) {
}
