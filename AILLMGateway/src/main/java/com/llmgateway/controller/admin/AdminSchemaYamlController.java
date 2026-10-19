package com.llmgateway.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/** 模型参数 Schema 的 JSON ↔ YAML 转换（管理端「YAML 视图」用） */
@RestController
@RequestMapping("/admin/model-params/schema")
@RequiredArgsConstructor
public class AdminSchemaYamlController {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/convert")
    public ApiResponse<Map<String, Object>> convert(@RequestBody ConvertRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("json", "");
            empty.put("yaml", "");
            return ApiResponse.ok(empty);
        }
        try {
            Object tree;
            if ("yaml".equalsIgnoreCase(request.from())) {
                tree = new Yaml().load(request.content());
                if (tree == null) tree = Map.of();
            } else {
                tree = objectMapper.readValue(request.content(), Object.class);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);

            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setIndent(2);
            options.setAllowUnicode(true);
            String yaml = new Yaml(options).dump(tree);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("json", json);
            result.put("yaml", yaml);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            throw new BusinessException(40001, "转换失败：" + e.getMessage());
        }
    }

    public record ConvertRequest(String content, String from) {
    }
}
