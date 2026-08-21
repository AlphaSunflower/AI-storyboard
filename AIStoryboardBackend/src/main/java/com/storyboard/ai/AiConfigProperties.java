package com.storyboard.ai;

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
 * AI 配置属性类 —— 模型路由提示 + 文件存储的单一数据源。
 * <p>
 * 对应 application.yml 中的 {@code ai.*} 配置段（与 {@code ai.agent} / {@code ai.gateway} 平级）。
 * 默认模型/参数、模型路由已下沉 LLM 网关（model_route + model_params 表）。
 */
@ConfigurationProperties(prefix = "ai")
public class AiConfigProperties {

    private static final Logger log = LoggerFactory.getLogger(AiConfigProperties.class);

    /** JSON 解析器（用于解析 video-model-aliases 等 JSON 字符串配置） */
    private static final ObjectMapper json = new ObjectMapper();



    // ═══════════════════════════════════════════════════════════
    //  LLM 网关配置（独立前缀 ai.gateway：chat/文生图统一入口）
    //  ═══════════════════════════════════════════════════════════

    // ── LLM 网关 ──
    /** LLM 网关配置（独立前缀 {@code ai.gateway}，构造注入独立 bean，见 {@link Gateway}） */
    @Getter
    private final Gateway gateway;

    /**
     * 构造器注入：接收经 {@code @EnableConfigurationProperties} 注册、已绑定
     * {@code ai.gateway.*} 的独立 Gateway bean。
     * gateway 字段为 final（无 setter），仅由本构造器赋值——父前缀 {@code ai} 的字段绑定
     * 不会用 setter 覆盖它，避免"父前缀绑定"与"构造注入 bean"两实例并存。
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
     * LLM 网关配置 —— 独立前缀 {@code ai.gateway}，与 {@code ai} 下其它配置平级。
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

        /** 兜底文本模型名（网关不可达或未标记 is_default 时使用；默认 deepseek-v4-flash） */
        private String fallbackTextModel = "deepseek-v4-flash";

        /** 兜底文本模型独立 API Key（网关正常时由网关管理密钥，仅直连兜底时使用） */
        private String fallbackTextApiKey;

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