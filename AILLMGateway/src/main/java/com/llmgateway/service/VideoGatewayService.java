package com.llmgateway.service;

import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 视频网关：统一 /v1/videos 创建/轮询/下载，按 model 路由到 Laozhang（multipart）
 * 或 MiniMax（JSON content 数组）渠道。下载由网关流式代理（业务只认 /v1/videos/{taskId}/content）。
 */
public interface VideoGatewayService {

    /**
     * 创建视频任务。请求体（OpenAI 风格统一格式）：
     * {model, prompt, size?, resolution?, aspectRatio?, duration?, negativePrompt?, seed?, imageUrl?}
     * imageUrl：图生视频首帧（data URI 或 http URL，业务侧已把本地图转 data URI）
     */
    VideoResult create(String requestBody);

    /**
     * 轮询视频状态。taskId 反查渠道：查 call_log 最新一条该 model 的记录不够精确，
     * 改为按 taskId 前缀存 channel 标识：简化方案——轮询时遍历该 model 的路由渠道逐个尝试，
     * 命中 200 即返回。
     */
    VideoResult poll(String taskId);

    /** 视频下载：流式代理（业务只认本端点，永不接触上游 URL/Key） */
    ResponseEntity<StreamingResponseBody> download(String taskId);
}
