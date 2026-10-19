package com.llmgateway.vendor.route.impl;

import com.llmgateway.entity.Channel;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.UpstreamClient;
import com.llmgateway.vendor.route.ChatImageVendorAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * OpenAI 兼容聊天/生图协议适配器（channel.type = "openai_compatible"）。
 * <p>
 * 原路径透传：Bearer 换渠道 Key，请求/响应体不改。任何走 openai_compatible 渠道的厂商复用本适配器——同协议复用。
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatRouteAdapter implements ChatImageVendorAdapter {

    private final UpstreamClient upstreamClient;

    @Override
    public String channelType() {
        return "openai_compatible";
    }

    @Override
    public RouteResult forward(String path, String requestBody, Channel channel, String apiKey) {
        return forward(path, requestBody, channel, apiKey, null);
    }

    @Override
    public RouteResult forward(String path, String requestBody, Channel channel, String apiKey, Integer timeoutMs) {
        HttpResponse<String> resp = timeoutMs == null
                ? upstreamClient.postJson(channel.getBaseUrl(), path, apiKey, requestBody)
                : upstreamClient.postJson(channel.getBaseUrl(), path, apiKey, requestBody, timeoutMs);
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
        HttpResponse<java.io.InputStream> resp = timeoutMs == null
                ? upstreamClient.postJsonStream(channel.getBaseUrl(), path, apiKey, requestBody)
                : upstreamClient.postJsonStream(channel.getBaseUrl(), path, apiKey, requestBody, timeoutMs);
        if (resp.statusCode() != 200) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("upstream " + resp.statusCode() + ": " + err);
        }
        try (java.io.InputStream in = resp.body()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                sink.accept(java.util.Arrays.copyOf(buf, n));
            }
        }
    }
}
