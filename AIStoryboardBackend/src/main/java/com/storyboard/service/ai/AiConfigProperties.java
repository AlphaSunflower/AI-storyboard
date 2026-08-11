package com.storyboard.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * AI 配置属性类 —— 所有 Laozhang API 相关配置的单一数据源。
 * <p>
 * 对应 application.yml 中的 {@code ai.laozhang.*} 配置段。
 * 模型名称、API 端点路径、默认参数等全部集中在此，不再散落于 Service 代码中。
 */
@ConfigurationProperties(prefix = "ai.laozhang")
public class AiConfigProperties {

    private static final Logger log = LoggerFactory.getLogger(AiConfigProperties.class);

    /** JSON 解析器（用于解析 video-model-aliases 等 JSON 字符串配置） */
    private static final ObjectMapper json = new ObjectMapper();

    // ═══════════════════════════════════════════════════════════
    //  密钥（无默认值，必须通过环境变量或 application-local.yml 提供）
    // ═══════════════════════════════════════════════════════════

    // ── 密钥 ──
    /** Laozhang API 通用密钥 */
    @Setter
    @Getter
    private String apiKey;
    /** Sora2 专用 API 密钥（某些模型需要独立计费） */
    @Setter
    @Getter
    private String sora2OfficialApiKey;
    /** MiniMax 视频生成 API 密钥（V2 接口 Bearer 鉴权；.env 提供，不提交） */
    @Setter
    @Getter
    private String minimaxApiKey;

    // ═══════════════════════════════════════════════════════════
    //  API 基础地址
    // ═══════════════════════════════════════════════════════════

    // ── 基础地址 ──
    /** OpenAI 兼容接口基础地址（用于生图、生视频） */
    @Setter
    @Getter
    private String baseUrlOpenai;
    /** Gemini 原生接口基础地址（用于 Gemini 系列模型生图） */
    @Setter
    @Getter
    private String baseUrlGemini;
    /** Chat Completions 接口基础地址（用于脚本生成的 Vision 模型） */
    @Setter
    @Getter
    private String baseUrlVision;
    /** MiniMax 视频生成基础地址（V2 接口；国内 api.minimaxi.com，国际 api.minimax.io） */
    @Setter
    @Getter
    private String minimaxBaseUrl = "https://api.minimaxi.com";

    // ═══════════════════════════════════════════════════════════
    //  LLM 网关配置（独立前缀 ai.gateway：chat/文生图统一入口）
    //  ═══════════════════════════════════════════════════════════

    // ── LLM 网关 ──
    /** LLM 网关配置（独立前缀 {@code ai.gateway}，见 {@link Gateway}） */
    @Setter
    @Getter
    private Gateway gateway = new Gateway();

    /**
     * 构造器注入：接收经 {@code @EnableConfigurationProperties} 注册、已绑定
     * {@code ai.gateway.*} 的独立 Gateway bean。
     * 嵌套类上的 {@code @ConfigurationProperties} 注解只在它被独立注册为 bean 时才生效；
     * 若仅靠字段绑定，Binder 会按父类前缀 {@code ai.laozhang.gateway.*} 查找（yml 不存在）
     * → 字段保留默认值、apiKey=null → 网关请求发出 "Bearer null"。
     * <p>
     * {@code @Autowired} 必须有：Spring Boot 4 会把"唯一构造器"类当作构造器绑定目标，
     * 若不加 @Autowired，Gateway 参数会被当配置项从父前缀绑定（→ null），而非注入 bean。
     */
    @Autowired
    public AiConfigProperties(Gateway gateway) {
        this.gateway = gateway;
    }

    // ═══════════════════════════════════════════════════════════
    //  API 端点路径（相对于 baseUrlOpenai）
    // ═══════════════════════════════════════════════════════════

    // ── 端点路径 ──
    /** 图片生成端点 */
    @Setter
    @Getter
    private String endpointImageGenerations = "/images/generations";
    /** 视频任务创建端点 */
    @Setter
    @Getter
    private String endpointVideoCreate = "/videos";
    /** 视频任务状态查询端点（后会拼接 taskId） */
    @Setter
    @Getter
    private String endpointVideoStatus = "/videos/";
    /** 视频任务状态查询回退端点（当主端点不可用时） */
    @Setter
    @Getter
    private String endpointVideoStatusFallback = "/video/generations/";
    /** 图片编辑端点（图改图，multipart 上传） */
    @Setter
    @Getter
    private String endpointImageEdits = "/images/edits";
    /** 视频内容下载端点（后会拼接 taskId + "/content"） */
    @Setter
    @Getter
    private String endpointVideoContent = "/videos/";

    // ═══════════════════════════════════════════════════════════
    //  模型路由规则（控制请求发往哪个端点 / 使用哪把密钥）
    // ═══════════════════════════════════════════════════════════

    // ── 模型路由 ──
    /** 走 Gemini 接口的生图模型列表（逗号分隔） */
    @Setter
    @Getter
    private String geminiImageModels = "gemini-3-pro-image-preview";
    /** 使用 Sora2 独立密钥的模型列表（逗号分隔） */
    @Setter
    @Getter
    private String sora2Models = "gpt-image-2-official";
    /**
     * 视频模型别名映射（JSON 格式）。
     * 前端传简称 → 后端通过此映射转为 Laozhang API 实际模型名。
     * 示例：{"veo-3.1-fast":"veo-3.1-fast-generate-preview"}
     */
    @Setter
    @Getter
    private String videoModelAliases = "{\"veo-3.1-fast\":\"veo-3.1-fast-generate-preview\",\"veo-3.1\":\"veo-3.1-generate-preview\"}";

    // ═══════════════════════════════════════════════════════════
    //  默认值（未显式指定时使用）
    // ═══════════════════════════════════════════════════════════

    // ── 默认值 ──
    /** 默认生图模型 */
    @Setter
    @Getter
    private String defaultImageModel = "gpt-image-2";
    /** 默认图生图/图改图模型（edits 分支；独立于文生图，可经环境变量 DEFAULT_IMAGE_EDIT_MODEL 填写） */
    @Setter
    @Getter
    private String defaultImageEditModel = "gpt-image-2";
    /** 默认脚本生成模型（Vision） */
    @Setter
    @Getter
    private String defaultVisionModel = "gemini-3-flash-preview";
    /** 默认生成图片尺寸（OpenAI 格式：宽x高） */
    @Setter
    @Getter
    private String defaultImageSize = "1024x1024";
    /** 默认生成视频时长（秒，字符串格式由 Laozhang API 决定） */
    @Setter
    @Getter
    private String defaultVideoDuration = "8";
    /** 默认生成视频分辨率 */
    @Setter
    @Getter
    private String defaultVideoResolution = "720p";
    /** 默认生成视频尺寸 */
    @Setter
    @Getter
    private String defaultVideoSize = "1280x720";
    /** 默认生成视频宽高比 */
    @Setter
    @Getter
    private String defaultVideoAspectRatio = "16:9";

    // ── 视频生成 Provider（Laozhang / MiniMax 双通道）──
    // ── 视频 Provider ──
    /** 视频生成通道：minimax（默认）| laozhang（保留可切回） */
    @Setter
    @Getter
    private String videoProvider = "minimax";
    /** MiniMax 视频生成模型 */
    @Setter
    @Getter
    private String minimaxVideoModel = "MiniMax-H3";
    /** MiniMax 视频生成分辨率档（768P | 2K） */
    @Setter
    @Getter
    private String minimaxVideoResolution = "768P";

    // ═══════════════════════════════════════════════════════════
    //  文件存储路径
    // ═══════════════════════════════════════════════════════════

    // ── 文件存储 ──
    /** 视频文件本地存储目录 */
    @Setter
    @Getter
    private String videoUploadDir = "uploads/videos";
    /** 视频文件扩展名 */
    @Setter
    @Getter
    private String videoFileExtension = ".mp4";
    /** 视频文件对外访问 URL 前缀 */
    @Setter
    @Getter
    private String videoUrlPrefix = "/api/files/videos/";

    // ═══════════════════════════════════════════════════════════
    //  轮询参数（用于异步视频生成任务）
    // ═══════════════════════════════════════════════════════════

    // ── 轮询 ──
    /** 任务状态轮询间隔（毫秒） */
    @Setter
    @Getter
    private long pollIntervalMs = 5000;
    /** 任务状态轮询超时（毫秒） */

    private long pollTimeoutMs = 600000;

    // ═══════════════════════════════════════════════════════════
    //  标准 Getter / Setter（Spring Boot 配置绑定需要）
    // ═══════════════════════════════════════════════════════════


    /** 网关基础地址（chat/文生图统一入口；对应 ai.gateway.base-url） */
    public String getGatewayBaseUrl() { return gateway == null ? null : gateway.getBaseUrl(); }

    /** 网关调用密钥（网关 /admin 签发；对应 ai.gateway.api-key） */
    public String getGatewayApiKey() { return gateway == null ? null : gateway.getApiKey(); }

    /**
     * LLM 网关配置 —— 独立前缀 {@code ai.gateway}，与 {@code ai.laozhang} 平级。
     * chat/文生图调用统一经网关转发（edits 图改图保持直连 Laozhang）。
     */
    @Setter
    @Getter
    @ConfigurationProperties(prefix = "ai.gateway")
    public static class Gateway {

        /** 网关基础地址（chat/文生图统一入口，默认本机网关 8083） */
        private String baseUrl = "http://localhost:8083";

        /** 网关调用密钥（在网关 /admin 签发） */
        private String apiKey;

    }

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