package com.storyboard.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * AI 配置属性类 —— 所有 Laozhang API 相关配置的单一数据源。
 *
 * 对应 application.yml 中的 {@code ai.laozhang.*} 配置段。
 * 模型名称、API 端点路径、默认参数等全部集中在此，不再散落于 Service 代码中。
 */
@ConfigurationProperties(prefix = "ai.laozhang")
public class AiConfigProperties {

    /** JSON 解析器（用于解析 video-model-aliases 等 JSON 字符串配置） */
    private static final ObjectMapper json = new ObjectMapper();

    // ═══════════════════════════════════════════════════════════
    //  密钥（无默认值，必须通过环境变量或 application-local.yml 提供）
    // ═══════════════════════════════════════════════════════════

    /** Laozhang API 通用密钥 */
    private String apiKey;
    /** Sora2 专用 API 密钥（某些模型需要独立计费） */
    private String sora2OfficialApiKey;
    /** Dify Agent API 密钥 */
    private String difyApiKey;

    // ═══════════════════════════════════════════════════════════
    //  API 基础地址
    // ═══════════════════════════════════════════════════════════

    /** OpenAI 兼容接口基础地址（用于生图、生视频） */
    private String baseUrlOpenai;
    /** Gemini 原生接口基础地址（用于 Gemini 系列模型生图） */
    private String baseUrlGemini;
    /** Chat Completions 接口基础地址（用于脚本生成的 Vision 模型） */
    private String baseUrlVision;
    /** Dify 自托管基础地址（对话代理 /v1/chat-messages 用） */
    private String difyBaseUrl;

    // ═══════════════════════════════════════════════════════════
    //  API 端点路径（相对于 baseUrlOpenai）
    // ═══════════════════════════════════════════════════════════

    /** 图片生成端点 */
    private String endpointImageGenerations = "/images/generations";
    /** 视频任务创建端点 */
    private String endpointVideoCreate = "/videos";
    /** 视频任务状态查询端点（后会拼接 taskId） */
    private String endpointVideoStatus = "/videos/";
    /** 视频任务状态查询回退端点（当主端点不可用时） */
    private String endpointVideoStatusFallback = "/video/generations/";
    /** 图片编辑端点（图改图，multipart 上传） */
    private String endpointImageEdits = "/images/edits";
    /** 视频内容下载端点（后会拼接 taskId + "/content"） */
    private String endpointVideoContent = "/videos/";

    // ═══════════════════════════════════════════════════════════
    //  模型路由规则（控制请求发往哪个端点 / 使用哪把密钥）
    // ═══════════════════════════════════════════════════════════

    /** 走 Gemini 接口的生图模型列表（逗号分隔） */
    private String geminiImageModels = "gemini-3-pro-image-preview";
    /** 使用 Sora2 独立密钥的模型列表（逗号分隔） */
    private String sora2Models = "gpt-image-2-official";
    /**
     * 视频模型别名映射（JSON 格式）。
     * 前端传简称 → 后端通过此映射转为 Laozhang API 实际模型名。
     * 示例：{"veo-3.1-fast":"veo-3.1-fast-generate-preview"}
     */
    private String videoModelAliases = "{\"veo-3.1-fast\":\"veo-3.1-fast-generate-preview\",\"veo-3.1\":\"veo-3.1-generate-preview\"}";

    // ═══════════════════════════════════════════════════════════
    //  默认值（未显式指定时使用）
    // ═══════════════════════════════════════════════════════════

    /** 默认生图模型 */
    private String defaultImageModel = "gpt-image-2";
    /** 默认脚本生成模型（Vision） */
    private String defaultVisionModel = "gemini-3-flash-preview";
    /** 默认生成图片尺寸（OpenAI 格式：宽x高） */
    private String defaultImageSize = "1024x1024";
    /** 默认生成视频时长（秒，字符串格式由 Laozhang API 决定） */
    private String defaultVideoDuration = "8";
    /** 默认生成视频分辨率 */
    private String defaultVideoResolution = "720p";
    /** 默认生成视频尺寸 */
    private String defaultVideoSize = "1280x720";
    /** 默认生成视频宽高比 */
    private String defaultVideoAspectRatio = "16:9";

    // ═══════════════════════════════════════════════════════════
    //  文件存储路径
    // ═══════════════════════════════════════════════════════════

    /** 视频文件本地存储目录 */
    private String videoUploadDir = "uploads/videos";
    /** 视频文件扩展名 */
    private String videoFileExtension = ".mp4";
    /** 视频文件对外访问 URL 前缀 */
    private String videoUrlPrefix = "/api/files/videos/";

    // ═══════════════════════════════════════════════════════════
    //  轮询参数（用于异步视频生成任务）
    // ═══════════════════════════════════════════════════════════

    /** 任务状态轮询间隔（毫秒） */
    private long pollIntervalMs = 5000;
    /** 任务状态轮询超时（毫秒） */
    private long pollTimeoutMs = 600000;

    // ═══════════════════════════════════════════════════════════
    //  标准 Getter / Setter（Spring Boot 配置绑定需要）
    // ═══════════════════════════════════════════════════════════

    // ── 密钥 ──
    public String getApiKey() { return apiKey; }
    public void setApiKey(String s) { this.apiKey = s; }

    public String getSora2OfficialApiKey() { return sora2OfficialApiKey; }
    public void setSora2OfficialApiKey(String s) { this.sora2OfficialApiKey = s; }

    public String getDifyApiKey() { return difyApiKey; }
    public void setDifyApiKey(String s) { this.difyApiKey = s; }

    // ── 基础地址 ──
    public String getBaseUrlOpenai() { return baseUrlOpenai; }
    public void setBaseUrlOpenai(String s) { this.baseUrlOpenai = s; }

    public String getBaseUrlGemini() { return baseUrlGemini; }
    public void setBaseUrlGemini(String s) { this.baseUrlGemini = s; }

    public String getBaseUrlVision() { return baseUrlVision; }
    public void setBaseUrlVision(String s) { this.baseUrlVision = s; }

    public String getDifyBaseUrl() { return difyBaseUrl; }
    public void setDifyBaseUrl(String s) { this.difyBaseUrl = s; }

    // ── 端点路径 ──
    public String getEndpointImageGenerations() { return endpointImageGenerations; }
    public void setEndpointImageGenerations(String s) { this.endpointImageGenerations = s; }

    public String getEndpointVideoCreate() { return endpointVideoCreate; }
    public void setEndpointVideoCreate(String s) { this.endpointVideoCreate = s; }

    public String getEndpointVideoStatus() { return endpointVideoStatus; }
    public void setEndpointVideoStatus(String s) { this.endpointVideoStatus = s; }

    public String getEndpointVideoStatusFallback() { return endpointVideoStatusFallback; }
    public void setEndpointVideoStatusFallback(String s) { this.endpointVideoStatusFallback = s; }

    public String getEndpointImageEdits() { return endpointImageEdits; }
    public void setEndpointImageEdits(String s) { this.endpointImageEdits = s; }

    public String getEndpointVideoContent() { return endpointVideoContent; }
    public void setEndpointVideoContent(String s) { this.endpointVideoContent = s; }

    // ── 模型路由 ──
    public String getGeminiImageModels() { return geminiImageModels; }
    public void setGeminiImageModels(String s) { this.geminiImageModels = s; }

    public String getSora2Models() { return sora2Models; }
    public void setSora2Models(String s) { this.sora2Models = s; }

    public String getVideoModelAliases() { return videoModelAliases; }
    public void setVideoModelAliases(String s) { this.videoModelAliases = s; }

    // ── 默认值 ──
    public String getDefaultImageModel() { return defaultImageModel; }
    public void setDefaultImageModel(String s) { this.defaultImageModel = s; }

    public String getDefaultVisionModel() { return defaultVisionModel; }
    public void setDefaultVisionModel(String s) { this.defaultVisionModel = s; }

    public String getDefaultImageSize() { return defaultImageSize; }
    public void setDefaultImageSize(String s) { this.defaultImageSize = s; }

    public String getDefaultVideoDuration() { return defaultVideoDuration; }
    public void setDefaultVideoDuration(String s) { this.defaultVideoDuration = s; }

    public String getDefaultVideoResolution() { return defaultVideoResolution; }
    public void setDefaultVideoResolution(String s) { this.defaultVideoResolution = s; }

    public String getDefaultVideoSize() { return defaultVideoSize; }
    public void setDefaultVideoSize(String s) { this.defaultVideoSize = s; }

    public String getDefaultVideoAspectRatio() { return defaultVideoAspectRatio; }
    public void setDefaultVideoAspectRatio(String s) { this.defaultVideoAspectRatio = s; }

    // ── 文件存储 ──
    public String getVideoUploadDir() { return videoUploadDir; }
    public void setVideoUploadDir(String s) { this.videoUploadDir = s; }

    public String getVideoFileExtension() { return videoFileExtension; }
    public void setVideoFileExtension(String s) { this.videoFileExtension = s; }

    public String getVideoUrlPrefix() { return videoUrlPrefix; }
    public void setVideoUrlPrefix(String s) { this.videoUrlPrefix = s; }

    // ── 轮询 ──
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long l) { this.pollIntervalMs = l; }

    public long getPollTimeoutMs() { return pollTimeoutMs; }
    public void setPollTimeoutMs(long l) { this.pollTimeoutMs = l; }

    // ═══════════════════════════════════════════════════════════
    //  派生辅助方法（惰性解析，避免每次都重新解析）
    // ═══════════════════════════════════════════════════════════

    /** Gemini 生图模型 Set 缓存（从逗号分隔字符串解析） */
    private transient Set<String> geminiImageModelSet;
    /** Sora2 密钥模型 Set 缓存（从逗号分隔字符串解析） */
    private transient Set<String> sora2ModelSet;
    /** 视频模型别名 Map 缓存（从 JSON 字符串解析） */
    private transient Map<String, String> videoModelAliasMap;

    /**
     * 获取 Gemini 接口生图模型集合。
     * 用于判断某个模型是否应走 Gemini 原生接口而非 OpenAI 兼容接口。
     */
    public Set<String> getGeminiImageModelSet() {
        if (geminiImageModelSet == null) {
            geminiImageModelSet = Set.of(geminiImageModels.split("\\s*,\\s*"));
        }
        return geminiImageModelSet;
    }

    /**
     * 获取 Sora2 独立密钥模型集合。
     * 用于判断某个模型是否应使用 {@code sora2OfficialApiKey} 而非通用密钥。
     */
    public Set<String> getSora2ModelSet() {
        if (sora2ModelSet == null) {
            sora2ModelSet = Set.of(sora2Models.split("\\s*,\\s*"));
        }
        return sora2ModelSet;
    }

    /**
     * 获取视频模型别名映射表。
     * Key = 前端传的简称，Value = Laozhang API 实际模型名。
     * 解析失败时返回空 Map（不会抛异常，保障启动不中断）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getVideoModelAliasMap() {
        if (videoModelAliasMap == null) {
            try {
                videoModelAliasMap = json.readValue(videoModelAliases,
                    new TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                videoModelAliasMap = Collections.emptyMap();
            }
        }
        return videoModelAliasMap;
    }
}
