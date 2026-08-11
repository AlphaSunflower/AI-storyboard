package com.llmgateway.controller.admin;

import com.llmgateway.dto.ApiResponse;
import com.llmgateway.service.AdminUserService;
import com.llmgateway.service.CallLogService;
import com.llmgateway.service.ChannelService;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.ModelRouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台统计：单端点聚合仪表盘数据。
 * 调用状态口径与 CallLogService 落库一致：'success' 计成功、'error' 计失败，'created'（视频任务进行中）不计入成功/失败。
 */
@RestController
@RequestMapping("/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final ChannelService channelService;
    private final ModelRouteService routeService;
    private final KeyService keyService;
    private final AdminUserService adminUserService;
    private final CallLogService callLogService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 渠道：总数 + 启用数（selectCount 自动带 @TableLogic 过滤）
        result.put("channels", stats(channelService.countAll(), channelService.countEnabled()));

        // 模型路由：总数
        result.put("routes", Map.of("total", routeService.countAll()));

        // 业务调用 Key：总数 + 启用数
        result.put("apiKeys", stats(keyService.countAll(), keyService.countEnabled()));

        // 管理后台用户：总数
        result.put("users", Map.of("total", adminUserService.countAll()));

        // 调用统计：全量 + 今日（status='success' 为成功，'error' 为失败；successRate 在 total=0 时为 0）
        result.put("calls", callLogService.callStats());
        result.put("todayCalls", callLogService.todayCallStats());

        // Top 10 模型与近 7 天趋势：count/successRate/date 字段名已由 CallLogService 对齐前端契约
        result.put("topModels", callLogService.topModels());
        result.put("trend7d", callLogService.trend7d());
        return ApiResponse.ok(result);
    }

    /** 组装 {total, enabled} 统计块 */
    private Map<String, Object> stats(long total, long enabled) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("enabled", enabled);
        return m;
    }
}
