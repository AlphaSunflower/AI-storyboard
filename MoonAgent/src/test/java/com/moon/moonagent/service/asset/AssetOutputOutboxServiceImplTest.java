package com.moon.moonagent.service.asset;

import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AssetOutboxEvent;
import com.moon.moonagent.mapper.AssetOutboxMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AssetOutputOutboxServiceImplTest {

    @Test
    void enqueuesEachCompletedResultUrl() {
        AssetOutboxMapper mapper = mock(AssetOutboxMapper.class);
        AssetOutputOutboxServiceImpl service = new AssetOutputOutboxServiceImpl(mapper);
        AgentAsset asset = completedAsset();

        service.enqueue(asset);

        ArgumentCaptor<AssetOutboxEvent> captor = ArgumentCaptor.forClass(AssetOutboxEvent.class);
        verify(mapper, times(2)).insertIgnore(captor.capture());
        assertThat(captor.getAllValues()).extracting(AssetOutboxEvent::getResultIndex)
                .containsExactly(0, 1);
        assertThat(captor.getAllValues()).extracting(AssetOutboxEvent::getProducer)
                .containsOnly("moon-agent");
        assertThat(captor.getAllValues().getFirst().getPayloadJson())
                .contains("\"generationRecordId\":\"record-1\"")
                .contains("\"resultUrl\":\"/api/files/images/a.png\"");
    }

    @Test
    void ignoresUnfinishedRecord() {
        AssetOutboxMapper mapper = mock(AssetOutboxMapper.class);
        AssetOutputOutboxServiceImpl service = new AssetOutputOutboxServiceImpl(mapper);
        AgentAsset asset = completedAsset();
        asset.setStatus("running");

        service.enqueue(asset);

        verify(mapper, times(0)).insertIgnore(org.mockito.ArgumentMatchers.any());
    }

    private static AgentAsset completedAsset() {
        AgentAsset asset = new AgentAsset();
        asset.setId("record-1");
        asset.setUserId("u1");
        asset.setProjectId("p1");
        asset.setConversationId("c1");
        asset.setType("image");
        asset.setGenType("text_to_image");
        asset.setPrompt("测试");
        asset.setModel("image-model");
        asset.setStatus("completed");
        asset.setResultUrls("[\"/api/files/images/a.png\",\"/api/files/images/b.png\"]");
        return asset;
    }
}
