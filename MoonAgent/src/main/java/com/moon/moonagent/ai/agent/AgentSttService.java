package com.moon.moonagent.ai.agent;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.InputStream;

/**
 * 语音转文字服务：接收 16kHz 单声道 WAV，经 vosk-server WebSocket 识别。
 */
public interface AgentSttService {

    /**
     * 识别 WAV 语音内容。
     *
     * @param file 16kHz 单声道 16bit WAV 文件（前端 AudioContext 重采样后生成）
     * @return 识别文本（空串表示未识别到语音）
     */
    String transcribe(MultipartFile file);

    /**
     * 流式语音识别：实时读取 PCM 音频流转发 vosk，识别文本经 SSE 实时推送。
     *
     * @param audioIn 16kHz 单声道 16bit PCM 裸流（无 WAV 头，前端录音中实时推流）
     * @param emitter SSE 推送器：partial 事件实时推识别中文本，结束时推 final 文本并 complete
     */
    void streamTranscribe(InputStream audioIn, SseEmitter emitter);
}
