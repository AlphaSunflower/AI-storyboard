package com.llmgateway.controller.admin;

import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.ModelParamsRequest;
import com.llmgateway.dto.vo.ModelParamsVO;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.service.ModelParamsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 模型参数能力+默认值管理：按 model_name upsert，GET 回显（表单编辑） */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class ModelParamsController {

    private final ModelParamsService modelParamsService;

    /** PUT /admin/model-params：按 model_name upsert（type 空默认 text；nMin/nMax 均提供时须 nMin<=nMax） */
    @PutMapping("/model-params")
    public ApiResponse<ModelParamsVO> upsert(@RequestBody ModelParamsRequest req) {
        return ApiResponse.ok(toVO(modelParamsService.upsert(req)));
    }

    /** GET /admin/model-params/{modelName}：表单编辑回显（不存在返回 data=null 不报错） */
    @GetMapping("/model-params/{modelName}")
    public ApiResponse<ModelParamsVO> get(@PathVariable String modelName) {
        ModelParams entity = modelParamsService.getByModelName(modelName);
        return ApiResponse.ok(entity == null ? null : toVO(entity));
    }

    private ModelParamsVO toVO(ModelParams e) {
        return new ModelParamsVO(e.getId(), e.getModelName(), e.getType(), e.getTemperature(), e.getMaxTokens(),
                e.getTopP(), e.getNMin(), e.getNMax(), e.getNDefault(), e.getSizes(), e.getSizeDefault(),
                e.getQualities(), e.getQualityDefault(), e.getStyles(), e.getStyleDefault(),
                e.getDurations(), e.getDurationDefault(), e.getResolutions(), e.getResolutionDefault(),
                e.getAspectRatios(), e.getAspectRatioDefault(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
