package com.llmgateway.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.entity.CallLog;
import com.llmgateway.mapper.CallLogMapper;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 调用日志查询（分页倒序） */
@RestController
@RequestMapping("/admin/call-logs")
public class AdminLogController {

    private final CallLogMapper callLogMapper;

    public AdminLogController(CallLogMapper callLogMapper) {
        this.callLogMapper = callLogMapper;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) String model) {
        size = Math.max(1, Math.min(50, size));   // 分页下界校验（防 size=-1 绕过上限）
        LambdaQueryWrapper<CallLog> wrapper = new LambdaQueryWrapper<CallLog>()
                .orderByDesc(CallLog::getCreatedAt);
        if (model != null && !model.isBlank()) {
            wrapper.eq(CallLog::getModel, model);
        }
        Page<CallLog> result = callLogMapper.selectPage(Page.of(page, size), wrapper);
        return ApiResponse.ok(Map.of("records", result.getRecords(), "total", result.getTotal(),
                "page", result.getCurrent(), "size", result.getSize()));
    }
}
