package com.moon.moonagent.controller;

import com.moon.moonagent.ai.agent.AgentSttService;
import com.moon.moonagent.dto.response.SttResponse;
import com.storyboard.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 语音转文字端点（Moon 智能体语音输入）。
 * 鉴权由 Gateway 统一验签后透传 X-User-Id header（GatewayAuthenticationFilter 装配）。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentSttController {

    private final AgentSttService sttService;

    /** 语音识别：上传 WAV（16kHz 单声道）→ 返回识别文本 */
    @PostMapping("/stt")
    public ApiResponse<SttResponse> transcribe(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(new SttResponse(sttService.transcribe(file)));
    }
}
