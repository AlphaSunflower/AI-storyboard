package com.moon.moonagent.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 过期 checkpoint 定期清理：已消费/过期且超过保留天数的记录删除。
 * 软编码保留天数（默认 30 天），cron 表达式可经 yml 覆盖。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckpointCleanupService {

    private final AgentCheckpointMapper checkpointMapper;

    /** 默认保留天数（软编码，后续可从 sys_config 读取） */
    private static final int RETENTION_DAYS = 30;

    @Scheduled(cron = "${ai.cleanup.checkpoint-cron:0 0 3 * * *}")
    public void cleanup() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(RETENTION_DAYS);
        int deleted = checkpointMapper.delete(new LambdaQueryWrapper<AgentCheckpoint>()
                .in(AgentCheckpoint::getStatus, "used", "expired")
                .lt(AgentCheckpoint::getExpirationTime, cutoff));
        if (deleted > 0) {
            log.info("清理过期 checkpoint: {} 条（保留 {} 天）", deleted, RETENTION_DAYS);
        }
    }
}
