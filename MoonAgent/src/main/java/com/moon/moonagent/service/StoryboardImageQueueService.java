package com.moon.moonagent.service;

import com.moon.moonagent.dto.request.StoryboardImageEnqueueRequest;
import com.moon.moonagent.entity.StoryboardImageTask;

import java.util.List;

/**
 * 分镜图片队列服务：完善已有图片/自动生成分镜图片，批量入队（串行执行）→ 查询整队 → 取消。
 */
public interface StoryboardImageQueueService {

    /** 批量入队并触发串行处理，返回创建的行。 */
    List<StoryboardImageTask> enqueue(StoryboardImageEnqueueRequest request);

    /**
     * 按项目查询队列（created_at 正序）。
     *
     * @param status 可选过滤：{@code active} 仅返回 queued/running；缺省/null 返回全量。
     */
    List<StoryboardImageTask> listByProject(String projectId, String status);

    /** 根据队列 id 反查其所属项目（供 controller 做归属校验；不存在返回 null）。 */
    String getProjectId(String queueId);

    /** 取消单条队列任务（queued 真正取消；running 仅本地标记并停止跟踪）。 */
    void cancel(String queueId);
}
