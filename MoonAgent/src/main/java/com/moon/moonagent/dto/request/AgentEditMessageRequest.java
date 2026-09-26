package com.moon.moonagent.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 编辑最后一轮用户消息并覆盖重发。 */
public record AgentEditMessageRequest(
        @NotBlank(message = "消息内容不能为空") String content
) {
}
