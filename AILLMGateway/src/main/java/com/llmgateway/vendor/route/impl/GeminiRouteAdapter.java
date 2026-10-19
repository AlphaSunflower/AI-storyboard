package com.llmgateway.vendor.route.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.entity.Channel;
import com.llmgateway.service.GeminiFormatConverter;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.UpstreamClient;
import com.llmgateway.vendor.route.ChatImageVendorAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Gemini 聊天/生图协议适配器（channel.type = "gemini"）。
 * <p>
 * 请求转 Gemini 原生 generateContent 格式、Key 走 query 参数；响应转回 OpenAI 格式。
 * 生图 n>1 时循环 N 次再合并 data[]（Gemini 单次只回一张图）。
 */
@Component
@RequiredArgsConstructor
public class GeminiRouteAdapter implements ChatImageVendorAdapter {

    private final UpstreamClient upstreamClient;
    private final GeminiFormatConverter geminiConverter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String channelType() {
        return "gemini";
    }

    @Override
    public RouteResult forward(String path, String requestBody, Channel channel, String apiKey) throws Exception {
        return forward(path, requestBody, channel, apiKey, null);
    }

    @Override
    public RouteResult forward(String path, String requestBody, Channel channel, String apiKey,
                               Integer timeoutMs) throws Exception {
        int imageN = path.contains("images/generations") ? readImageN(requestBody) : 1;
        if (imageN > 1) {
            return forwardGeminiImageMulti(channel, apiKey, requestBody, imageN, timeoutMs);
        }
        String geminiBody = geminiConverter.toGeminiRequest(requestBody);
        HttpResponse<String> resp = timeoutMs == null
                ? upstreamClient.postGemini(channel.getBaseUrl(), apiKey, geminiBody)
                : upstreamClient.postGemini(channel.getBaseUrl(), apiKey, geminiBody, timeoutMs);
        if (resp.statusCode() == 200) {
            return new RouteResult(200, geminiConverter.toOpenAiResponse(resp.body()));
        }
        return new RouteResult(resp.statusCode(), resp.body());
    }

    @Override
    public void stream(String path, String requestBody, Channel channel, String apiKey,
                       Consumer<byte[]> sink) throws Exception {
        stream(path, requestBody, channel, apiKey, sink, null);
    }

    @Override
    public void stream(String path, String requestBody, Channel channel, String apiKey,
                       Consumer<byte[]> sink, Integer timeoutMs) throws Exception {
        // gemini 无原生 SSE 透传路径：降级非流式，转成 OpenAI 格式一次性写回
        String geminiBody = geminiConverter.toGeminiRequest(requestBody);
        HttpResponse<String> resp = timeoutMs == null
                ? upstreamClient.postGemini(channel.getBaseUrl(), apiKey, geminiBody)
                : upstreamClient.postGemini(channel.getBaseUrl(), apiKey, geminiBody, timeoutMs);
        byte[] out = (resp.statusCode() == 200
                ? geminiConverter.toOpenAiResponse(resp.body())
                : resp.body()).getBytes(StandardCharsets.UTF_8);
        sink.accept(out);
    }

    /** Gemini 图片多图：循环 N 次 generateContent，逐次转 OpenAI 格式并合并 data[]；单张失败跳过，全部失败回首个错误 */
    private RouteResult forwardGeminiImageMulti(Channel channel, String apiKey, String requestBody,
                                                int n, Integer timeoutMs) throws Exception {
        String geminiBody = geminiConverter.toGeminiRequest(requestBody);
        ArrayNode merged = objectMapper.createArrayNode();
        int firstErrorStatus = 0;
        String firstErrorBody = null;
        for (int i = 0; i < n; i++) {
            HttpResponse<String> resp = timeoutMs == null
                    ? upstreamClient.postGemini(channel.getBaseUrl(), apiKey, geminiBody)
                    : upstreamClient.postGemini(channel.getBaseUrl(), apiKey, geminiBody, timeoutMs);
            if (resp.statusCode() == 200) {
                JsonNode oai = objectMapper.readTree(geminiConverter.toOpenAiResponse(resp.body()));
                JsonNode data = oai.path("data");
                if (data.isArray()) {
                    for (JsonNode item : data) merged.add(item);
                }
            } else if (firstErrorStatus == 0) {
                firstErrorStatus = resp.statusCode();
                firstErrorBody = resp.body();
            }
        }
        if (merged.isEmpty()) {
            return new RouteResult(firstErrorStatus != 0 ? firstErrorStatus : 502,
                    firstErrorBody != null ? firstErrorBody
                            : "{\"error\":{\"message\":\"Gemini image generation returned no images\"}}");
        }
        ObjectNode out = objectMapper.createObjectNode();
        out.put("created", System.currentTimeMillis() / 1000);
        out.set("data", merged);
        return new RouteResult(200, objectMapper.writeValueAsString(out));
    }

    /** 解析图片请求的 n（缺失/非法默认 1） */
    private int readImageN(String requestBody) {
        try {
            int n = objectMapper.readTree(requestBody).path("n").asInt(1);
            return n > 0 ? n : 1;
        } catch (Exception e) {
            return 1;
        }
    }
}
