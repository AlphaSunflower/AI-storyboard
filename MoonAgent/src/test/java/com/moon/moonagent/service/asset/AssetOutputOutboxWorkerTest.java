package com.moon.moonagent.service.asset;

import com.moon.moonagent.entity.AssetOutboxEvent;
import com.moon.moonagent.mapper.AssetOutboxMapper;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetOutputOutboxWorkerTest {

    @Test
    void registersClaimedEventAndMarksSucceeded() {
        AssetOutboxMapper mapper = mock(AssetOutboxMapper.class);
        AssetClient assetClient = mock(AssetClient.class);
        AssetOutputOutboxWorker worker = new AssetOutputOutboxWorker(mapper, assetClient);
        when(mapper.claim(anyString(), anyInt())).thenReturn(List.of(event()));
        when(assetClient.registerGenerationOutput(any(AssetContracts.GenerationOutputEvent.class), anyString()))
                .thenReturn(null);

        worker.run();

        verify(assetClient).registerGenerationOutput(any(AssetContracts.GenerationOutputEvent.class), anyString());
        verify(mapper).markSucceeded("event-1");
    }

    private static AssetOutboxEvent event() {
        AssetOutboxEvent event = new AssetOutboxEvent();
        event.setEventId("event-1");
        event.setAttemptCount(0);
        event.setPayloadJson("""
                {
                  "producer":"moon-agent",
                  "ownerUserId":"u1",
                  "projectId":"p1",
                  "generationRecordId":"record-1",
                  "resultIndex":0,
                  "resultAssetId":"record-1",
                  "resultUrl":"/api/files/images/a.png",
                  "mediaType":"IMAGE",
                  "prompt":"测试",
                  "model":"image-model",
                  "metadata":{}
                }
                """);
        return event;
    }
}
