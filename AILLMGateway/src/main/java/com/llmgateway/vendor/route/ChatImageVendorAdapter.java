package com.llmgateway.vendor.route;

import com.llmgateway.entity.Channel;
import com.llmgateway.service.RouteResult;

import java.util.function.Consumer;

/**
 * 聊天 / 生图厂商路由适配器 SPI —— 按渠道类型（channel.type）分发。
 * <p>
 * 一个契约对应一种「上游聊天/生图协议」。新增协议 = 新建实现类 + 在 {@link RouteVendorRegistry} 按
 * {@link #channelType()} 注册，编排器（GatewayRoutingServiceImpl）零改动；同协议复用同一适配器。
 * <p>
 * 结果统一为 OpenAI 风格（上游响应已转格式），故复用 {@link RouteResult}，无需 per-vendor sealed 结果类型。
 */
public interface ChatImageVendorAdapter {

    /** 该适配器服务的渠道类型（注册表 key，来自 {@link Channel#getType()}） */
    String channelType();

    /**
     * 同步转发（chat completions / images/generations）：返回上游状态码 + 已转 OpenAI 格式的响应体。
     *
     * @param path        请求路径（如 /chat/completions、/images/generations），用于区分 chat 与生图
     * @param requestBody OpenAI 风格请求体（JSON 字符串）
     */
    RouteResult forward(String path, String requestBody, Channel channel, String apiKey) throws Exception;

    /**
     * 带超时覆盖的同步转发（渠道/路由配置了 timeout_ms 时使用）。
     * 默认忽略 timeout，兼容不关心超时的适配器。
     */
    default RouteResult forward(String path, String requestBody, Channel channel, String apiKey,
                                Integer timeoutMs) throws Exception {
        return forward(path, requestBody, channel, apiKey);
    }

    /**
     * 流式转发（chat SSE）：逐块回调 sink。不支持 SSE 透传的渠道可降级一次性写回。
     * 编排器负责渠道轮换与客户端中断判定；本方法只做单渠道转发。
     */
    void stream(String path, String requestBody, Channel channel, String apiKey,
                Consumer<byte[]> sink) throws Exception;

    /** 带超时覆盖的流式转发；默认忽略 timeout。 */
    default void stream(String path, String requestBody, Channel channel, String apiKey,
                        Consumer<byte[]> sink, Integer timeoutMs) throws Exception {
        stream(path, requestBody, channel, apiKey, sink);
    }
}
