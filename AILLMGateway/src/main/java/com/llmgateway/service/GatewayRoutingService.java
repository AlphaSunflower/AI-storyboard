package com.llmgateway.service;

/**
 * 路由核心：解析 model → 查 model_route → 取 enabled channel（按 priority 升序）
 * → AES 解密渠道 Key → 按渠道类型转发（透传 / Gemini 转换）。
 * 返回上游状态码 + 响应体（透传，不做二次包装）。
 */
public interface GatewayRoutingService {

    /** 处理 OpenAI 兼容 chat/images 请求（path 为 /chat/completions 或 /images/generations） */
    RouteResult route(String path, String requestBody);

    /**
     * 处理 OpenAI 兼容 chat 流式请求（stream=true）：上游 SSE 响应逐块透传。
     *
     * <p>openai_compatible 渠道原生 SSE 透传；gemini 渠道降级为非流式
     * （转换后一次性返回完整 JSON，调用方按非流式处理，兼容 Spring AI）。
     *
     * @param path          请求路径（/chat/completions）
     * @param requestBody   请求体（含 stream=true）
     * @param sink          响应字节消费者（逐块写入客户端）
     * @throws Exception    上游失败或渠道耗尽时抛出（调用方转错误响应）
     */
    void streamChat(String path, String requestBody, java.util.function.Consumer<byte[]> sink) throws Exception;

    /**
     * 拉取可用模型列表（OpenAI 风格 JSON 字符串 {object:"list",data:[{id,object,type,params}]}）。
     *
     * <p>数据源 model_route：渠道须启用；同一模型多渠道轮换时去重保留首个；type 过滤
     * （image/video/text/vision）；params 从 model_params 按 model_name 组装（能力枚举+默认值）。
     *
     * @param type 模型类型过滤（null/blank 不过滤）
     * @return OpenAI 风格 JSON 字符串（由调用方直接透传响应体）
     */
    String fetchModels(String type);
}
