package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.vo.OllamaSyncResult;
import com.llmgateway.entity.Channel;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Ollama 模型列表同步：从渠道 GET /api/tags 拉取全量模型，仅刷新渠道测试候选（channel.models 逗号拼接），
 * 不自动创建 model_route——是否建路由由用户按需在路由管理手动配置。
 */
@Service
@RequiredArgsConstructor
public class OllamaModelSyncService {

    private static final Logger log = LoggerFactory.getLogger(OllamaModelSyncService.class);

    private static final String MODELS_CACHE_PREFIX = "gateway:models:";

    private final ChannelMapper channelMapper;
    private final KeyService keyService;
    private final UpstreamClient upstreamClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OllamaSyncResult sync(String channelId) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null) throw new BusinessException(40401, "渠道不存在");
        if (!"ollama".equals(channel.getType())) throw new BusinessException(40001, "仅 ollama 渠道支持同步模型");

        String apiKey = keyService.decrypt(channel.getApiKey());
        HttpResponse<String> resp = upstreamClient.get(
                stripTrailingSlash(channel.getBaseUrl()) + "/tags", apiKey);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new BusinessException(50201,
                    "上游 /api/tags 失败: " + resp.statusCode() + " " + upstreamClient.extractError(resp.body()));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(resp.body());
        } catch (Exception e) {
            throw new BusinessException(50201, "上游 /api/tags 响应解析失败: " + e.getMessage());
        }
        JsonNode models = root.path("models");
        if (!models.isArray()) throw new BusinessException(50201, "上游 /api/tags 响应缺少 models 数组");
        List<String> names = new ArrayList<>();
        for (JsonNode n : models) {
            String name = n.path("name").asText("");
            if (!name.isBlank()) names.add(name);
        }

        Channel upd = new Channel();
        upd.setId(channelId);
        upd.setModels(String.join(",", names));
        upd.setUpdatedAt(OffsetDateTime.now());
        channelMapper.updateById(upd);
        evictModelsCache();

        log.info("Ollama 模型列表刷新完成: channel={} total={}", channel.getName(), names.size());
        return new OllamaSyncResult(names.size(), names);
    }

    private void evictModelsCache() {
        try {
            Set<String> keys = redisTemplate.keys(MODELS_CACHE_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
        } catch (Exception e) {
            log.warn("清除模型列表缓存失败（不影响业务）: {}", e.getMessage());
        }
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
