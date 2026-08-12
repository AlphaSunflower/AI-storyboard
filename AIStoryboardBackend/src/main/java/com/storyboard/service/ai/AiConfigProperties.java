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
    //  模型路由规则（控制请求发往哪个端点 / 使用哪把密钥）
    // ═══════════════════════════════════════════════════════════

    // ── 模型路由 ──
    /** 走 Gemini 接口的生图模型列表（逗号分隔） */
    @Setter
    @Getter
    private String geminiImageModels = "gemini-3-pro-image-preview";
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

    // ── 视频生成（模型名经网关透传上游）──
    /** MiniMax 视频生成模型 */
    @Setter
    @Getter
    private String minimaxVideoModel = "MiniMax-H3";

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