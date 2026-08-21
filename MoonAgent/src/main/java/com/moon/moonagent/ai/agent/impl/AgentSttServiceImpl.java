package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.agent.AgentSttService;
import com.storyboard.common.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 语音转文字实现：解析 WAV → WebSocket 转发 vosk-server（JDK 自带 HttpClient，零新依赖）。
 * 协议：发 config → 发 PCM 二进制帧（16kHz 16bit mono）→ 发 {"eof":1} → 收 {"result":{"text":"..."}}。
 */
@Slf4j
@Service
public class AgentSttServiceImpl implements AgentSttService {

    /** vosk-server WebSocket 地址，可用 vosk.ws-url 覆盖 */
    @Value("${vosk.ws-url:ws://localhost:2700}")
    private String wsUrl;

    /** 识别超时秒数，可用 vosk.timeout 覆盖 */
    @Value("${vosk.timeout:30}")
    private long timeoutSeconds;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String transcribe(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40001, "音频文件不能为空");
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new BusinessException(40001, "音频文件过大（上限 10MB，约 5 分钟语音）");
        }
        byte[] pcm;
        try {
            pcm = extractPcm16kMono(file.getBytes());
        } catch (IOException e) {
            throw new BusinessException(40001, "音频读取失败");
        }
        if (pcm.length == 0) {
            throw new BusinessException(40001, "音频文件格式不正确（需要 16kHz 单声道 WAV）");
        }
        return recognize(pcm);
    }

    /** 解析 WAV 头，校验 RIFF/WAVE 魔数与 16kHz/单声道/16bit PCM，按块定位 data 后返回裸 PCM */
    private byte[] extractPcm16kMono(byte[] wav) {
        if (wav.length < 44
                || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F'
                || wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') {
            throw new BusinessException(40001, "音频文件格式不正确（需要 WAV）");
        }
        int audioFormat = (wav[20] & 0xff) | (wav[21] & 0xff) << 8;
        int channels = (wav[22] & 0xff) | (wav[23] & 0xff) << 8;
        int sampleRate = (wav[24] & 0xff) | (wav[25] & 0xff) << 8
                | (wav[26] & 0xff) << 16 | (wav[27] & 0xff) << 24;
        int bitsPerSample = (wav[34] & 0xff) | (wav[35] & 0xff) << 8;
        if (audioFormat != 1 || channels != 1 || sampleRate != 16000 || bitsPerSample != 16) {
            throw new BusinessException(40001, "音频格式需为 16kHz 单声道 16bit PCM WAV");
        }
        // 从 fmt 块之后按块扫描定位 data（容错非标准 44 字节头，如 fmt 带扩展字段）
        int offset = 12;
        while (offset + 8 <= wav.length) {
            String id = new String(wav, offset, 4, StandardCharsets.US_ASCII);
            int len = (wav[offset + 4] & 0xff) | (wav[offset + 5] & 0xff) << 8
                    | (wav[offset + 6] & 0xff) << 16 | (wav[offset + 7] & 0xff) << 24;
            // 防御恶意/损坏的 len：负数（如 0xFFFFFFF8）会导致 offset 不前进死循环，
            // 超出文件实际范围则跳块越界——直接跳出走「未找到 data 块」40001 分支（long 运算防 int 溢出）
            if (len < 0 || offset + 8L + len > wav.length) {
                break;
            }
            if ("data".equals(id)) {
                int start = offset + 8;
                int actual = Math.max(0, Math.min(len, wav.length - start));
                byte[] pcm = new byte[actual];
                System.arraycopy(wav, start, pcm, 0, actual);
                return pcm;
            }
            offset += 8 + len + (len & 1); // 块按 2 字节对齐
        }
        throw new BusinessException(40001, "音频文件格式不正确（未找到 data 块）");
    }

    /** 经 WebSocket 转发 vosk-server 并取回识别文本 */
    private String recognize(byte[] pcm) {
        CompletableFuture<String> result = new CompletableFuture<>();
        List<String> texts = new ArrayList<>();

        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder partial = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                // vosk 响应可能被分片，先拼接完整消息再解析
                partial.append(data);
                if (!last) {
                    return WebSocket.Listener.super.onText(webSocket, data, false);
                }
                String msg = partial.toString();
                partial.setLength(0);
                handleMessage(msg, texts);
                return WebSocket.Listener.super.onText(webSocket, data, true);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                if (!result.isDone()) {
                    // vosk 中间词结果（AcceptWaveform 命中）与最终结果同为 {"text":...} 格式，
                    // 中间结果文本是最终结果的子集——只取最后一条避免重复拼接
                    result.complete(texts.isEmpty() ? "" : texts.get(texts.size() - 1).trim());
                }
                return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                if (!result.isDone()) {
                    result.completeExceptionally(error);
                }
            }
        };

        try {
            WebSocket ws = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), listener)
                    .get(10, TimeUnit.SECONDS);
            // 显式下发 config 更稳（vosk 默认即 16k）
            ws.sendText("{\"config\":{\"sample_rate\":16000}}", true).join();
            // 分块发送 PCM（1 秒 = 16000 采样 * 2 字节 = 32000 字节）
            int chunk = 16000 * 2;
            for (int off = 0; off < pcm.length; off += chunk) {
                byte[] part = new byte[Math.min(chunk, pcm.length - off)];
                System.arraycopy(pcm, off, part, 0, part.length);
                ws.sendBinary(ByteBuffer.wrap(part), true).join();
            }
            ws.sendText("{\"eof\" : 1}", true).join(); // 注意空格：vosk-server 精确字符串匹配 '{"eof" : 1}'，无空格会当音频数据
            return result.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("vosk 识别超时: {}", wsUrl, e);
            throw new BusinessException(50001, "语音识别服务暂不可用，请稍后重试");
        } catch (InterruptedException e) {
            // 恢复中断标志，避免吞掉线程中断状态
            Thread.currentThread().interrupt();
            log.warn("vosk 识别被中断: {}", wsUrl, e);
            throw new BusinessException(50001, "语音识别服务暂不可用，请稍后重试");
        } catch (ExecutionException e) {
            // HttpClient 连接失败/onError 都会被包成 ExecutionException
            log.warn("vosk 连接/识别失败: {}", wsUrl, e);
            throw new BusinessException(50001, "语音识别服务暂不可用，请稍后重试");
        } catch (Exception e) {
            log.warn("vosk 识别异常: {}", wsUrl, e);
            throw new BusinessException(50001, "语音识别服务暂不可用，请稍后重试");
        }
    }

    /**
     * 解析 vosk 返回消息：partial 为 {"partial":"..."}，最终结果为 {"text":"..."}（vosk 原生 FinalResult 格式，
     * 无 "result" 包装键——实测 vosk-server 直传）。仅收集最终结果：含 "text" 键且不含 "partial" 键。
     * 子串扫描零依赖（vosk 输出结构固定；文本不含引号，跳过转义处理）。
     */
    private void handleMessage(String msg, List<String> texts) {
        try {
            if (msg.contains("\"partial\"")) {
                return; // 中间结果丢弃
            }
            int textIdx = msg.indexOf("\"text\"");
            if (textIdx < 0) {
                return;
            }
            int colonIdx = msg.indexOf(':', textIdx + 6);
            int start = msg.indexOf('"', colonIdx + 1);
            int end = msg.indexOf('"', start + 1);
            if (start < 0 || end < 0) {
                return;
            }
            String text = msg.substring(start + 1, end);
            if (!text.isEmpty()) {
                texts.add(text);
            }
        } catch (Exception e) {
            log.debug("vosk 消息解析失败: {}", msg);
        }
    }
}
