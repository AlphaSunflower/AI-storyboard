package com.moon.moonagent.ai.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.GenerationRecordService;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.Map;

/**
 * 用户级生成记录服务实现。
 */
@Service
@RequiredArgsConstructor
public class GenerationRecordServiceImpl implements GenerationRecordService {

    private static final Logger log = LoggerFactory.getLogger(GenerationRecordServiceImpl.class);

    private final AgentAssetMapper assetMapper;
    private final AgentConversationMapper conversationMapper;
    private final AgentGenerationService generationService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService pollExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public Map<String, Object> listRecords(String userId, String genType, String status,
                                           LocalDate from, LocalDate to, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, size));
        long total = assetMapper.selectCount(buildFilter(userId, genType, status, from, to));
        LambdaQueryWrapper<AgentAsset> listQw = buildFilter(userId, genType, status, from, to)
                .orderByDesc(AgentAsset::getCreatedAt);
        List<AgentAsset> records = assetMapper.selectList(listQw.last(
                "LIMIT " + safeSize + " OFFSET " + ((long) (safePage - 1) * safeSize)));
        return Map.of("records", records, "total", total, "page", safePage, "size", safeSize);
    }

    /** 列表/总数共用的筛选条件（不含 orderBy，避免 COUNT 查询报「created_at 必须出现在 GROUP BY」）。 */
    private LambdaQueryWrapper<AgentAsset> buildFilter(String userId, String genType, String status,
                                                       LocalDate from, LocalDate to) {
        LambdaQueryWrapper<AgentAsset> qw = new LambdaQueryWrapper<AgentAsset>()
                .ne(AgentAsset::getType, "reference")
                .apply("(user_id = {0} OR conversation_id IN "
                        + "(SELECT id FROM conversations WHERE user_id = {0}))", userId);
        if (genType != null && !genType.isBlank()) {
            qw.eq(AgentAsset::getGenType, genType);
        }
        if ("success".equals(status)) {
            qw.in(AgentAsset::getStatus, List.of("completed", "succeeded"));
        } else if ("failed".equals(status)) {
            qw.eq(AgentAsset::getStatus, "failed");
        } else if ("active".equals(status)) {
            qw.in(AgentAsset::getStatus, List.of("queued", "generating", "running"));
        }
        if (from != null) {
            qw.ge(AgentAsset::getCreatedAt, from.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime());
        }
        if (to != null) {
            qw.lt(AgentAsset::getCreatedAt, to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime());
        }
        return qw;
    }

    @Override
    public AgentAsset getRecord(String userId, String id) {
        AgentAsset asset = assetMapper.selectById(id);
        if (asset == null) throw new BusinessException(40401, "记录不存在");
        assertOwnership(userId, asset);
        return asset;
    }

    @Override
    public AgentAsset refetch(String userId, String id) {
        AgentAsset asset = getRecord(userId, id);
        if (!"video".equals(asset.getType())) {
            throw new BusinessException(40001, "图片记录无需重新拉取");
        }
        if (asset.getTaskId() == null || asset.getTaskId().isBlank()) {
            throw new BusinessException(40001, "该记录缺少任务ID，无法重新拉取");
        }
        Map<String, String> poll = generationService.pollVideoTask(asset.getTaskId());
        String status = poll.getOrDefault("status", "unknown");
        if ("completed".equals(status) || "succeeded".equals(status)) {
            String url = poll.get("videoUrl") != null && !poll.get("videoUrl").isBlank() ? poll.get("videoUrl") : asset.getUrl();
            asset.setStatus("completed");
            if (url != null && !url.isBlank()) {
                asset.setUrl(url);
                asset.setResultUrls(toJson(List.of(url)));
            }
            asset.setError(null);
            asset.setDurationMs(duration(asset));
        } else if ("failed".equals(status)) {
            asset.setStatus("failed");
            if (poll.get("error") != null && !poll.get("error").isBlank()) asset.setError(poll.get("error"));
            asset.setDurationMs(duration(asset));
        } else {
            asset.setStatus("running");
        }
        try {
            assetMapper.updateById(asset);
        } catch (Exception e) {
            log.warn("重新拉取后更新记录失败: assetId={}, 原因: {}", asset.getId(), e.getMessage());
        }
        return asset;
    }

    @Override
    public Map<String, String> retry(String userId, String id) {
        AgentAsset failed = getRecord(userId, id);
        if (!"failed".equals(failed.getStatus())) {
            throw new BusinessException(40001, "仅失败记录可重试");
        }
        if (failed.getConversationId() == null || failed.getConversationId().isBlank()) {
            throw new BusinessException(40001, "AI 分镜生成记录请在编辑器中重试");
        }
        AgentConversation conv = conversationMapper.selectById(failed.getConversationId());
        if (conv == null) throw new BusinessException(40401, "会话不存在");

        Map<String, Object> params = parseParams(failed.getParams());
        List<String> refImages = parseReferenceImages(failed.getReferenceUrls());
        String sourceUrl = parseSourceUrl(failed.getReferenceUrls());

        String newId = null;
        String taskId = null;
        if ("video".equals(failed.getType())) {
            taskId = generationService.createVideoTask(
                    conv, failed.getSceneId(), failed.getPrompt(), failed.getModel(),
                    strVal(params.get("resolution")), strVal(params.get("size")),
                    strVal(params.get("aspectRatio")), strVal(params.get("duration")),
                    strVal(params.get("negativePrompt")), refImages, sourceUrl);
            if (taskId != null && !taskId.isBlank()) {
                AgentAsset newRecord = assetMapper.selectOne(
                        new LambdaQueryWrapper<AgentAsset>().eq(AgentAsset::getTaskId, taskId).last("LIMIT 1"));
                if (newRecord != null) {
                    newId = newRecord.getId();
                    final String fTaskId = taskId;
                    final String fNewId = newId;
                    pollExecutor.submit(() -> pollVideoTaskAndUpdateRecord(fTaskId, fNewId));
                }
            }
        } else {
            Integer n = params.get("n") instanceof Number num ? num.intValue() : null;
            Map<String, Object> gen = generationService.generateImage(
                    conv, failed.getSceneId(), failed.getPrompt(), failed.getModel(),
                    strVal(params.get("size")), strVal(params.get("quality")), n,
                    strVal(params.get("mode")), refImages, sourceUrl);
            Object ids = gen.get("assetIds");
            if (ids instanceof List<?> list && !list.isEmpty()) newId = String.valueOf(list.getFirst());
        }
        return Map.of("id", newId == null ? "" : newId, "taskId", taskId == null ? "" : taskId);
    }

    /** 重试视频的新任务后台轮询（复用网关轮询，直接更新新记录行；不写对话消息，仅供生成记录展示）。 */
    private void pollVideoTaskAndUpdateRecord(String taskId, String recordId) {
        try {
            for (int i = 0; i < 90; i++) {
                if (Thread.currentThread().isInterrupted()) return;
                Map<String, String> result = generationService.pollVideoTask(taskId);
                String status = result.getOrDefault("status", "unknown");
                AgentAsset record = assetMapper.selectById(recordId);
                if (record == null) return;
                if ("completed".equals(status) || "succeeded".equals(status)) {
                    String url = result.get("videoUrl") != null && !result.get("videoUrl").isBlank()
                            ? result.get("videoUrl") : record.getUrl();
                    record.setStatus("completed");
                    if (url != null && !url.isBlank()) {
                        record.setUrl(url);
                        record.setResultUrls(toJson(List.of(url)));
                    }
                    record.setError(null);
                    record.setDurationMs(duration(record));
                    assetMapper.updateById(record);
                    return;
                }
                if ("failed".equals(status)) {
                    record.setStatus("failed");
                    if (result.get("error") != null) record.setError(result.get("error"));
                    record.setDurationMs(duration(record));
                    assetMapper.updateById(record);
                    return;
                }
                record.setStatus("running");
                assetMapper.updateById(record);
                Thread.sleep(5000);
            }
            AgentAsset record = assetMapper.selectById(recordId);
            if (record != null) {
                record.setStatus("failed");
                record.setError("视频生成超时，请重试");
                record.setDurationMs(duration(record));
                assetMapper.updateById(record);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("重试视频后台轮询失败: taskId={}, 原因: {}", taskId, e.getMessage());
            AgentAsset record = assetMapper.selectById(recordId);
            if (record != null) {
                record.setStatus("failed");
                record.setError("视频生成失败，请稍后重试");
                record.setDurationMs(duration(record));
                assetMapper.updateById(record);
            }
        }
    }

    private void assertOwnership(String userId, AgentAsset asset) {
        if (userId != null && userId.equals(asset.getUserId())) return;
        if (asset.getConversationId() == null || asset.getConversationId().isBlank()) {
            throw new BusinessException(40401, "记录不存在");
        }
        AgentConversation conv = conversationMapper.selectById(asset.getConversationId());
        if (conv == null || !userId.equals(conv.getUserId())) {
            throw new BusinessException(40401, "记录不存在");
        }
    }

    private Long duration(AgentAsset asset) {
        if (asset.getCreatedAt() == null) return null;
        return Duration.between(asset.getCreatedAt(), OffsetDateTime.now()).toMillis();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 从 reference_urls JSON 提取参考图片 URL（排除 source 角色）。 */
    private List<String> parseReferenceImages(String json) {
        List<String> urls = new ArrayList<>();
        if (json == null || json.isBlank()) return urls;
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    String role = node.path("role").asText("");
                    String url = node.path("url").asText("");
                    if (!url.isBlank() && !"source".equals(role)) urls.add(url);
                }
            }
        } catch (Exception e) {
            log.warn("解析参考素材失败: {}", e.getMessage());
        }
        return urls;
    }

    /** 从 reference_urls JSON 提取 source 图（role=source；无则回退第一张图片）。 */
    private String parseSourceUrl(String json) {
        List<String> refs = parseReferenceImages(json);
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    if ("source".equals(node.path("role").asText(""))) {
                        return node.path("url").asText("");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析参考素材 source 失败: {}", e.getMessage());
        }
        return refs.isEmpty() ? null : refs.getFirst();
    }

    private String toJson(List<String> urls) {
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String strVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
