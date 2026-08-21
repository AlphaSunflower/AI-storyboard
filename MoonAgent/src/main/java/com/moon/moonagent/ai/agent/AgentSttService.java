package com.moon.moonagent.ai.agent;

import org.springframework.web.multipart.MultipartFile;

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
}
