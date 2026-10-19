package com.llmgateway.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.ModelParamsPreviewRequest;
import com.llmgateway.dto.admin.ModelParamsRequest;
import com.llmgateway.dto.admin.ParamsSchemaValidateRequest;
import com.llmgateway.dto.vo.ModelParamsVO;
import com.llmgateway.dto.vo.ParamsSchemaValidationVO;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.service.ModelParamsService;
import com.llmgateway.service.ModelParamsSpecNormalizer;
import com.llmgateway.service.ParamsSchemaValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模型参数能力+默认值管理：按 model_name upsert，GET 回显（表单编辑） */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class ModelParamsController {

    private final ModelParamsService modelParamsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 全部模型配置（模型配置工作台列表用） */
    @GetMapping("/model-params/all")
    public ApiResponse<List<ModelParamsVO>> all() {
        return ApiResponse.ok(modelParamsService.listAll().stream().map(this::toVO).toList());
    }

    /** PUT /admin/model-params：按 model_name upsert（type 空默认 text；nMin/nMax 均提供时须 nMin<=nMax） */
    @PutMapping("/model-params")
    public ApiResponse<ModelParamsVO> upsert(@RequestBody ModelParamsRequest req) {
        return ApiResponse.ok(toVO(modelParamsService.upsert(req)));
    }

    /**
     * GET /admin/model-params?modelName=X&type=Y：表单编辑回显（不存在返回 data=null 不报错）。
     * 用查询参数而非路径变量：Ollama 模型名含 /（如 org/model:tag），路径变量会撞 Tomcat 编码斜杠 400；
     * 同时按 (model_name, type) 返回，支持同一模型 text/vision 各一套参数。
     */
    @GetMapping("/model-params")
    public ApiResponse<ModelParamsVO> get(@RequestParam String modelName, @RequestParam String type) {
        ModelParams entity = modelParamsService.getByModelNameAndType(modelName, type);
        return ApiResponse.ok(entity == null ? null : toVO(entity));
    }

    /**
     * POST /admin/model-params/validate：保存前校验 params_schema（结构化错误路径）。
     * 空 schema 视为未配置，返回 valid=true。
     */
    @PostMapping("/model-params/validate")
    public ApiResponse<ParamsSchemaValidationVO> validate(
            @RequestBody(required = false) ParamsSchemaValidateRequest req) {
        ParamsSchemaValidator.Result result =
                ParamsSchemaValidator.validate(req == null ? null : req.paramsSchema());
        List<ParamsSchemaValidationVO.Issue> errors = result.errors().stream()
                .map(i -> new ParamsSchemaValidationVO.Issue(i.path(), i.message()))
                .toList();
        List<ParamsSchemaValidationVO.Issue> warnings = result.warnings().stream()
                .map(i -> new ParamsSchemaValidationVO.Issue(i.path(), i.message()))
                .toList();
        return ApiResponse.ok(new ParamsSchemaValidationVO(
                result.valid(), errors, warnings,
                result.normalized() == null ? null
                        : objectMapper.convertValue(result.normalized(), Object.class)));
    }

    /**
     * POST /admin/model-params/normalize-preview：用真实上游样例试运行 sync/async 归一化。
     * 不调用上游、不落库，只验证 params_schema.response 的映射是否正确。
     */
    @PostMapping("/model-params/normalize-preview")
    public ApiResponse<Map<String, Object>> normalizePreview(@RequestBody ModelParamsPreviewRequest req) {
        if (req == null || req.sample() == null || req.sample().isBlank()) {
            throw new BusinessException(40001, "请粘贴一段上游响应样例");
        }
        ParamsSchemaValidator.Result validation = ParamsSchemaValidator.validate(req.paramsSchema());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", validation.valid());
        result.put("errors", validation.errors().stream()
                .map(i -> new ParamsSchemaValidationVO.Issue(i.path(), i.message())).toList());
        result.put("warnings", validation.warnings().stream()
                .map(i -> new ParamsSchemaValidationVO.Issue(i.path(), i.message())).toList());
        if (!validation.valid()) return ApiResponse.ok(result);

        JsonNode spec = ModelParamsSpecNormalizer.spec(req.paramsSchema());
        String normalized = "async".equalsIgnoreCase(req.kind())
                ? ModelParamsSpecNormalizer.normalizeVideoPoll(req.sample(), spec, req.taskId())
                : ModelParamsSpecNormalizer.normalizeImageSync(req.sample(), spec);
        try {
            // 不能直接返回 Jackson2 JsonNode：Spring Boot 4 用 Jackson3 序列化，会把它展开成 bean 属性
            result.put("normalized", objectMapper.readValue(normalized, Object.class));
        } catch (Exception e) {
            result.put("normalizedRaw", normalized);
        }
        return ApiResponse.ok(result);
    }

    private ModelParamsVO toVO(ModelParams e) {
        return new ModelParamsVO(e.getId(), e.getModelName(), e.getType(), e.getIsDefault(), e.getTemperature(), e.getMaxTokens(),
                e.getTopP(), e.getContextWindow(), e.getNMin(), e.getNMax(), e.getNDefault(), e.getSizes(), e.getSizeDefault(),
                e.getQualities(), e.getQualityDefault(), e.getStyles(), e.getStyleDefault(),
                e.getDurations(), e.getDurationDefault(), e.getResolutions(), e.getResolutionDefault(),
                e.getAspectRatios(), e.getAspectRatioDefault(),
                e.getRefImagesMin(), e.getRefImagesMax(),
                e.getRefVideosMin(), e.getRefVideosMax(),
                e.getAudioCountMin(), e.getAudioCountMax(),
                e.getAudioSegmentDurationMin(), e.getAudioSegmentDurationMax(),
                e.getVideoSegmentDurationMin(), e.getVideoSegmentDurationMax(),
                e.getMaxTotalDuration(), e.getMaxTotalFiles(),
                e.getMaxVideoSizeMb(), e.getMaxImageSizeMb(), e.getMaxAudioSizeMb(),
                e.getMaxRequestBodyMb(), e.getMaxPromptChars(),
                e.getOllamaParams(),
                e.getParamsSchema(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
