package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.dto.admin.ModelParamsRequest;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.service.ModelParamsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Set;

/** 模型参数能力+默认值管理实现：按 model_name upsert，GET 回显（表单编辑） */
@Service
@RequiredArgsConstructor
public class ModelParamsServiceImpl implements ModelParamsService {

    /** 合法模型类型（text 文本 / image 生图 / video 视频生成 / vision 图片视频理解） */
    private static final Set<String> MODEL_TYPES = Set.of("text", "image", "video", "vision");

    private final ModelParamsMapper modelParamsMapper;

    /** PUT /admin/model-params：按 model_name upsert（type 空默认 text；nMin/nMax 均提供时须 nMin<=nMax） */
    @Override
    public ModelParams upsert(ModelParamsRequest req) {
        if (req.modelName() == null || req.modelName().isBlank()) {
            throw new BusinessException(40001, "模型名不能为空");
        }
        // type 校验：空默认 text，非空须 ∈ {text,image,video,vision}
        String type = normalizeType(req.type());
        // 数量范围校验：nMin/nMax 均非空时 nMin 必须 <= nMax
        if (req.nMin() != null && req.nMax() != null && req.nMin() > req.nMax()) {
            throw new BusinessException(40001, "数量范围不合法");
        }

        ModelParams existing = modelParamsMapper.selectOne(new LambdaQueryWrapper<ModelParams>()
                .eq(ModelParams::getModelName, req.modelName().trim()));
        if (existing != null) {
            // 已存在：非 null 字段更新 + 刷新 updatedAt
            applyNonNull(existing, req);
            existing.setUpdatedAt(OffsetDateTime.now());
            modelParamsMapper.updateById(existing);
            return existing;
        }

        // 不存在：insert（id 由 @TableId(ASSIGN_UUID) 自动生成）
        ModelParams entity = new ModelParams();
        entity.setModelName(req.modelName().trim());
        entity.setType(type);
        applyNonNull(entity, req);
        OffsetDateTime now = OffsetDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        modelParamsMapper.insert(entity);
        return entity;
    }

    /** GET /admin/model-params/{modelName}：表单编辑回显（不存在返回 null 不报错） */
    @Override
    public ModelParams getByModelName(String modelName) {
        return modelParamsMapper.selectOne(new LambdaQueryWrapper<ModelParams>()
                .eq(ModelParams::getModelName, modelName.trim()));
    }

    /** 归一化模型类型：空默认 text，非法值抛 40001 */
    private String normalizeType(String type) {
        if (type == null || type.isBlank()) return "text";
        String t = type.trim().toLowerCase();
        if (!MODEL_TYPES.contains(t)) throw new BusinessException(40001, "type 仅支持 text/image/video/vision");
        return t;
    }

    /** 将请求中非 null 字段拷贝到实体（upsert 更新语义） */
    private void applyNonNull(ModelParams entity, ModelParamsRequest req) {
        if (req.type() != null) entity.setType(req.type().trim().toLowerCase());
        if (req.temperature() != null) entity.setTemperature(req.temperature());
        if (req.maxTokens() != null) entity.setMaxTokens(req.maxTokens());
        if (req.topP() != null) entity.setTopP(req.topP());
        if (req.nMin() != null) entity.setNMin(req.nMin());
        if (req.nMax() != null) entity.setNMax(req.nMax());
        if (req.nDefault() != null) entity.setNDefault(req.nDefault());
        if (req.sizes() != null) entity.setSizes(req.sizes());
        if (req.sizeDefault() != null) entity.setSizeDefault(req.sizeDefault());
        if (req.qualities() != null) entity.setQualities(req.qualities());
        if (req.qualityDefault() != null) entity.setQualityDefault(req.qualityDefault());
        if (req.styles() != null) entity.setStyles(req.styles());
        if (req.styleDefault() != null) entity.setStyleDefault(req.styleDefault());
        if (req.durations() != null) entity.setDurations(req.durations());
        if (req.durationDefault() != null) entity.setDurationDefault(req.durationDefault());
        if (req.resolutions() != null) entity.setResolutions(req.resolutions());
        if (req.resolutionDefault() != null) entity.setResolutionDefault(req.resolutionDefault());
        if (req.aspectRatios() != null) entity.setAspectRatios(req.aspectRatios());
        if (req.aspectRatioDefault() != null) entity.setAspectRatioDefault(req.aspectRatioDefault());
    }
}
