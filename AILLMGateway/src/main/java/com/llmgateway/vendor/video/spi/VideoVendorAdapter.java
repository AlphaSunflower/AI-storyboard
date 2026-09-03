package com.llmgateway.vendor.video.spi;

import com.llmgateway.entity.Channel;
import com.llmgateway.vendor.video.dto.VendorCreateInput;
import com.llmgateway.vendor.video.dto.MinimaxVideoResult;
import com.llmgateway.vendor.video.dto.VideoVendorResult;
import com.llmgateway.vendor.video.model.ReferenceImage;

import java.net.http.HttpRequest;
import java.util.List;

/**
 * 视频厂商适配器 SPI —— 统一规则接口（规则统一，数据不统一）。
 * <p>
 * 一个契约对应一种「上游视频协议」，同一协议的多家厂商复用同一个适配器实现。
 * 新增协议 = 新建一个实现类 + 在 {@link VideoVendorRegistry} 按 {@link #channelType()} 注册，
 * 调用方（VideoGatewayServiceImpl，纯编排）无需任何改动。
 * <p>
 * 每个适配器独立封装：请求载荷构建（JSON content 数组 / multipart）、创建/轮询响应解析（task_id / id / taskId、
 * 状态字符串归一）、以及该协议自身的重试策略（重试次数 / 可重试判定 / 退避）。
 */
public interface VideoVendorAdapter {

    /** 该适配器服务的渠道类型（注册表 key，来自 {@link Channel#getType()}） */
    String channelType();

    // ── 该协议自身的创建重试策略（供编排器做泛型重试循环）──

    /** 创建最大尝试次数 */
    int maxCreateAttempts();

    /** 给定上游状态码+响应体，是否值得重试（如 minimax: 429/5xx；laozhang: 404 或 5xx 且 body 含特定故障标记） */
    boolean isRetryableCreate(int status, String body);

    /** 第 attempt 次重试前的退避毫秒数（1 起） */
    long createBackoffMs(int attempt);

    // ── 请求载荷构建（统一入参 → 该协议 HTTP 请求）──

    /** 用统一入参构建该协议的创建请求（含 HTTP 方法/URL/头/体/超时），交由编排器发送 */
    HttpRequest buildCreateRequest(VendorCreateInput in, Channel channel, String apiKey);

    /**
     * 构建创建请求（新版：支持带 role 的参考图列表）。
     * <p>
     * 默认实现：将 {@link ReferenceImage} 列表转为 {@link VendorCreateInput} 的 referenceImages 字段，
     * 然后委托给旧版 {@link #buildCreateRequest(VendorCreateInput, Channel, String)}（向后兼容）。
     * <p>
     * 各模型适配器可覆盖此方法以实现 role 语义的精确映射（如 MiniMax 的 first_frame / last_frame / reference_image）。
     *
     * @param in              统一入参
     * @param channel         渠道配置
     * @param apiKey          API 密钥
     * @param referenceImages 带 role 的参考图列表（可能为空）
     * @return HTTP 请求
     */
    default HttpRequest buildCreateRequest(VendorCreateInput in, Channel channel, String apiKey,
                                           List<ReferenceImage> referenceImages) {
        // 默认实现：忽略 role（旧模型无 role 语义），将入参原样交给三参重载
        return buildCreateRequest(in, channel, apiKey);
    }

    // ── 响应解析（该协议原始响应 → 归一结果）──

    /** 解析创建响应 —— 从 task_id / id / taskId 稳取 taskId（不再让调用方猜字段名） */
    VideoVendorResult parseCreate(String rawBody);

    /** 解析轮询响应 —— 完成状态映射归一（succeeded/failed/processing）与错误/进度/直链提取 */
    VideoVendorResult parsePoll(String rawBody, String taskId, Channel channel, String apiKey);

    // ======== cancel / delete task (optional vendor capability) ========

    /**
     * Build an upstream cancel/delete request (DELETE). Return null to signal that
     * this vendor has no cancel capability (gateway will throw a friendly error).
     *
     * @param taskId  upstream task id
     * @param channel channel
     * @param apiKey  decrypted api key
     * @return cancel request; null when unsupported
     */
    default HttpRequest buildCancelRequest(String taskId, Channel channel, String apiKey) {
        return null;
    }

    /**
     * Parse a cancel/delete response and normalize it into a vendor result.
     * Upstream action/status values map to: cancelled -> cancelled, deleted -> deleted, otherwise lowercased passthrough.
     *
     * @param rawBody upstream response body
     * @param taskId  task id
     * @param channel channel
     * @param apiKey  decrypted api key
     * @return normalized result
     */
    default VideoVendorResult parseCancel(String rawBody, String taskId, Channel channel, String apiKey) {
        return new MinimaxVideoResult("unknown", taskId, "unknown", null, null, null, null, null);
    }
}


