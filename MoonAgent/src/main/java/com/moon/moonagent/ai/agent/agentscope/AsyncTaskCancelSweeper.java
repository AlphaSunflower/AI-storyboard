package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.moon.moonagent.entity.AgentAsyncTask;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 收尾「永远停在 cancelling」的异步任务。
 *
 * <p>取消是协作式的：任务线程自己读 cancel 标记再落终态。如果执行线程已经不在
 * （进程重启、工具线程被丢、上游一直不返回），任务就会永久停在 {@code cancelling}。
 * 客户端把它当「活跃任务」，于是每 5 秒拉一次全量任务列表（本机实测 39 条 / 395 KB）——
 * 一个僵尸任务就能让整个 /chat 一直不降频。
 *
 * <p>超过阈值仍为 {@code cancelling} 的，直接落 {@code cancelled} 终态（保留 error 说明）。
 * 阈值要大于工具执行超时（默认 20 分钟），避免把正在收尾的任务误杀。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncTaskCancelSweeper {

    private final AgentAsyncTaskMapper taskMapper;

    @Value("${ai.agent.agentscope.cancel-sweep-enabled:true}")
    private boolean enabled;

    @Value("${ai.agent.agentscope.cancel-timeout-minutes:40}")
    private long timeoutMinutes;

    @Scheduled(fixedDelayString = "${ai.agent.agentscope.cancel-sweep-delay-ms:300000}")
    public void finalizeStaleCancelling() {
        if (!enabled || timeoutMinutes <= 0) return;
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minus(Duration.ofMinutes(timeoutMinutes));
        try {
            int updated = taskMapper.update(null, new LambdaUpdateWrapper<AgentAsyncTask>()
                    .eq(AgentAsyncTask::getStatus, "cancelling")
                    .lt(AgentAsyncTask::getUpdatedAt, cutoff)
                    .set(AgentAsyncTask::getStatus, "cancelled")
                    .set(AgentAsyncTask::getError, "取消超时，已强制结束")
                    .set(AgentAsyncTask::getUpdatedAt, now)
                    .set(AgentAsyncTask::getCompletedAt, now));
            if (updated > 0) {
                log.warn("强制结束 {} 个长期停留在 cancelling 的异步任务（阈值 {} 分钟）",
                        updated, timeoutMinutes);
            }
        } catch (RuntimeException e) {
            log.warn("cancelling 任务收尾失败（下一轮重试）: {}", e.getMessage());
        }
    }
}
