package com.storyboard.ai;

import java.util.List;
import java.util.Map;

/**
 * 视频生成服务 —— 创建/轮询/下载统一走 LLM 网关（/v1/videos）。
 *
 * 网关侧已完成协议转换（Laozhang multipart / MiniMax content 数组）与渠道路由，
 * 业务侧只保留：本地图 → data URI 内联（图生视频）、状态落库、轮询 4 分钟
 * giveUp 窗口、下载重试 3 次与本地转存 uploads/videos。
 */
public interface VideoGenerationService {

    /**
     * 创建视频生成任务（统一走网关 POST /v1/videos，JSON 体，OpenAI 风格格式）。
     * 响应解析 task_id / id / taskId 任一 → 返回 taskId 落库（scene.videoTaskId 逻辑不变）。
     */
    String createVideoTask(String sceneId, String prompt, String alias,
                           String resolution, String size, String aspectRatio,
                           Integer duration, String negativePrompt, Long seed,
                           List<String> referenceImages, String generatedImageUrl);

    /**
     * 创建视频生成任务（多模态参考素材版：referenceVideos/referenceAudios 为本地文件相对 URL
     * 或 data URI/mm_file://；与首帧 imageUrl 互斥，由服务内部保证）。
     */
    String createVideoTask(String sceneId, String prompt, String alias,
                           String resolution, String size, String aspectRatio,
                           Integer duration, String negativePrompt, Long seed,
                           List<String> referenceImages, String generatedImageUrl,
                           List<String> referenceVideos, List<String> referenceAudios);

    /**
     * 轮询视频任务状态，完成后自动下载视频文件（统一走网关）。
     * 网关统一响应 {taskId, status, progress?, error?}（status: processing/succeeded/failed）：
     *   succeeded → GET 网关下载端点拿视频流 → 本地转存 uploads/videos → {status:completed, videoUrl}
     *   failed   → 透传 error
     *   processing → 返回 processing（透传 progress）
     */
    Map<String, String> pollVideoTask(String taskId);
}
