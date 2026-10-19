package com.llmgateway.controller.admin;

import com.llmgateway.dto.ApiResponse;
import com.llmgateway.service.GatewayConfigYamlService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 配置即代码：整份网关配置的 YAML 导出 / 校验 / 导入 */
@RestController
@RequestMapping("/admin/config-yaml")
@RequiredArgsConstructor
public class AdminConfigYamlController {

    private final GatewayConfigYamlService yamlService;

    @GetMapping
    public ApiResponse<Map<String, Object>> export() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("yaml", yamlService.exportYaml());
        return ApiResponse.ok(result);
    }

    @PostMapping("/validate")
    public ApiResponse<GatewayConfigYamlService.ValidationResult> validate(@RequestBody YamlRequest request) {
        return ApiResponse.ok(yamlService.validate(request == null ? null : request.yaml()));
    }

    @PostMapping("/import")
    public ApiResponse<GatewayConfigYamlService.ImportResult> importYaml(@RequestBody YamlRequest request) {
        return ApiResponse.ok(yamlService.importYaml(request == null ? null : request.yaml()));
    }

    public record YamlRequest(String yaml) {
    }
}
