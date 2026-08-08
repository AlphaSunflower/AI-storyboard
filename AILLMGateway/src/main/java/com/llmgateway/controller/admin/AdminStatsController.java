package com.llmgateway.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.GatewayApiKey;
import com.llmgateway.mapper.AdminUserMapper;
import com.llmgateway.mapper.CallLogMapper;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.GatewayApiKeyMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台统计：单端点聚合仪表盘数据。
 * 调用状态口径与 CallLogService 落库一致：'success' 计成功、'error' 计失败，'created'（视频任务进行中）不计入成功/失败。
 */
@RestController
@RequestMapping("/admin/stats")
public class AdminStatsController {

    private final ChannelMapper channelMapper;
    private final ModelRouteMapper routeMapper;
    private final GatewayApiKeyMapper apiKeyMapper;
    private final AdminUserMapper adminUserMapper;
    private final CallLogMapper callLogMapper;

    public AdminStatsController(ChannelMapper channelMapper, ModelRouteMapper routeMapper,
                                GatewayApiKeyMapper apiKeyMapper, AdminUserMapper adminUserMapper,
                                CallLogMapper callLogMapper) {
        this.channelMapper = channelMapper;
        this.routeMapper = routeMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.adminUserMapper = adminUserMapper;
        this.callLogMapper = callLogMapper;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 渠道：总数 + 启用数（selectCount 自动带 @TableLogic 过滤）
        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put("total", channelMapper.selectCount(null));
        channels.put("enabled", channelMapper.selectCount(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getEnabled, true)));
        result.put("channels", channels);

        // 模型路由：总数
        Map<String, Object> routes = new LinkedHashMap<>();
        routes.put("total", routeMapper.selectCount(null));
        result.put("routes", routes);

        // 业务调用 Key：总数 + 启用数
        Map<String, Object> apiKeys = new LinkedHashMap<>();
        apiKeys.put("total", apiKeyMapper.selectCount(null));
        apiKeys.put("enabled", apiKeyMapper.selectCount(new LambdaQueryWrapper<GatewayApiKey>()
                .eq(GatewayApiKey::getEnabled, true)));
        result.put("apiKeys", apiKeys);

        // 管理后台用户：总数
        Map<String, Object> users = new LinkedHashMap<>();
        users.put("total", adminUserMapper.selectCount(null));
        result.put("users", users);

        // 调用统计：全量 + 今日（status='success' 为成功，'error' 为失败；successRate 在 total=0 时为 0）
        result.put("calls", aggregateCalls(callLogMapper.countByStatus()));
        result.put("todayCalls", aggregateCalls(callLogMapper.countTodayByStatus()));

        // Top 10 模型：count + successRate（success_cnt 已在 SQL 里用 FILTER 一次查出）
        List<Map<String, Object>> topModels = new ArrayList<>();
        for (Map<String, Object> row : callLogMapper.topModels()) {
            long cnt = ((Number) row.get("cnt")).longValue();
            long successCnt = ((Number) row.getOrDefault("success_cnt", 0L)).longValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model", row.get("model"));
            item.put("count", cnt);
            item.put("successRate", cnt == 0 ? 0.0 : successCnt * 1.0 / cnt);
            topModels.add(item);
        }
        result.put("topModels", topModels);

        // 近 7 天趋势：直接透传 Mapper 结果
        result.put("trend7d", callLogMapper.trend7d());
        return ApiResponse.ok(result);
    }

    /** 把 status 分布行（status + cnt）聚合成 {total, success, failed, successRate} */
    private Map<String, Object> aggregateCalls(List<Map<String, Object>> rows) {
        long total = 0, success = 0, failed = 0;
        for (Map<String, Object> row : rows) {
            long cnt = ((Number) row.get("cnt")).longValue();
            total += cnt;
            String status = row.get("status") == null ? "" : row.get("status").toString();
            if ("success".equals(status)) success += cnt;
            else if ("error".equals(status)) failed += cnt;
            // 'created'（视频任务进行中）不计入成功/失败
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("success", success);
        result.put("failed", failed);
        result.put("successRate", total == 0 ? 0.0 : success * 1.0 / total);
        return result;
    }
}
