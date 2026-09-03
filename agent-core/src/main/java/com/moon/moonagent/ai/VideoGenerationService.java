package com.moon.moonagent.ai;

import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.ai.video.ReferenceImageRole;

import java.util.ArrayList;
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
     * 响应解析：网关已归一为 {vendor, taskId}，本层只认 taskId 落库（scene.videoTaskId 逻辑不变）。
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
     * 创建视频任务（新版：支持带 role 的参考图列表）。
     * <p>
     * 每个参考图可指定用途（首帧/尾帧/参考图），由适配器负责转换为各模型特定格式。
     * <p>
     * 向后兼容：如果调用方没有 role 信息，可使用 {@link ReferenceImage#ofReference(String)} 包装。
     *
     * @param sceneId           分镜 ID（可为空）
     * @param prompt            视频生成提示词
     * @param alias             模型别名（可为空，使用默认）
     * @param resolution        分辨率（可为空，使用默认）
     * @param size              尺寸（可为空）
     * @param aspectRatio       宽高比（可为空）
     * @param duration          时长（可为空）
     * @param negativePrompt    负面提示词（可为空）
     * @param seed              随机种子（可为空）
     * @param referenceImages   带 role 的参考图列表（可为空）
     * @param generatedImageUrl 已生成的图片 URL（首帧，可为空）
     * @param referenceVideos   参考视频列表（可为空）
     * @param referenceAudios   参考音频列表（可为空）
     * @return 视频任务 ID
     */
    String createVideoTask(String sceneId, String prompt, String alias,
                           String resolution, String size, String aspectRatio,
                           Integer duration, String negativePrompt, Long seed,
                           List<ReferenceImage> referenceImages, String generatedImageUrl,
                           List<String> referenceVideos, List<String> referenceAudios,
                           boolean isReferenceImageWithRole);

    /**
     * 轮询视频任务状态，完成后自动下载视频文件（统一走网关）。
     * 网关统一响应 {taskId, status, progress?, error?}（status: processing/succeeded/failed）：
     *   succeeded → GET 网关下载端点拿视频流 → 本地转存 uploads/videos → {status:completed, videoUrl}
     *   failed   → 透传 error
     *   processing → 返回 processing（透传 progress）
     */
    Map<String, String> pollVideoTask(String taskId);

    /**
     * 取消/删除视频任务（按厂商能力适配；MiniMax v2: DELETE /v2/video_generation/{taskId}）。
     * 返回归一化 map：{status(cancelled/deleted), taskId}；上游不支持或无此任务时抛异常。
     */
    Map<String, String> cancelVideoTask(String taskId);
}



