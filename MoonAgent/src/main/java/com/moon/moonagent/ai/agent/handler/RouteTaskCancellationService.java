package com.moon.moonagent.ai.agent.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.StoryboardImageTask;
import com.moon.moonagent.entity.StoryboardVideoQueue;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.StoryboardImageTaskMapper;
import com.moon.moonagent.mapper.StoryboardVideoQueueMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 切链时的任务取消服务：用户从当前链切到另一链时，取消该会话的进行中任务，避免旧任务继续消耗资源/产出。
 *
 * <p>取消范围（best-effort，任一失败仅 log，不抛异常打断切链）：
 * <ul>
 *   <li>agent_assets 中本会话 queued/generating/running 的视频任务（有 taskId 者先调上游 cancelVideoTask，再置 cancelled）；</li>
 *   <li>storyboard_video_queue / storyboard_image_task 中同会话未完成任务（置 cancelled）。</li>
 * </ul>
 * <p>保留 completed 资产与已完成素材。
 */
@Component
public class RouteTaskCancellationService {

    private static final Logger log = LoggerFactory.getLogger(RouteTaskCancellationService.class);

    private final AgentAssetMapper assetMapper;
    private final AgentGenerationService generationService;
    private final StoryboardVideoQueueMapper videoQueueMapper;
    private final StoryboardImageTaskMapper imageTaskMapper;

    public RouteTaskCancellationService(AgentAssetMapper assetMapper,
                                        AgentGenerationService generationService,
                                        StoryboardVideoQueueMapper videoQueueMapper,
                                        StoryboardImageTaskMapper imageTaskMapper) {
        this.assetMapper = assetMapper;
        this.generationService = generationService;
        this.videoQueueMapper = videoQueueMapper;
        this.imageTaskMapper = imageTaskMapper;
    }

    /**
     * 取消会话的进行中任务。
     *
     * @param conversationId 会话 id
     */
    public void cancelPendingTasks(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        cancelAgentAssets(conversationId);
        cancelVideoQueue(conversationId);
        cancelImageQueue(conversationId);
    }

    private void cancelAgentAssets(String conversationId) {
        try {
            List<AgentAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<AgentAsset>()
                    .eq(AgentAsset::getConversationId, conversationId)
                    .in(AgentAsset::getStatus, List.of("queued", "generating", "running")));
            for (AgentAsset asset : assets) {
                try {
                    if (asset.getTaskId() != null && !asset.getTaskId().isBlank()) {
                        try {
                            generationService.cancelVideoTask(asset.getTaskId());
                        } catch (Exception e) {
                            log.warn("切链取消上游视频任务失败 assetId={} taskId={}: {}", asset.getId(), asset.getTaskId(), e.getMessage());
                        }
                    }
                    assetMapper.update(null, new LambdaUpdateWrapper<AgentAsset>()
                            .eq(AgentAsset::getId, asset.getId())
                            .set(AgentAsset::getStatus, "cancelled"));
                } catch (Exception e) {
                    log.warn("切链取消 agent_asset 失败 assetId={}: {}", asset.getId(), e.getMessage());
                }
            }
            if (!assets.isEmpty()) {
                log.info("切链取消 agent_assets 视频任务: conversationId={} count={}", conversationId, assets.size());
            }
        } catch (Exception e) {
            log.warn("切链取消 agent_assets 扫描失败: conversationId={} error={}", conversationId, e.getMessage());
        }
    }

    private void cancelVideoQueue(String conversationId) {
        try {
            List<StoryboardVideoQueue> rows = videoQueueMapper.selectList(new LambdaQueryWrapper<StoryboardVideoQueue>()
                    .eq(StoryboardVideoQueue::getConversationId, conversationId)
                    .in(StoryboardVideoQueue::getStatus, List.of("queued", "running")));
            for (StoryboardVideoQueue row : rows) {
                try {
                    videoQueueMapper.update(null, new LambdaUpdateWrapper<StoryboardVideoQueue>()
                            .eq(StoryboardVideoQueue::getId, row.getId())
                            .set(StoryboardVideoQueue::getStatus, "cancelled"));
                } catch (Exception e) {
                    log.warn("切链取消视频队列失败 queueId={}: {}", row.getId(), e.getMessage());
                }
            }
            if (!rows.isEmpty()) {
                log.info("切链取消 storyboard_video_queue: conversationId={} count={}", conversationId, rows.size());
            }
        } catch (Exception e) {
            log.warn("切链取消 storyboard_video_queue 扫描失败: conversationId={} error={}", conversationId, e.getMessage());
        }
    }

    private void cancelImageQueue(String conversationId) {
        try {
            List<StoryboardImageTask> rows = imageTaskMapper.selectList(new LambdaQueryWrapper<StoryboardImageTask>()
                    .eq(StoryboardImageTask::getConversationId, conversationId)
                    .in(StoryboardImageTask::getStatus, List.of("queued", "running")));
            for (StoryboardImageTask row : rows) {
                try {
                    imageTaskMapper.update(null, new LambdaUpdateWrapper<StoryboardImageTask>()
                            .eq(StoryboardImageTask::getId, row.getId())
                            .set(StoryboardImageTask::getStatus, "cancelled"));
                } catch (Exception e) {
                    log.warn("切链取消图片队列失败 taskId={}: {}", row.getId(), e.getMessage());
                }
            }
            if (!rows.isEmpty()) {
                log.info("切链取消 storyboard_image_task: conversationId={} count={}", conversationId, rows.size());
            }
        } catch (Exception e) {
            log.warn("切链取消 storyboard_image_task 扫描失败: conversationId={} error={}", conversationId, e.getMessage());
        }
    }
}
