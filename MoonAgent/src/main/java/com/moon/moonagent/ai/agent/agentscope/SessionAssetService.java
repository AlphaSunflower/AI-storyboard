package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.agent.asset.AssetCaptionService;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SessionAssetService {

    private final AgentAssetMapper assetMapper;
    private final AgentScopeProperties properties;

    /**
     * 素材语义描述（可选依赖：单测用两参构造器直接 new，不引入视觉链路）。
     * 读素材时对「还没有描述」的历史素材做一次兜底打标自愈。
     */
    @Autowired(required = false)
    private transient AssetCaptionService captionService;

    public List<SessionAssetRef> list(String conversationId, String type, int requestedLimit) {
        return list(conversationId, type, requestedLimit, false);
    }

    public List<SessionAssetRef> list(String conversationId, String type,
                                      int requestedLimit, boolean referentialOnly) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        int limit = normalizeLimit(requestedLimit);
        LambdaQueryWrapper<AgentAsset> query = new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getConversationId, conversationId)
                // 已软删除的素材 MinIO 对象已经没了，不能再让智能体把它当成可用素材引用。
                .ne(AgentAsset::getStatus, AgentAsset.STATUS_DELETED)
                .orderByDesc(AgentAsset::getCreatedAt)
                .last("LIMIT " + Math.max(limit, 12));
        if (type != null && !type.isBlank()) {
            query.eq(AgentAsset::getType, type.trim().toLowerCase(Locale.ROOT));
        }
        List<AgentAsset> rows = assetMapper.selectList(query);
        if (rows == null || rows.isEmpty()) return List.of();

        List<SessionAssetRef> refs = new ArrayList<>();
        for (AgentAsset row : rows) {
            String url = firstUrl(row);
            if (url == null || url.isBlank()) continue;
            ensureCaption(row);
            refs.add(toRef(row, url));
            if (refs.size() >= limit && !referentialOnly) break;
        }
        return refs;
    }

    /** 历史素材自愈：没有 description 的图片素材触发一次异步打标（幂等、失败忽略）。 */
    private void ensureCaption(AgentAsset row) {
        AssetCaptionService service = captionService;
        if (service == null || row == null) return;
        if (row.getDescription() != null && !row.getDescription().isBlank()) return;
        try {
            service.ensureCaptionAsync(row.getId());
        } catch (Exception ignored) {
            // 打标是增强能力，绝不影响素材列表返回
        }
    }

    public List<SessionAssetRef> resolve(String conversationId, String type, String queryText,
                                         int requestedLimit, boolean referential) {
        List<SessionAssetRef> candidates = list(
                conversationId, type, Math.max(normalizeLimit(requestedLimit) * 2, 8), false);
        if (candidates.isEmpty()) return List.of();

        String needle = normalize(queryText);
        List<ScoredRef> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            SessionAssetRef ref = candidates.get(i);
            int score = Math.max(0, 100 - i * 5);
            if (referential) score += 30;
            if ("image".equalsIgnoreCase(ref.type())) score += 8;
            if (needle.isBlank()) {
                scored.add(new ScoredRef(ref, score));
                continue;
            }
            String haystack = normalize(ref.label()) + " " + normalize(ref.url()) + " "
                    + normalize(ref.sourceKind());
            if (haystack.contains(needle)) score += 80;
            for (String token : needle.split("\\s+")) {
                if (token.length() >= 2 && haystack.contains(token)) score += 12;
            }
            scored.add(new ScoredRef(ref, score));
        }
        scored.sort(Comparator.comparingInt(ScoredRef::score).reversed());
        int limit = normalizeLimit(requestedLimit);
        return scored.stream().limit(limit).map(ScoredRef::ref).toList();
    }

    public List<String> urlsByIds(String conversationId, List<String> assetIds) {
        if (conversationId == null || conversationId.isBlank()
                || assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new HashSet<>(assetIds);
        List<AgentAsset> rows = assetMapper.selectList(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getConversationId, conversationId)
                .in(AgentAsset::getId, ids)
                .ne(AgentAsset::getStatus, AgentAsset.STATUS_DELETED)
                .orderByDesc(AgentAsset::getCreatedAt));
        if (rows == null || rows.isEmpty()) return List.of();
        return rows.stream()
                .map(SessionAssetService::firstUrl)
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 按给定 id 顺序取素材引用（含语义标签）。
     *
     * <p>与 {@link #urlsByIds} 的区别：<b>结果顺序严格跟随入参</b>，且带 label。
     * 分镜生成需要把「素材是什么」写进 prompt，只拿 URL 的旧接口既丢标签、又因为按
     * created_at 排序而与入参 id 错位（首个资产被当成别的资产）。
     * 不存在/已删除的 id 直接跳过。
     */
    public List<SessionAssetRef> refsByIds(String conversationId, List<String> assetIds) {
        if (conversationId == null || conversationId.isBlank()
                || assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new HashSet<>(assetIds);
        List<AgentAsset> rows = assetMapper.selectList(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getConversationId, conversationId)
                .in(AgentAsset::getId, ids)
                .ne(AgentAsset::getStatus, AgentAsset.STATUS_DELETED));
        if (rows == null || rows.isEmpty()) return List.of();
        java.util.Map<String, SessionAssetRef> byId = new java.util.HashMap<>();
        for (AgentAsset row : rows) {
            String url = firstUrl(row);
            if (url == null || url.isBlank()) continue;
            ensureCaption(row);
            byId.put(row.getId(), toRef(row, url));
        }
        List<SessionAssetRef> ordered = new ArrayList<>();
        for (String id : assetIds) {
            SessionAssetRef ref = byId.get(id);
            if (ref != null) ordered.add(ref);
        }
        return ordered;
    }

    public Set<String> existingAssetIds(String conversationId, List<String> assetIds) {
        if (conversationId == null || conversationId.isBlank()
                || assetIds == null || assetIds.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>(assetIds);
        List<AgentAsset> rows = assetMapper.selectList(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getConversationId, conversationId)
                .in(AgentAsset::getId, ids)
                .ne(AgentAsset::getStatus, AgentAsset.STATUS_DELETED));
        if (rows == null || rows.isEmpty()) return Set.of();
        return rows.stream().map(AgentAsset::getId).collect(java.util.stream.Collectors.toSet());
    }

    private int normalizeLimit(int requested) {
        int upper = Math.max(1, properties.getAssetSearchLimit());
        return requested <= 0 ? upper : Math.min(requested, upper);
    }

    private static SessionAssetRef toRef(AgentAsset row, String url) {
        String sourceKind = "reference".equalsIgnoreCase(row.getType())
                ? "uploaded"
                : (row.getGenType() == null || row.getGenType().isBlank()
                    ? "generated" : row.getGenType());
        // 标签优先级：视觉描述 > 用户填写的用途 > 生成提示词 > 原始文件名 > 兜底文案。
        // 原始文件名放在兜底之前，是因为连打标都没成功时，IMG_xxx 也比「会话图片」多一条线索。
        String label = row.getDescription();
        if (label == null || label.isBlank()) label = row.getPurpose();
        if (label == null || label.isBlank()) label = row.getPrompt();
        if (label == null || label.isBlank()) label = row.getOriginalName();
        if (label == null || label.isBlank()) {
            label = "image".equalsIgnoreCase(row.getType()) ? "会话图片" : "会话素材";
        }
        if (label.length() > 80) label = label.substring(0, 80);
        return new SessionAssetRef(
                row.getId(),
                row.getType() == null ? "" : row.getType(),
                url,
                sourceKind,
                row.getCreatedAt() == null ? OffsetDateTime.now() : row.getCreatedAt(),
                label);
    }

    private static String firstUrl(AgentAsset row) {
        if (row.getResultUrls() != null && row.getResultUrls().contains("http")) {
            int start = row.getResultUrls().indexOf("http");
            int end = row.getResultUrls().indexOf('"', start);
            if (start >= 0) return end > start
                    ? row.getResultUrls().substring(start, end)
                    : row.getResultUrls().substring(start);
        }
        return row.getUrl();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record ScoredRef(SessionAssetRef ref, int score) {
    }
}
