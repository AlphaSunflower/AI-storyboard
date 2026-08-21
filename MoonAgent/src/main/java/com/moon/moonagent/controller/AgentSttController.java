package com.moon.moonagent.controller;

import com.moon.moonagent.ai.agent.AgentSttService;
import com.moon.moonagent.dto.response.SttResponse;
import com.storyboard.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;

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

    /**
     * 流式语音识别：请求体为 16kHz 单声道 16bit PCM 裸流（录音中实时推流），
     * 响应为 SSE——partial 事件实时推识别中文本，结束推 final 事件并关闭。
     * 前端录音停止即关闭请求体（EOF）→ 后端发 eof 收最终结果。
     */
    @PostMapping(value = "/stt/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTranscribe(HttpServletRequest request) throws IOException {
        SseEmitter emitter = new SseEmitter(0L); // 不超时，由前端断开驱动结束
        // 虚拟线程：阻塞读请求体流（直到前端关闭=EOF）不影响 servlet 线程池
        InputStream body = request.getInputStream();
        Thread.startVirtualThread(() -> sttService.streamTranscribe(body, emitter));
        return emitter;
    }
}
