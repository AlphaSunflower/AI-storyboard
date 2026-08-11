package com.llmgateway.service;

/**
 * 图改图（edits）网关服务：接收 OpenAI 原生 multipart 字节流，
 * 从字节流解析 model 字段 → 复用 model_route 渠道路由 → 原样透传上游。
 *
 * 行为与 GatewayRoutingService 完全一致：
 *   429/5xx → 切下一渠道；4xx → 透传错误体；全渠道失败 → 50301；每次调用落 call_log
 */
public interface ImageEditService {

    /**
     * 转发图改图请求。
     *
     * @param multipartBody  原始 multipart 字节流（含 model/prompt 字段 + image 文件 part）
     * @param contentType    调用方 Content-Type（仅用于记录；转发头由本方法从 body 提取 boundary 重建——
     *                       调用方可能用 octet-stream 规避 Spring multipart 解析器）
     * @return 路由结果（上游状态码 + 响应体），与 GatewayRoutingService.route 语义一致：
     *         200 时 body 含 data[0].b64_json；4xx 时 body 为上游错误体并透传真实状态码
     */
    RouteResult edit(byte[] multipartBody, String contentType);
}
