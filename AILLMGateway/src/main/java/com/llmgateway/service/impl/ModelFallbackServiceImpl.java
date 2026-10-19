package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.entity.ModelFallback;
import com.llmgateway.mapper.ModelFallbackMapper;
import com.llmgateway.service.ModelFallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 回退链实现：按 (model_name, type) 全量替换，顺序即尝试顺序 */
@Service
@RequiredArgsConstructor
public class ModelFallbackServiceImpl implements ModelFallbackService {

    private final ModelFallbackMapper fallbackMapper;

    @Override
    public List<String> list(String modelName, String type) {
        if (modelName == null || modelName.isBlank()) return List.of();
        List<ModelFallback> rows = fallbackMapper.selectList(new LambdaQueryWrapper<ModelFallback>()
                .eq(ModelFallback::getModelName, modelName.trim())
                .eq(ModelFallback::getType, normalizeType(type))
                .orderByAsc(ModelFallback::getSortOrder));
        List<String> result = new ArrayList<>();
        for (ModelFallback row : rows) {
            if (row.getFallbackModel() != null && !row.getFallbackModel().isBlank()) {
                result.add(row.getFallbackModel());
            }
        }
        return result;
    }

    @Override
    @Transactional
    public List<String> replace(String modelName, String type, List<String> fallbacks) {
        if (modelName == null || modelName.isBlank()) return List.of();
        String normalizedType = normalizeType(type);
        String trimmedModel = modelName.trim();
        fallbackMapper.delete(new LambdaQueryWrapper<ModelFallback>()
                .eq(ModelFallback::getModelName, trimmedModel)
                .eq(ModelFallback::getType, normalizedType));
        if (fallbacks == null || fallbacks.isEmpty()) return List.of();

        Set<String> seen = new LinkedHashSet<>();
        List<String> stored = new ArrayList<>();
        int order = 0;
        for (String raw : fallbacks) {
            if (raw == null || raw.isBlank()) continue;
            String name = raw.trim();
            if (name.equals(trimmedModel) || !seen.add(name)) continue;
            ModelFallback row = new ModelFallback();
            row.setModelName(trimmedModel);
            row.setType(normalizedType);
            row.setFallbackModel(name);
            row.setSortOrder(order++);
            row.setCreatedAt(OffsetDateTime.now());
            row.setUpdatedAt(OffsetDateTime.now());
            fallbackMapper.insert(row);
            stored.add(name);
        }
        return stored;
    }

    private String normalizeType(String type) {
        return type == null || type.isBlank() ? "text" : type.trim().toLowerCase();
    }
}
