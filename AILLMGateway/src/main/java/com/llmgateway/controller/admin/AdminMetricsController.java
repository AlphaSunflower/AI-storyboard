package com.llmgateway.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.entity.Channel;
import com.llmgateway.mapper.CallLogMapper;
import com.llmgateway.mapper.ChannelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 可观测指标：近 7 天渠道/模型健康度 + 今日/7 天 token 汇总。
 * 数据源是 call_log（每次尝试一条，含失败），比只看总量更容易定位是哪个渠道在拖后腿。
 */
@RestController
@RequestMapping("/admin/metrics")
@RequiredArgsConstructor
public class AdminMetricsController {

    private final CallLogMapper callLogMapper;
    private final ChannelMapper channelMapper;

    @GetMapping
    public ApiResponse<Map<String, Object>> metrics() {
        Map<String, Channel> channels = channelMapper.selectList(new LambdaQueryWrapper<Channel>())
                .stream().collect(Collectors.toMap(Channel::getId, Function.identity(), (a, b) -> a));
        Map<String, String> latestErrors = callLogMapper.latestChannelErrors().stream()
                .filter(row -> row.get("channel_id") != null)
                .collect(Collectors.toMap(
                        row -> row.get("channel_id").toString(),
                        row -> row.get("error") == null ? "" : row.get("error").toString(),
                        (a, b) -> a));

        List<Map<String, Object>> channelRows = new ArrayList<>();
        for (Map<String, Object> row : callLogMapper.channelStats7d()) {
            String id = str(row.get("channel_id"));
            Channel channel = channels.get(id);
            Map<String, Object> item = baseStats(row);
            item.put("channelId", id);
            item.put("channelName", channel == null ? id : channel.getName());
            item.put("channelType", channel == null ? null : channel.getType());
            item.put("enabled", channel == null ? null : channel.getEnabled());
            String lastError = channel == null ? null : channel.getLastError();
            if (lastError == null || lastError.isBlank()) lastError = latestErrors.get(id);
            item.put("lastError", lastError);
            channelRows.add(item);
        }

        List<Map<String, Object>> modelRows = new ArrayList<>();
        for (Map<String, Object> row : callLogMapper.modelStats7d()) {
            Map<String, Object> item = baseStats(row);
            item.put("model", str(row.get("model")));
            modelRows.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window", "7d");
        result.put("channels", channelRows);
        result.put("models", modelRows);
        result.put("tokensToday", normalizeTokens(callLogMapper.tokenStatsToday()));
        result.put("tokens7d", normalizeTokens(callLogMapper.tokenStats7d()));
        return ApiResponse.ok(result);
    }

    private Map<String, Object> baseStats(Map<String, Object> row) {
        long calls = num(row.get("calls"));
        long success = num(row.get("success_cnt"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("calls", calls);
        item.put("success", success);
        item.put("errors", num(row.get("error_cnt")));
        item.put("successRate", calls == 0 ? 0.0 : success * 1.0 / calls);
        item.put("avgMs", num(row.get("avg_ms")));
        item.put("p95Ms", num(row.get("p95_ms")));
        item.put("tokens", num(row.get("tokens")));
        return item;
    }

    private Map<String, Object> normalizeTokens(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("promptTokens", row == null ? 0L : num(row.get("prompt_tokens")));
        item.put("completionTokens", row == null ? 0L : num(row.get("completion_tokens")));
        item.put("tokens", row == null ? 0L : num(row.get("tokens")));
        return item;
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
