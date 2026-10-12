package com.moon.moonagent.service.asset;

import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetOutputBackfillRunnerTest {

    @Test
    void enqueuesEveryUnsyncedHistoricalOutput() {
        AgentAssetMapper mapper = mock(AgentAssetMapper.class);
        AssetOutputOutboxService outbox = mock(AssetOutputOutboxService.class);
        AssetOutputBackfillRunner runner = new AssetOutputBackfillRunner(mapper, outbox);
        ReflectionTestUtils.setField(runner, "batchSize", 50);
        AgentAsset document = new AgentAsset();
        document.setId("doc-1");
        document.setType("document");
        when(mapper.findUnsyncedOutputAssets(anyInt())).thenReturn(List.of(document));

        runner.run();

        verify(mapper).findUnsyncedOutputAssets(50);
        verify(outbox).enqueue(document);
    }

    @Test
    void doesNothingWhenBacklogIsEmpty() {
        AgentAssetMapper mapper = mock(AgentAssetMapper.class);
        AssetOutputOutboxService outbox = mock(AssetOutputOutboxService.class);
        AssetOutputBackfillRunner runner = new AssetOutputBackfillRunner(mapper, outbox);
        when(mapper.findUnsyncedOutputAssets(anyInt())).thenReturn(List.of());

        runner.run();

        verify(outbox, never()).enqueue(org.mockito.ArgumentMatchers.any());
        verify(mapper, times(1)).findUnsyncedOutputAssets(anyInt());
    }
}
