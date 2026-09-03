package com.moon.moonagent.service;

import com.moon.moonagent.dto.request.StoryboardVideoEnqueueRequest;
import com.moon.moonagent.dto.request.StoryboardVideoEnqueueItem;
import com.moon.moonagent.entity.StoryboardVideoQueue;

import java.util.List;

/**
 * 分镜视频批量生成队列服务（后端持久化、串行执行、可取消）。
 *
 * <p>对外暴露：入队（批量）、查询整队、取消。内部以单飞锁驱动队列，
 * 始终只执行最早的 queued 任务（一个分镜一个分镜生成），终态由视频轮询回写 scenes。
 */
public interface StoryboardVideoQueueService {

    /**
     * 批量入队：每个 item 落一条 queued 队列记录，并把对应分镜的 videoStatus 置为 queued。
     *
     * @param request 入队请求（conversationId/projectId/items）
     * @return 本次入队的队列记录（含 queueId，供前端展示/取消）
     */
    List<StoryboardVideoQueue> enqueue(StoryboardVideoEnqueueRequest request);

    /**
     * 查询某项目的队列（created_at 正序）。
     *
     * @param status 可选过滤：{@code active} 仅返回 queued/running；缺省/null 返回全量。
     */
    List<StoryboardVideoQueue> listByProject(String projectId, String status);

    /** 根据队列 id 反查其所属项目（供 controller 做归属校验；不存在返回 null）。 */
    String getProjectId(String queueId);

    /**
     * 取消单个队列任务。
     * 仅 queued 可真正取消（移除后续执行）；running 置本地 cancelled 并停止跟踪，
     * 但上游网关无视频取消 API，无法叫停上游任务（UI 需明示）。
     */
    void cancel(String queueId);
}
