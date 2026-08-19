package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
        // 输入约束范围校验：5 组 min/max 均非空时 min 必须 <= max
        validateRange(req.refImagesMin(), req.refImagesMax(), "可参考图范围");
        validateRange(req.refVideosMin(), req.refVideosMax(), "可参考视频范围");
        validateRange(req.audioCountMin(), req.audioCountMax(), "音频个数范围");
        validateRange(req.audioSegmentDurationMin(), req.audioSegmentDurationMax(), "音频单段时长范围");
        validateRange(req.videoSegmentDurationMin(), req.videoSegmentDurationMax(), "视频单段时长范围");

        // 单一权威源：设为默认时，先清掉同类型其它模型的默认标记（每类型至多一个默认）
        if (Boolean.TRUE.equals(req.isDefault())) {
            modelParamsMapper.update(null, new LambdaUpdateWrapper<ModelParams>()
                    .eq(ModelParams::getType, type)
                    .eq(ModelParams::getIsDefault, true)
                    .ne(ModelParams::getModelName, req.modelName().trim())
                    .set(ModelParams::getIsDefault, false));
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
        entity.setIsDefault(Boolean.TRUE.equals(req.isDefault()));   // null → false（insert 显式落库）
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
        if (req.isDefault() != null) entity.setIsDefault(req.isDefault());
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
        if (req.refImagesMin() != null) entity.setRefImagesMin(req.refImagesMin());
        if (req.refImagesMax() != null) entity.setRefImagesMax(req.refImagesMax());
        if (req.refVideosMin() != null) entity.setRefVideosMin(req.refVideosMin());
        if (req.refVideosMax() != null) entity.setRefVideosMax(req.refVideosMax());
        if (req.audioCountMin() != null) entity.setAudioCountMin(req.audioCountMin());
        if (req.audioCountMax() != null) entity.setAudioCountMax(req.audioCountMax());
        if (req.audioSegmentDurationMin() != null) entity.setAudioSegmentDurationMin(req.audioSegmentDurationMin());
        if (req.audioSegmentDurationMax() != null) entity.setAudioSegmentDurationMax(req.audioSegmentDurationMax());
        if (req.videoSegmentDurationMin() != null) entity.setVideoSegmentDurationMin(req.videoSegmentDurationMin());
        if (req.videoSegmentDurationMax() != null) entity.setVideoSegmentDurationMax(req.videoSegmentDurationMax());
        if (req.maxTotalDuration() != null) entity.setMaxTotalDuration(req.maxTotalDuration());
        if (req.maxTotalFiles() != null) entity.setMaxTotalFiles(req.maxTotalFiles());
        if (req.maxVideoSizeMb() != null) entity.setMaxVideoSizeMb(req.maxVideoSizeMb());
        if (req.maxImageSizeMb() != null) entity.setMaxImageSizeMb(req.maxImageSizeMb());
        if (req.maxAudioSizeMb() != null) entity.setMaxAudioSizeMb(req.maxAudioSizeMb());
        if (req.maxRequestBodyMb() != null) entity.setMaxRequestBodyMb(req.maxRequestBodyMb());
        if (req.maxPromptChars() != null) entity.setMaxPromptChars(req.maxPromptChars());
    }

    /** 范围校验：min/max 均非空且 min > max → 40001 */
    private void validateRange(Integer min, Integer max, String label) {
        if (min != null && max != null && min > max) {
            throw new BusinessException(40001, label + "不合法：min 不能大于 max");
        }
    }
}
