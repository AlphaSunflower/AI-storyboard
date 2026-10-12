package com.moon.moonagent.service.asset;

import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把引入自动回写之前产生的历史产出补进项目库。
 *
 * <p>沙箱产物过去不会入项目库 outbox，导致生成的文档/图片/视频只留在对话侧。
 * 这里定期扫描「已完成 + 未入队 + 库中无同对象」的产出并重新入队；
 * outbox 以 (producer, generation_record_id, result_index) 唯一，重复执行不会产生重复回写。
 */
@Component
@RequiredArgsConstructor
public class AssetOutputBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(AssetOutputBackfillRunner.class);

    private final AgentAssetMapper assetMapper;
    private final AssetOutputOutboxService outboxService;

    @Value("${storyboard.asset-client.backfill-batch-size:200}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${storyboard.asset-client.backfill-delay-ms:600000}",
            initialDelayString = "${storyboard.asset-client.backfill-initial-delay-ms:90000}")
    public void run() {
        List<AgentAsset> pending = assetMapper.findUnsyncedOutputAssets(Math.max(1, batchSize));
        if (pending == null || pending.isEmpty()) return;
        int enqueued = 0;
        for (AgentAsset asset : pending) {
            outboxService.enqueue(asset);
            enqueued++;
        }
        log.info("项目库产出回补: 本轮扫描 {} 条历史产出并重新入队", enqueued);
    }
}
