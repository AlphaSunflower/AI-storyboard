package com.llmgateway.controller.admin;

import com.llmgateway.dto.ApiResponse;
import com.llmgateway.service.ModelFallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 跨模型回退链管理（模型级，不挂在单条路由上） */
@RestController
@RequestMapping("/admin/fallbacks")
@RequiredArgsConstructor
public class AdminFallbackController {

    private final ModelFallbackService fallbackService;

    @GetMapping
    public ApiResponse<List<String>> list(@RequestParam String modelName,
                                          @RequestParam(required = false) String type) {
        return ApiResponse.ok(fallbackService.list(modelName, type));
    }

    @PutMapping
    public ApiResponse<List<String>> replace(@RequestBody FallbackRequest request) {
        return ApiResponse.ok(fallbackService.replace(request.modelName(), request.type(), request.fallbacks()));
    }

    public record FallbackRequest(String modelName, String type, List<String> fallbacks) {
    }
}
