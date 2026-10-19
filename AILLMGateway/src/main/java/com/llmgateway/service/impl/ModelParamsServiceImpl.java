package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.llmgateway.dto.admin.ModelParamsRequest;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.service.ModelCatalogCache;
import com.llmgateway.service.ModelParamsService;
import com.llmgateway.service.ModelRouteService;
import com.llmgateway.service.ParamsSchemaValidator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 模型参数能力+默认值管理实现：按 model_name upsert，GET 回显（表单编辑） */
@Service
@RequiredArgsConstructor
public class ModelParamsServiceImpl implements ModelParamsService {

    private static final Logger log = LoggerFactory.getLogger(ModelParamsServiceImpl.class);

    /** 合法模型类型（text 文本 / image 生图 / video 视频生成 / vision 图片视频理解） */
    private static final Set<String> MODEL_TYPES = Set.of("text", "image", "video", "vision");

    /**
     * 允许通过 {@code clearFields} 置空的字段白名单。
     * 用 SFunction 而不是列名字符串：由 MyBatis-Plus 负责驼峰→下划线，也避免任意属性名打穿实体。
     */
    private static final Map<String, SFunction<ModelParams, ?>> CLEARABLE = new LinkedHashMap<>();

    static {
        CLEARABLE.put("temperature", ModelParams::getTemperature);
        CLEARABLE.put("maxTokens", ModelParams::getMaxTokens);
        CLEARABLE.put("topP", ModelParams::getTopP);
        CLEARABLE.put("contextWindow", ModelParams::getContextWindow);
        CLEARABLE.put("nMin", ModelParams::getNMin);
        CLEARABLE.put("nMax", ModelParams::getNMax);
        CLEARABLE.put("nDefault", ModelParams::getNDefault);
        CLEARABLE.put("sizes", ModelParams::getSizes);
        CLEARABLE.put("sizeDefault", ModelParams::getSizeDefault);
        CLEARABLE.put("qualities", ModelParams::getQualities);
        CLEARABLE.put("qualityDefault", ModelParams::getQualityDefault);
        CLEARABLE.put("styles", ModelParams::getStyles);
        CLEARABLE.put("styleDefault", ModelParams::getStyleDefault);
        CLEARABLE.put("durations", ModelParams::getDurations);
        CLEARABLE.put("durationDefault", ModelParams::getDurationDefault);
        CLEARABLE.put("resolutions", ModelParams::getResolutions);
        CLEARABLE.put("resolutionDefault", ModelParams::getResolutionDefault);
        CLEARABLE.put("aspectRatios", ModelParams::getAspectRatios);
        CLEARABLE.put("aspectRatioDefault", ModelParams::getAspectRatioDefault);
        CLEARABLE.put("refImagesMin", ModelParams::getRefImagesMin);
        CLEARABLE.put("refImagesMax", ModelParams::getRefImagesMax);
        CLEARABLE.put("refVideosMin", ModelParams::getRefVideosMin);
        CLEARABLE.put("refVideosMax", ModelParams::getRefVideosMax);
        CLEARABLE.put("audioCountMin", ModelParams::getAudioCountMin);
        CLEARABLE.put("audioCountMax", ModelParams::getAudioCountMax);
        CLEARABLE.put("audioSegmentDurationMin", ModelParams::getAudioSegmentDurationMin);
        CLEARABLE.put("audioSegmentDurationMax", ModelParams::getAudioSegmentDurationMax);
        CLEARABLE.put("videoSegmentDurationMin", ModelParams::getVideoSegmentDurationMin);
        CLEARABLE.put("videoSegmentDurationMax", ModelParams::getVideoSegmentDurationMax);
        CLEARABLE.put("maxTotalDuration", ModelParams::getMaxTotalDuration);
        CLEARABLE.put("maxTotalFiles", ModelParams::getMaxTotalFiles);
        CLEARABLE.put("maxVideoSizeMb", ModelParams::getMaxVideoSizeMb);
        CLEARABLE.put("maxImageSizeMb", ModelParams::getMaxImageSizeMb);
        CLEARABLE.put("maxAudioSizeMb", ModelParams::getMaxAudioSizeMb);
        CLEARABLE.put("maxRequestBodyMb", ModelParams::getMaxRequestBodyMb);
        CLEARABLE.put("maxPromptChars", ModelParams::getMaxPromptChars);
        CLEARABLE.put("ollamaParams", ModelParams::getOllamaParams);
        CLEARABLE.put("paramsSchema", ModelParams::getParamsSchema);
    }

    private final ModelParamsMapper modelParamsMapper;
    private final ModelRouteService modelRouteService;
    private final ModelCatalogCache catalogCache;

    /** PUT /admin/model-params：按 model_name upsert（type 空默认 text；nMin/nMax 均提供时须 nMin<=nMax） */
    @Override
    public ModelParams upsert(ModelParamsRequest req) {
        if (req.modelName() == null || req.modelName().isBlank()) {
            throw new BusinessException(40001, "模型名不能为空");
        }
        String type = normalizeType(req.type());
        if (req.nMin() != null && req.nMax() != null && req.nMin() > req.nMax()) {
            throw new BusinessException(40001, "数量范围不合法");
        }
        // 结构错误必须在保存时挡住：否则 ModelParamsSpecNormalizer 会静默返回 null，
        // 配置者以为生效了，实际前端退回固定列、响应归一化也没执行。
        if (req.paramsSchema() != null && !req.paramsSchema().isBlank()) {
            ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(req.paramsSchema());
            if (!result.valid()) {
                throw new BusinessException(40001, "参数 Schema 不合法：" + result.summary());
            }
        }
        validateRange(req.refImagesMin(), req.refImagesMax(), "可参考图范围");
        validateRange(req.refVideosMin(), req.refVideosMax(), "可参考视频范围");
        validateRange(req.audioCountMin(), req.audioCountMax(), "音频个数范围");
        validateRange(req.audioSegmentDurationMin(), req.audioSegmentDurationMax(), "音频单段时长范围");
        validateRange(req.videoSegmentDurationMin(), req.videoSegmentDurationMax(), "视频单段时长范围");

        if (Boolean.TRUE.equals(req.isDefault())) {
            modelParamsMapper.update(null, new LambdaUpdateWrapper<ModelParams>()
                    .eq(ModelParams::getType, type)
                    .eq(ModelParams::getIsDefault, true)
                    .ne(ModelParams::getModelName, req.modelName().trim())
                    .set(ModelParams::getIsDefault, false));
        }
        // 「默认模型」的唯一权威是路由默认：参数页这个开关必须写透过去，
        // 否则会出现「列表标了默认、实际生成却用别的模型」。
        if (req.isDefault() != null) {
            boolean applied = modelRouteService.setDefaultRoute(
                    type, req.modelName().trim(), Boolean.TRUE.equals(req.isDefault()));
            if (!applied) {
                log.warn("模型参数设置了默认，但该模型在 {} 类型下没有路由，默认模型未变更: {}",
                        type, req.modelName().trim());
            }
        }

        ModelParams existing = modelParamsMapper.selectOne(new LambdaQueryWrapper<ModelParams>()
                .eq(ModelParams::getModelName, req.modelName().trim())
                .eq(ModelParams::getType, type));
        // 参数能力（sizes/durations/resolutions/contextWindow 等）也随 /v1/models 下发，
        // 所以任何一次 upsert 都要清列表缓存——不能只依赖「顺手设了默认」这条路径
        catalogCache.evict(type);
        if (existing != null) {
            applyNonNull(existing, req);
            existing.setUpdatedAt(OffsetDateTime.now());
            modelParamsMapper.updateById(existing);
            // updateById 的默认策略忽略 null，清空必须显式 SET col = null
            applyClearFields(existing.getId(), req);
            ModelParams updated = modelParamsMapper.selectById(existing.getId());
            return updated != null ? updated : existing;
        }

        ModelParams entity = new ModelParams();
        entity.setModelName(req.modelName().trim());
        entity.setType(type);
        applyNonNull(entity, req);
        entity.setIsDefault(Boolean.TRUE.equals(req.isDefault()));
        OffsetDateTime now = OffsetDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        modelParamsMapper.insert(entity);
        return entity;
    }

    @Override
    public ModelParams getByModelNameAndType(String modelName, String type) {
        return modelParamsMapper.selectOne(new LambdaQueryWrapper<ModelParams>()
                .eq(ModelParams::getModelName, modelName.trim())
                .eq(ModelParams::getType, type));
    }

    @Override
    public java.util.List<ModelParams> listAll() {
        return modelParamsMapper.selectList(new LambdaQueryWrapper<ModelParams>()
                .orderByAsc(ModelParams::getModelName)
                .orderByAsc(ModelParams::getType));
    }

    @Override
    public ModelParams getByModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) return null;
        return modelParamsMapper.selectOne(new LambdaQueryWrapper<ModelParams>()
                .eq(ModelParams::getModelName, modelName.trim())
                .orderByAsc(ModelParams::getType)
                .last("LIMIT 1"));
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) return "text";
        String t = type.trim().toLowerCase();
        if (!MODEL_TYPES.contains(t)) throw new BusinessException(40001, "type 仅支持 text/image/video/vision");
        return t;
    }

    private void applyNonNull(ModelParams entity, ModelParamsRequest req) {
        if (req.type() != null) entity.setType(req.type().trim().toLowerCase());
        if (req.isDefault() != null) entity.setIsDefault(req.isDefault());
        if (req.temperature() != null) entity.setTemperature(req.temperature());
        if (req.maxTokens() != null) entity.setMaxTokens(req.maxTokens());
        if (req.topP() != null) entity.setTopP(req.topP());
        if (req.contextWindow() != null) entity.setContextWindow(req.contextWindow());
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
        if (req.ollamaParams() != null && !req.ollamaParams().isBlank()) entity.setOllamaParams(req.ollamaParams());
        if (req.paramsSchema() != null && !req.paramsSchema().isBlank()) entity.setParamsSchema(req.paramsSchema());
    }

    /**
     * 显式清空字段：{@code clearFields} 里列出的字段 + 空字符串的 paramsSchema/ollamaParams。
     * 未知字段只告警跳过，不因为一个拼写错误把整次保存打回。
     */
    private void applyClearFields(String id, ModelParamsRequest req) {
        Set<String> fields = new LinkedHashSet<>();
        if (req.clearFields() != null) {
            for (String field : req.clearFields()) {
                if (field != null && !field.isBlank()) fields.add(field.trim());
            }
        }
        if (req.paramsSchema() != null && req.paramsSchema().isBlank()) fields.add("paramsSchema");
        if (req.ollamaParams() != null && req.ollamaParams().isBlank()) fields.add("ollamaParams");
        if (fields.isEmpty()) return;

        LambdaUpdateWrapper<ModelParams> wrapper = new LambdaUpdateWrapper<ModelParams>()
                .eq(ModelParams::getId, id);
        boolean any = false;
        for (String field : fields) {
            SFunction<ModelParams, ?> column = CLEARABLE.get(field);
            if (column == null) {
                log.warn("忽略未知的可清空字段: {}", field);
                continue;
            }
            wrapper.set(column, null);
            any = true;
        }
        if (any) {
            modelParamsMapper.update(null, wrapper);
            log.info("模型参数已清空字段: modelParamsId={}, fields={}", id, fields);
        }
    }

    private void validateRange(Integer min, Integer max, String label) {
        if (min != null && max != null && min > max) {
            throw new BusinessException(40001, label + "不合法：min 不能大于 max");
        }
    }
}
