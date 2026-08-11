package com.storyboard.service.ai;

import java.util.List;
import java.util.Map;

/**
 * MiniMax 视频生成服务 —— 网关通道（协议转换已下沉 LLM 网关）。
 *
 * 原直连 MiniMax V2（content 数组 / data URI 内联 / 768P 恒定）的协议转换已删除，
 * 创建/轮询/下载统一走网关 /v1/videos 端点（与 {@link VideoGenerationService} 相同模式）：
 *   POST /v1/videos                               创建（JSON，OpenAI 风格统一格式）
 *   GET  /v1/videos/{taskId}                      轮询（统一响应 {taskId,status,progress?,error?}）
 *   GET  /v1/videos/{taskId}/content              下载（视频流，网关代理）
 * 保留双通道反查逻辑：终态更新 scene.videoTaskId 或 agent_assets.task_id 对应记录。
 */
public interface MinimaxVideoService {

    /**
     * 创建视频生成任务（统一走网关 POST /v1/videos）。
     * 返回 task_id / id / taskId 任一解析出的 taskId 并落库 scene.videoTaskId（sceneId 非空时）。
     */
    String createVideoTask(String sceneId, String prompt, String alias,
                           String resolution, String size, String aspectRatio,
                           Integer duration, String negativePrompt, Long seed,
                           List<String> referenceImages, String generatedImageUrl);

    /**
     * 轮询视频任务状态，成功后经网关下载并转存本地（统一响应处理）。
     * 状态映射：succeeded→completed（网关下载到 uploads/videos）；
     * failed→failed（透传 error）；processing→processing。
     */
    Map<String, String> pollVideoTask(String taskId);
}
