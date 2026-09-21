package com.moon.moonagent.service.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AssetOutboxEvent;
import com.moon.moonagent.mapper.AssetOutboxMapper;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AssetOutputOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(AssetOutputOutboxWorker.class);

    private final AssetOutboxMapper outboxMapper;
    private final AssetClient assetClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String workerId = "moon-asset-outbox-" + UUID.randomUUID();

    @Scheduled(fixedDelayString = "${storyboard.asset-client.outbox-delay-ms:15000}")
    public void run() {
        for (AssetOutboxEvent event : outboxMapper.claim(workerId, 20)) {
            try {
                AssetContracts.GenerationOutputEvent payload = objectMapper.readValue(
                        event.getPayloadJson(), AssetContracts.GenerationOutputEvent.class);
                assetClient.registerGenerationOutput(payload, UUID.randomUUID().toString());
                outboxMapper.markSucceeded(event.getEventId());
            } catch (Exception e) {
                outboxMapper.markFailed(event.getEventId(), trim(e.getMessage()));
                log.warn("项目库生成结果回写失败: eventId={}, attempt={}, 原因={}",
                        event.getEventId(), event.getAttemptCount(), e.getMessage());
            }
        }
    }

    private static String trim(String message) {
        if (message == null || message.isBlank()) return "回写失败";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
