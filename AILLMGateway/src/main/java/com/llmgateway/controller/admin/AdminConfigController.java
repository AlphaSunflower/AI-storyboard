package com.llmgateway.controller.admin;

import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.ConfigUpdateRequest;
import com.llmgateway.dto.vo.SysConfigVO;
import com.llmgateway.entity.SysConfig;
import com.llmgateway.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 系统可调配置管理（/admin/** 已有 AdminJwtFilter ADMIN 鉴权）：GET 回显 / PUT 批量更新（落库，重启生效） */
@RestController
@RequestMapping("/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {

    private final SysConfigService sysConfigService;

    /** GET /admin/config：全量配置回显（表单编辑用） */
    @GetMapping
    public ApiResponse<List<SysConfigVO>> list() {
        return ApiResponse.ok(sysConfigService.getAll().stream().map(this::toVO).toList());
    }

    /** PUT /admin/config：批量更新（body {"items":[{"key":"gateway.upstream.retry-count","value":"3"}]}）；落库后重启生效 */
    @PutMapping
    public ApiResponse<List<SysConfigVO>> update(@RequestBody ConfigUpdateRequest request) {
        return ApiResponse.ok(sysConfigService.updateValues(request).stream().map(this::toVO).toList());
    }

    private SysConfigVO toVO(SysConfig e) {
        return new SysConfigVO(e.getConfigKey(), e.getConfigValue(), e.getRemark(), e.getUpdatedAt());
    }
}
