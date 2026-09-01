package com.moon.moonagent.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.SceneGenerationBridge;
import com.moon.moonagent.ai.SceneView;
import com.storyboard.common.MultipartBuilder;
import com.moon.moonagent.ai.VideoGenerationService;
import com.moon.moonagent.ai.video.ReferenceImage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import com.storyboard.common.BusinessException;

/**
 * 视频生成服务实现 —— 创建/轮询/下载统一走 LLM 网关（/v1/videos）。
 */
@Service
@RequiredArgsConstructor
public class VideoGenerationServiceImpl implements VideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationServiceImpl.class);

    /** H3 参考图硬上限（资产参考图 + 手动参考素材合并后截断）。 */
    private static final int MAX_REFERENCE_IMAGES = 9;

    private final AiConfigProperties config;
    private final GatewayModelService gatewayModelService;
    private final VideoPollExecutor videoPollExecutor;
    private final FileStorageService fileStorageService;
    private final SceneGenerationBridge sceneBridge;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 创建视频生成任务（统一走网关 POST /v1/videos，JSON 体，OpenAI 风格格式）。
     * 响应解析：网关已归一为 {vendor, taskId}，本层只认 taskId 落库（scene.videoTaskId 逻辑不变）。
     */
    @Override
    public String createVideoTask(String sceneId, String prompt, String alias,
                                   String resolution, String size, String aspectRatio,
                                   Integer duration, String negativePrompt, Long seed,
                                   List<String> referenceImages, String generatedImageUrl) {
        return createVideoTask(sceneId, prompt, alias, resolution, size, aspectRatio,
                duration, negativePrompt, seed, referenceImages, generatedImageUrl, null, null);
    }

    /**
     * 创建视频生成任务（多模态参考素材版）。
     * 参考素材任一存在 → 多模态参考模式（r2va，不传首帧 imageUrl）；否则沿用首帧/文生视频逻辑。
     */
    @Override
    public String createVideoTask(String sceneId, String prompt, String alias,
                                   String resolution, String size, String aspectRatio,
                                   Integer duration, String negativePrompt, Long seed,
                                   List<String> referenceImages, String generatedImageUrl,
                                   List<String> referenceVideos, List<String> referenceAudios) {
        SceneView scene = sceneId != null ? sceneBridge.getScene(sceneId) : null;
        if (sceneId != null && scene == null) throw new BusinessException(40401, "资源不存在: " + sceneId);

        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(40001, "视频生成 prompt 不能为空");
        }

        String actualModel = alias != null
                ? config.getVideoModelAliasMap().getOrDefault(alias, alias)
                : (scene != null && scene.videoModel != null && !scene.videoModel.isBlank())
                        ? scene.videoModel
                        : gatewayModelService.getDefaultVideoModel();  // 默认模型：网关 model_params.is_default（MiniMax 直连自家，非 laozhang）

        // 参数：显式传入 > 分镜覆盖 > 网关兜底默认（未传值则不下发，网关 model_params 兜底 768P/8s/16:9/1280x720）
        String effSize = size;
        String effResolution = resolution != null ? resolution
                : (scene != null && scene.videoResolution != null && !scene.videoResolution.isBlank())
                        ? scene.videoResolution
                        : null;
        String effAspectRatio = aspectRatio != null ? aspectRatio
                : (scene != null && scene.videoAspectRatio != null && !scene.videoAspectRatio.isBlank())
                        ? scene.videoAspectRatio
                        : null;
        Integer effDuration = duration != null ? duration
                : (scene != null && scene.duration != null && scene.duration > 0)
                        ? scene.duration
                        : null;

        // 资产库注入：场景关联资产的文字卡 + 参考图（≤9 优先级截断）；无资产则零影响
        String effectivePrompt = prompt;
        List<String> assetRefImages = List.of();
        if (scene != null) {
            try {
                String sheet = sceneBridge.sceneAssetSheet(sceneId, "video");
                if (sheet != null && !sheet.isBlank()) effectivePrompt = sheet + "\n\n" + prompt;
                assetRefImages = sceneBridge.sceneReferenceImages(sceneId, "video");
            } catch (Exception e) {
                log.warn("资产库注入失败，跳过: {}", e.getMessage());
            }
        }

        try {
            // 统一 OpenAI 风格 JSON 请求体（模型→渠道路由、协议转换已下沉网关）
            Map<String, Object> body = new HashMap<>();
            body.put("model", actualModel);          // alias 映射保留在业务侧
            body.put("prompt", effectivePrompt);
            // 未显式传值的参数不下发，由网关兜底默认（768P/8s/16:9/1280x720）
            if (effSize != null) body.put("size", effSize);
            if (effResolution != null) body.put("resolution", effResolution);
            if (effAspectRatio != null) body.put("aspectRatio", effAspectRatio);
            if (effDuration != null) body.put("duration", effDuration);
            if (negativePrompt != null && !negativePrompt.isEmpty()) {
                body.put("negativePrompt", negativePrompt);
            }
            if (seed != null) {
                body.put("seed", seed);
            }

            // 参考素材转换：一律多模态参考生视频（r2va）——用户 2026-08-27 明确「图生视频(首帧 source) 与
            // 资产生视频(资产参考图) 都当参考图 reference_image 用」，不做严格首帧 first_frame 冻结
            // （首帧冻结会锁死起始画面、丢失运镜/动态；参考图更能按设定灵活演绎）。
            // 本地文件（/api/files/... 相对路径）→ 小文件(≤10MB) data URI 内联 / 大文件经网关上传代理换 mm_file://
            List<String> refImages = new ArrayList<>();
            List<String> refVideos = new ArrayList<>();
            List<String> refAudios = new ArrayList<>();
            if (referenceVideos != null) {
                for (String v : referenceVideos) { String u = resolveReferenceUrl(v, "video"); if (u != null) refVideos.add(u); }
            }
            if (referenceAudios != null) {
                for (String a : referenceAudios) { String u = resolveReferenceUrl(a, "audio"); if (u != null) refAudios.add(u); }
            }
            if (referenceImages != null) {
                for (String i : referenceImages) { String u = resolveReferenceUrl(i, "image"); if (u != null) refImages.add(u); }
            }
            // 图生视频首帧 source 也并作参考图（外链/data URI/mm_file 透传；本地路径转 data URI/mm_file）
            if (generatedImageUrl != null && !generatedImageUrl.isBlank()) {
                String u = resolveReferenceUrl(generatedImageUrl, "image");
                if (u != null) refImages.add(u);
            }
            // 资产参考图优先于手动参考素材：本地路径转 data URI 后合并，总数 ≤ 9
            if (!assetRefImages.isEmpty()) {
                List<String> merged = new ArrayList<>();
                for (String a : assetRefImages) {
                    String u = resolveReferenceUrl(a, "image");
                    if (u != null) merged.add(u);
                }
                merged.addAll(refImages);
                if (merged.size() > MAX_REFERENCE_IMAGES) merged = merged.subList(0, MAX_REFERENCE_IMAGES);
                refImages = merged;
            }
            boolean hasRefs = !refImages.isEmpty() || !refVideos.isEmpty() || !refAudios.isEmpty();
            if (hasRefs) {
                body.put("referenceImages", refImages);
                body.put("referenceVideos", refVideos);
                body.put("referenceAudios", refAudios);
            }
            // ── 定位日志：图在哪一步消失？(grep [参考图] 可见 source → refImages 全程) ──
            log.info("[参考图] 创建视频: hasRefs={} | source(generatedImageUrl)={} | referenceImages传入={} | 资产参考图={} | 解析后refImages={} | refVideos={} | refAudios={}",
                    hasRefs, generatedImageUrl, referenceImages, assetRefImages, refImages, refVideos, refAudios);
            if (!hasRefs) {
                log.warn("[参考图] 无任何参考素材 → 纯文生视频 t2va（MiniMax query 的 input_image_count 将=0，图未进请求体）");
            }
            // 无任何参考素材 → 纯文生视频（t2va）；不写 imageUrl 首帧（首帧与参考图两条路径已统一为 reference）

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getGatewayBaseUrl() + "/v1/videos"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getGatewayApiKey())
                .timeout(Duration.ofSeconds(config.getVideoRequestTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                throw new BusinessException(50201, "Video API returned " + resp.statusCode() + ": " + resp.body());
            }
            // 网关已按 vendor 归一响应（{vendor, taskId, status?}），业务层只认 taskId，不再猜字段名
            JsonNode root = objectMapper.readTree(resp.body());
            String taskId = root.path("taskId").asText("");
            if (taskId.isBlank()) {
                throw new BusinessException(50201, "视频创建响应缺少 taskId: " + resp.body());
            }

            if (scene != null) {
                scene.videoTaskId = taskId;
                scene.videoStatus = "generating";
                sceneBridge.updateScene(scene);
            }

            return taskId;
        } catch (Exception e) {
            if (scene != null) {
                scene.videoStatus = "failed";
                sceneBridge.updateScene(scene);
            }
            throw new BusinessException(50201, "AI 视频生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 轮询视频任务状态，完成后自动下载视频文件（统一走网关）。
     * 网关统一响应 {taskId, status, progress?, error?}（status: processing/succeeded/failed）：
     *   succeeded → GET 网关下载端点拿视频流 → 本地转存 uploads/videos → {status:completed, videoUrl}
     *   failed   → 透传 error
     *   processing → 返回 processing（透传 progress）
     */
    @Override
    public Map<String, String> pollVideoTask(String taskId) {
        return videoPollExecutor.pollVideoTask(taskId);
    }

    private String extractFilename(String urlPath) {
        int idx = urlPath.lastIndexOf('/');
        return idx >= 0 ? urlPath.substring(idx + 1) : urlPath;
    }

    /** 参考素材转换：已内联/绝对 URL 原样返回；`/api/files/**` 相对路径经主后端绝对 URL 拉取 → 网关上传换 mm_file://；
     *  其余本地文件 ≤10MB 转 data URI，>10MB 经网关上传代理换 mm_file:// */
    private String resolveReferenceUrl(String urlPath, String type) {
        if (urlPath == null || urlPath.isBlank()) return null;
        if (urlPath.startsWith("data:") || urlPath.startsWith("http") || urlPath.startsWith("mm_file://")) {
            return urlPath;
        }
        String filename = extractFilename(urlPath);
        // 主后端相对路径（/api/files/...）：取主后端绝对 URL → 下载字节 → 网关 /v1/files/upload 换 mm_file://。
        // 资产/图片 URL 是主后端相对路径，本机 uploads 未必有对应文件（此前回退本地文件找不到 → 丢图 → t2va）；
        // 统一经网关文件上传换 mm_file://，免除对本地共享盘的依赖，也避免 base64 膨胀爆 64MB 请求体（2026-08-27 方案 2）。
        if (urlPath.startsWith("/api/files/")) {
            String abs = getRefBaseUrl() + urlPath;
            byte[] bytes = downloadBytes(abs);
            if (bytes == null) {
                log.warn("[参考图] /api/files/** 下载失败 -> 丢图(该素材将不进请求体): {}", abs);
                return null;
            }
            log.info("[参考图] /api/files/** 下载成功: {} ({} 字节)", abs, bytes.length);
            // v2 契约 image_url.url 支持 data URI：≤10MB 直接内联（免上传、不撞 purpose 报错，2026-08-27）
            if (bytes.length <= 10 * 1024 * 1024) {
                String dataUri = "data:" + FileStorageService.contentType(filename) + ";base64,"
                        + Base64.getEncoder().encodeToString(bytes);
                log.info("[参考图] 小文件≤10MB 转 data URI 内联: {} ({} 字节)", filename, bytes.length);
                return dataUri;
            }
            // 大文件>10MB：经网关 /v1/files/upload 换 mm_file://（避免 base64 膨胀爆 64MB 请求体）
            String mm = uploadReferenceToGateway(filename, bytes);
            log.info("[参考图] 大文件>10MB 经网关上传换 mm_file://: {} -> {}", filename, mm);
            return mm;
        }
        // 其余相对对象路径：≤10MB 转 data URI，>10MB 经网关上传换 mm_file://
        byte[] fileBytes = switch (type) {
            case "video" -> fileStorageService.readVideo(filename);
            case "audio" -> fileStorageService.readAudio(filename);
            default -> fileStorageService.readImage(filename);
        };
        if (fileBytes == null) {
            log.warn("参考素材对象不存在: {}", filename);
            return null;
        }
        if (fileBytes.length <= 10 * 1024 * 1024) {
            String mime = FileStorageService.contentType(filename);
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(fileBytes);
        }
        return uploadReferenceToGateway(filename, fileBytes);
    }

    /** 参考素材拉取基准地址（agent-core = 主后端 storyboard.internal-url） */
    private String getRefBaseUrl() {
        return config.getStoryboardInternalUrl();
    }

    /** 下载远端字节（参考素材经主后端 `/api/files/**` 拉取）；失败返回 null */
    private byte[] downloadBytes(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60)).GET().build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200 && resp.body().length > 0) return resp.body();
            log.warn("下载参考素材失败 {}: {}", url, resp.statusCode());
            return null;
        } catch (Exception e) {
            log.warn("下载参考素材异常 {}: {}", url, e.getMessage());
            return null;
        }
    }

    /** 参考素材经网关 `/v1/files/upload` 换 mm_file://（octet-stream 发 multipart 字节流，网关重建 boundary） */
    private String uploadReferenceToGateway(String filename, byte[] bytes) {
        try {
            MultipartBuilder mp = new MultipartBuilder()
                    .file("file", filename, FileStorageService.contentType(filename), bytes);
            byte[] bodyBytes = mp.build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.getGatewayBaseUrl() + "/v1/files/upload"))
                    .header("Content-Type", "application/octet-stream")
                    .header("Authorization", "Bearer " + config.getGatewayApiKey())
                    .timeout(Duration.ofSeconds(300))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new BusinessException(50201, "参考素材上传失败: " + resp.statusCode() + " " + resp.body());
            }
            // 网关响应 {file_id: "mm_file://..."} 已带前缀，原样返回（勿再拼 mm_file://）
            String fileId = objectMapper.readTree(resp.body()).path("file_id").asText("");
            if (fileId.isBlank()) {
                throw new BusinessException(50201, "参考素材上传响应缺少 file_id: " + resp.body());
            }
            return fileId;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50201, "参考素材上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建视频任务（新版：支持带 role 的参考图列表）。
     * <p>
     * 每个参考图可指定用途（首帧/尾帧/参考图），由适配器负责转换为各模型特定格式。
     * <p>
     * 将 ReferenceImage 列表转换为网关期望的格式（{url, role} 对象数组），传递给 LLM 网关。
     */
    @Override
    public String createVideoTask(String sceneId, String prompt, String alias,
                                   String resolution, String size, String aspectRatio,
                                   Integer duration, String negativePrompt, Long seed,
                                   List<ReferenceImage> referenceImages, String generatedImageUrl,
                                   List<String> referenceVideos, List<String> referenceAudios,
                                   boolean isReferenceImageWithRole) {
        SceneView scene = sceneId != null ? sceneBridge.getScene(sceneId) : null;
        if (sceneId != null && scene == null) throw new BusinessException(40401, "资源不存在: " + sceneId);

        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(40001, "视频生成 prompt 不能为空");
        }

        String actualModel = alias != null
                ? config.getVideoModelAliasMap().getOrDefault(alias, alias)
                : (scene != null && scene.videoModel != null && !scene.videoModel.isBlank())
                        ? scene.videoModel
                        : gatewayModelService.getDefaultVideoModel();

        // 参数：显式传入 > 分镜覆盖 > 网关兜底默认
        String effResolution = resolution != null ? resolution
                : (scene != null && scene.videoResolution != null && !scene.videoResolution.isBlank())
                        ? scene.videoResolution : null;
        String effAspectRatio = aspectRatio != null ? aspectRatio
                : (scene != null && scene.videoAspectRatio != null && !scene.videoAspectRatio.isBlank())
                        ? scene.videoAspectRatio : null;
        Integer effDuration = duration != null ? duration
                : (scene != null && scene.duration != null && scene.duration > 0)
                        ? scene.duration : null;

        try {
            // 构建统一请求体
            Map<String, Object> body = new HashMap<>();
            body.put("model", actualModel);
            body.put("prompt", prompt);
            if (size != null) body.put("size", size);
            if (effResolution != null) body.put("resolution", effResolution);
            if (effAspectRatio != null) body.put("aspectRatio", effAspectRatio);
            if (effDuration != null) body.put("duration", effDuration);
            if (negativePrompt != null && !negativePrompt.isEmpty()) {
                body.put("negativePrompt", negativePrompt);
            }
            if (seed != null) {
                body.put("seed", seed);
            }

            // 将 ReferenceImage 列表转换为带 role 的格式
            List<Map<String, Object>> refImageList = new ArrayList<>();
            if (referenceImages != null) {
                for (ReferenceImage ref : referenceImages) {
                    if (ref.url() == null || ref.url().isBlank()) continue;
                    String resolvedUrl = resolveReferenceUrl(ref.url(), "image");
                    if (resolvedUrl != null) {
                        Map<String, Object> refMap = new HashMap<>();
                        refMap.put("url", resolvedUrl);
                        refMap.put("role", ref.role() != null ? ref.role().name() : "REFERENCE");
                        refImageList.add(refMap);
                    }
                }
            }

            // 处理 generatedImageUrl 作为首帧
            if (generatedImageUrl != null && !generatedImageUrl.isBlank()) {
                String resolvedUrl = resolveReferenceUrl(generatedImageUrl, "image");
                if (resolvedUrl != null) {
                    Map<String, Object> refMap = new HashMap<>();
                    refMap.put("url", resolvedUrl);
                    refMap.put("role", "FIRST_FRAME");
                    refImageList.add(0, refMap); // 首帧放在列表开头
                }
            }

            if (!refImageList.isEmpty()) {
                body.put("referenceImages", refImageList);
            }

            // 参考视频和音频（保持原有逻辑）
            List<String> refVideos = new ArrayList<>();
            List<String> refAudios = new ArrayList<>();
            if (referenceVideos != null) {
                for (String v : referenceVideos) { String u = resolveReferenceUrl(v, "video"); if (u != null) refVideos.add(u); }
            }
            if (referenceAudios != null) {
                for (String a : referenceAudios) { String u = resolveReferenceUrl(a, "audio"); if (u != null) refAudios.add(u); }
            }
            if (!refVideos.isEmpty()) body.put("referenceVideos", refVideos);
            if (!refAudios.isEmpty()) body.put("referenceAudios", refAudios);

            log.info("[参考图-Role] 创建视频: model={}, refImageCount={}, hasFirstFrame={}",
                    actualModel, refImageList.size(),
                    refImageList.stream().anyMatch(m -> "FIRST_FRAME".equals(m.get("role"))));

            String jsonBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getGatewayBaseUrl() + "/v1/videos"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getGatewayApiKey())
                .timeout(Duration.ofSeconds(config.getVideoRequestTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                throw new BusinessException(50201, "Video API returned " + resp.statusCode() + ": " + resp.body());
            }

            JsonNode root = objectMapper.readTree(resp.body());
            String taskId = root.path("taskId").asText("");
            if (taskId.isBlank()) {
                throw new BusinessException(50201, "视频创建响应缺少 taskId: " + resp.body());
            }

            if (scene != null) {
                scene.videoTaskId = taskId;
                scene.videoStatus = "generating";
                sceneBridge.updateScene(scene);
            }
            log.info("视频任务已创建(带Role): taskId={}, model={}", taskId, actualModel);
            return taskId;
        } catch (Exception e) {
            if (scene != null) {
                scene.videoStatus = "failed";
                sceneBridge.updateScene(scene);
            }
            throw new BusinessException(50201, "AI 视频生成失败: " + e.getMessage(), e);
        }
    }

}



