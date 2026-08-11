package com.llmgateway.controller.admin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.vo.CallLogVO;
import com.llmgateway.entity.CallLog;
import com.llmgateway.service.CallLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 调用日志查询（分页倒序） */
@RestController
@RequestMapping("/admin/call-logs")
@RequiredArgsConstructor
public class AdminLogController {

    private final CallLogService callLogService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) String model) {
        Page<CallLog> result = callLogService.page(page, size, model);
        return ApiResponse.ok(Map.of(
                "records", result.getRecords().stream().map(this::toVO).toList(),
                "total", result.getTotal(),
                "page", result.getCurrent(),
                "size", result.getSize()));
    }

    private CallLogVO toVO(CallLog e) {
        return new CallLogVO(e.getId(), e.getModel(), e.getChannelId(), e.getStatus(),
                e.getDurationMs(), e.getError(), e.getVideoUrl(), e.getTaskId(), e.getCreatedAt());
    }
}
