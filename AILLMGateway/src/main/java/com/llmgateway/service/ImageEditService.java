package com.llmgateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.CallLog;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/**
 * 图改图（edits）网关服务：接收 OpenAI 原生 multipart 字节流，
 * 从字节流解析 model 字段 → 复用 model_route 渠道路由 → 原样透传上游。
 *
 * 行为与 GatewayRoutingService 完全一致：
 *   429/5xx → 切下一渠道；4xx → 透传错误体；全渠道失败 → 50301；每次调用落 call_log
 */
@Service
public class ImageEditService {

    private static final Logger log = LoggerFactory.getLogger(ImageEditService.class);

    /** 上游 edits 端点路径（openai_compatible 渠道统一使用） */
    private static final String EDIT_PATH = "/v1/images/edits";

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final KeyService keyService;
    private final UpstreamClient upstreamClient;
    private final CallLogService callLogService;

    public ImageEditService(ModelRouteMapper routeMapper,
                            ChannelMapper channelMapper,
                            KeyService keyService,
                            UpstreamClient upstreamClient,
                            CallLogService callLogService) {
        this.routeMapper = routeMapper;
        this.channelMapper = channelMapper;
        this.keyService = keyService;
        this.upstreamClient = upstreamClient;
        this.callLogService = callLogService;
    }

    /**
     * 转发图改图请求。
     *
     * @param multipartBody  原始 multipart 字节流（含 model/prompt 字段 + image 文件 part）
     * @param contentType    原 Content-Type（含 boundary）
     * @return 路由结果（上游状态码 + 响应体），与 GatewayRoutingService.route 语义一致：
     *         200 时 body 含 data[0].b64_json；4xx 时 body 为上游错误体并透传真实状态码
     */
    public GatewayRoutingService.RouteResult edit(byte[] multipartBody, String contentType) {
        long start = System.currentTimeMillis();
        String model = null;
        String channelId = null;
        try {
            // 1. 从 multipart 字节流轻量解析 model 字段（name="model" part 的 body）
            model = parseModelField(multipartBody);
            if (model == null || model.isBlank()) throw new BusinessException(40001, "model 不能为空");

            // 2. 查该模型的所有路由（一个模型可指向多个渠道，按 priority 轮换）
            List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                    .eq(ModelRoute::getModelName, model));
            if (routes == null || routes.isEmpty()) {
                throw new BusinessException(40401, "no route for model: " + model);
            }

            // 3. 候选渠道（路由指向的 enabled 渠道，按 priority 升序）
            List<Channel> candidates = routes.stream()
                    .map(r -> channelMapper.selectById(r.getChannelId()))
                    .filter(c -> c != null && Boolean.TRUE.equals(c.getEnabled()))
                    .sorted(Comparator.comparingInt(c -> c.getPriority() == null ? 0 : c.getPriority()))
                    .toList();
            if (candidates.isEmpty()) {
                throw new BusinessException(50301, "no available channel for model: " + model);
            }

            // 4. 逐个渠道尝试（失败切下一个）
            for (Channel channel : candidates) {
                try {
                    channelId = channel.getId();
                    String apiKey = keyService.decrypt(channel.getApiKey());
                    HttpResponse<String> resp = upstreamClient.postMultipart(
                            channel.getBaseUrl(), EDIT_PATH, apiKey, contentType, multipartBody);
                    int status = resp.statusCode();
                    if (status >= 400) {
                        String error = upstreamClient.extractError(resp.body());
                        log.warn("渠道 {} 返回 {}: {}", channel.getName(), status, error);
                        // 429/5xx 尝试下一个渠道；其余 4xx 业务错误直接透传（带上游真实状态码，避免被 200 包装致 Backend 误判成功）
                        if (status != 429 && status < 500) {
                            callLogService.log(model, channelId, "error",
                                    System.currentTimeMillis() - start, error, null, null);
                            return new GatewayRoutingService.RouteResult(status, resp.body());
                        }
                        continue;
                    }
                    callLogService.log(model, channelId, "success",
                            System.currentTimeMillis() - start, null, null, null);
                    return new GatewayRoutingService.RouteResult(status, resp.body());
                } catch (BusinessException be) {
                    throw be;
                } catch (Exception e) {
                    log.warn("渠道 {} 调用异常: {}", channel.getName(), e.getMessage());
                }
            }
            throw new BusinessException(50301, "all channels failed for model: " + model);
        } catch (BusinessException be) {
            callLogService.log(model, channelId, "error",
                    System.currentTimeMillis() - start, be.getMessage(), null, null);
            throw be;
        } catch (Exception e) {
            callLogService.log(model, channelId, "error",
                    System.currentTimeMillis() - start, e.getMessage(), null, null);
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    /**
     * 从 multipart 字节流中提取 name="model" 字段的值。
     * 轻量字节级解析（不引入 multipart 解析库）：
     *   定位 name="model" → 其后跟随 \r\n\r\n → 读取直到下一个 \r\n
     */
    private String parseModelField(byte[] body) {
        String text = new String(body, StandardCharsets.ISO_8859_1);  // multipart 二进制安全，逐字节映射
        String marker = "name=\"model\"";
        int idx = text.indexOf(marker);
        if (idx < 0) return null;
        int headerEnd = text.indexOf("\r\n\r\n", idx);
        if (headerEnd < 0) return null;
        int valueStart = headerEnd + 4;
        int valueEnd = text.indexOf("\r\n", valueStart);
        if (valueEnd < 0) return null;
        return text.substring(valueStart, valueEnd).trim();
    }
}
