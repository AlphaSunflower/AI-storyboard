package com.llmgateway.service;

import com.llmgateway.entity.CallLog;
import com.llmgateway.mapper.CallLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/** 调用日志异步落库（不阻塞响应）；videoUrl 供视频下载端点暂存 MiniMax 限时直链 */
@Service
public class CallLogService {

    private static final Logger log = LoggerFactory.getLogger(CallLogService.class);

    private final CallLogMapper callLogMapper;

    public CallLogService(CallLogMapper callLogMapper) {
        this.callLogMapper = callLogMapper;
    }

    @Async
    public void log(String model, String channelId, String status, long durationMs, String error, String videoUrl) {
        try {
            CallLog record = new CallLog();
            record.setModel(model);
            record.setChannelId(channelId);
            record.setStatus(status);
            record.setDurationMs(durationMs);
            record.setError(error);
            record.setVideoUrl(videoUrl);
            record.setCreatedAt(OffsetDateTime.now());
            callLogMapper.insert(record);
        } catch (Exception e) {
            log.warn("调用日志落库失败: {}", e.getMessage());
        }
    }
}
