package com.storyboard.service.impl;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.common.BusinessException;
import com.storyboard.dto.request.SceneMaterialReferenceRequest;
import com.storyboard.dto.request.SceneMaterialGroupSnapshotRequest;
import com.storyboard.dto.request.SceneMaterialsUpdateRequest;
import com.storyboard.entity.AgentAsset;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.SceneReferenceImage;
import com.storyboard.dto.response.SceneReferenceResponse;
import com.storyboard.mapper.AgentAssetMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.SceneReferenceImageMapper;
import com.storyboard.mapper.SceneMaterialGroupSnapshotMapper;
import com.storyboard.service.AssetService;
import com.storyboard.service.SceneMaterialService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SceneMaterialServiceImpl implements SceneMaterialService {

    private static final Set<String> REFERENCE_TYPES = Set.of("image", "video", "audio");
    private static final Set<String> REFERENCE_SOURCES = Set.of("output", "scene_image", "group_snapshot");
    private static final Map<String, Integer> REFERENCE_LIMITS = Map.of(
            "image", 10,
            "video", 1);

    private final SceneMapper sceneMapper;
    private final ProjectMapper projectMapper;
    private final AssetService assetService;
    private final SceneReferenceImageMapper referenceMapper;
    private final AgentAssetMapper agentAssetMapper;
    private final SceneMaterialGroupSnapshotMapper groupSnapshotMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public void replaceMaterials(String userId, String sceneId, SceneMaterialsUpdateRequest request) {
        Scene scene = getOwnedScene(userId, sceneId);
        List<String> assetIds = distinct(request == null ? null : request.assetIds());
        List<SceneMaterialReferenceRequest> references = request == null || request.references() == null
                ? List.of() : request.references();
        List<SceneMaterialGroupSnapshotRequest> groupSnapshots = request == null || request.groupSnapshots() == null
                ? List.of() : request.groupSnapshots();

        long videoReferenceCount = references.stream()
                .filter(reference -> reference != null && "video".equals(reference.type()))
                .count();
        if (videoReferenceCount > 1) {
            throw new BusinessException(40001, "每个分镜最多选择 1 个视频参考");
        }

        for (SceneMaterialReferenceRequest reference : references) {
            validateReference(userId, scene, reference);
        }

        assetService.setSceneAssets(userId, sceneId, assetIds, assetIds);

        List<SceneReferenceImage> existing = referenceMapper.findBySceneId(sceneId);
        int nextOrder = existing.stream()
                .filter(item -> "upload".equals(item.getSourceType()))
                .map(SceneReferenceImage::getSortOrder)
                .filter(order -> order != null)
                .max(Integer::compareTo)
                .map(order -> order + 1)
                .orElse(0);
        for (SceneReferenceImage item : existing) {
            if (!"upload".equals(item.getSourceType())) {
                referenceMapper.deleteById(item.getId());
            }
        }
        for (SceneMaterialReferenceRequest reference : references) {
            SceneReferenceImage row = new SceneReferenceImage();
            row.setSceneId(sceneId);
            row.setType(reference.type());
            row.setPurpose(reference.purpose() == null || reference.purpose().isBlank()
                    ? reference.type() : reference.purpose());
            row.setImageUrl(reference.url());
            row.setRole(reference.role() == null || reference.role().isBlank() ? "REFERENCE" : reference.role());
            row.setSourceType(reference.sourceType());
            row.setSourceId(reference.sourceId());
            row.setSourceConversationId(reference.sourceConversationId());
            row.setSortOrder(nextOrder++);
            referenceMapper.insert(row);
        }
        groupSnapshotMapper.deleteBySceneId(sceneId);
        for (SceneMaterialGroupSnapshotRequest snapshot : groupSnapshots) {
            if (snapshot == null || snapshot.snapshotId() == null || snapshot.groupId() == null) continue;
            try {
                groupSnapshotMapper.insert(
                        sceneId,
                        snapshot.snapshotId(),
                        snapshot.groupId(),
                        snapshot.groupVersion() == null ? 0 : snapshot.groupVersion(),
                        snapshot.contentHash() == null ? "" : snapshot.contentHash(),
                        objectMapper.writeValueAsString(snapshot.items() == null ? List.of() : snapshot.items()));
            } catch (Exception e) {
                throw new BusinessException(40001, "资产组快照保存失败");
            }
        }
    }

    @Override
    @Transactional
    public List<SceneReferenceResponse> linkReferences(String userId, String sceneId,
                                                       List<SceneMaterialReferenceRequest> references) {
        Scene scene = getOwnedScene(userId, sceneId);
        List<SceneMaterialReferenceRequest> incoming = distinctReferences(references);
        if (incoming.isEmpty()) {
            throw new BusinessException(40001, "请选择要添加的参考素材");
        }
        for (SceneMaterialReferenceRequest reference : incoming) {
            validateReference(userId, scene, reference);
        }

        List<SceneReferenceImage> existing = referenceMapper.findBySceneId(sceneId);
        Set<String> existingKeys = new HashSet<>();
        Map<String, SceneReferenceImage> byKey = new HashMap<>();
        int nextOrder = existing.stream()
                .map(SceneReferenceImage::getSortOrder)
                .filter(order -> order != null)
                .max(Integer::compareTo)
                .map(order -> order + 1)
                .orElse(0);
        for (SceneReferenceImage item : existing) {
            String key = referenceKey(item.getType(), item.getPurpose(), item.getImageUrl());
            existingKeys.add(key);
            byKey.put(key, item);
        }

        List<SceneMaterialReferenceRequest> toInsert = new ArrayList<>();
        for (SceneMaterialReferenceRequest reference : incoming) {
            String key = referenceKey(reference.type(), reference.purpose(), reference.url());
            if (!existingKeys.contains(key)) {
                toInsert.add(reference);
                existingKeys.add(key);
            }
        }
        enforceReferenceLimits(existing, toInsert);

        List<SceneReferenceResponse> result = new ArrayList<>();
        for (SceneMaterialReferenceRequest reference : incoming) {
            String key = referenceKey(reference.type(), reference.purpose(), reference.url());
            SceneReferenceImage duplicate = byKey.get(key);
            if (duplicate != null) {
                result.add(toResponse(duplicate));
                continue;
            }

            SceneReferenceImage row = new SceneReferenceImage();
            row.setSceneId(sceneId);
            row.setType(reference.type());
            row.setPurpose(reference.purpose() == null || reference.purpose().isBlank()
                    ? reference.type() : reference.purpose());
            row.setImageUrl(reference.url());
            row.setRole(reference.role() == null || reference.role().isBlank() ? "REFERENCE" : reference.role());
            row.setSourceType(reference.sourceType());
            row.setSourceId(reference.sourceId());
            row.setSourceConversationId(reference.sourceConversationId());
            row.setSortOrder(nextOrder++);
            referenceMapper.insert(row);
            byKey.put(key, row);
            result.add(toResponse(row));
        }
        return result;
    }

    private void validateReference(String userId, Scene scene, SceneMaterialReferenceRequest reference) {
        if (reference == null
                || !REFERENCE_TYPES.contains(reference.type())
                || !REFERENCE_SOURCES.contains(reference.sourceType())
                || StringUtils.isBlank(reference.url())
                || StringUtils.isBlank(reference.sourceId())) {
            throw new BusinessException(40001, "分镜素材参数不完整");
        }
        if ("scene_image".equals(reference.sourceType())) {
            if (!"image".equals(reference.type()) || !scene.getId().equals(reference.sourceId())
                    || !sceneImageUrls(scene).contains(reference.url())) {
                throw new BusinessException(40001, "分镜图片不属于当前分镜");
            }
            return;
        }
        if ("group_snapshot".equals(reference.sourceType())) {
            return;
        }

        AgentAsset output = agentAssetMapper.findOwnedOutputRecord(userId, scene.getProjectId(), reference.sourceId());
        if (output == null || !reference.type().equals(output.getType()) || !outputUrls(output).contains(reference.url())) {
            throw new BusinessException(40401, "产出素材不存在或无权访问");
        }
        if (StringUtils.isNotBlank(reference.sourceConversationId())
                && !reference.sourceConversationId().equals(output.getConversationId())) {
            throw new BusinessException(40001, "产出素材所属对话不匹配");
        }
    }

    private Scene getOwnedScene(String userId, String sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");
        Project project = projectMapper.selectById(scene.getProjectId());
        if (project == null || !userId.equals(project.getUserId())) {
            throw new BusinessException(40401, "分镜不存在");
        }
        return scene;
    }

    private List<String> sceneImageUrls(Scene scene) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (scene.getImageUrls() != null && !scene.getImageUrls().isBlank()) {
            Arrays.stream(scene.getImageUrls().split(","))
                    .map(String::trim)
                    .filter(url -> !url.isBlank())
                    .forEach(urls::add);
        }
        if (scene.getImageUrl() != null && !scene.getImageUrl().isBlank()) {
            urls.add(scene.getImageUrl());
        }
        return List.copyOf(urls);
    }

    private List<String> outputUrls(AgentAsset output) {
        List<String> urls = new ArrayList<>();
        if (output.getResultUrls() != null && !output.getResultUrls().isBlank()) {
            try {
                List<String> parsed = objectMapper.readValue(output.getResultUrls(), new TypeReference<>() {});
                urls.addAll(parsed.stream().filter(url -> url != null && !url.isBlank()).toList());
            } catch (Exception ignored) {
                // 宽松兼容历史坏数据，下行走 url 单字段兜底
            }
        }
        if (urls.isEmpty() && output.getUrl() != null && !output.getUrl().isBlank()) urls.add(output.getUrl());
        return urls;
    }

    private void enforceReferenceLimits(List<SceneReferenceImage> existing,
                                        List<SceneMaterialReferenceRequest> toInsert) {
        for (Map.Entry<String, Integer> entry : REFERENCE_LIMITS.entrySet()) {
            String type = entry.getKey();
            long existingCount = existing.stream()
                    .filter(item -> type.equals(item.getType()))
                    .count();
            long incomingCount = toInsert.stream()
                    .filter(reference -> type.equals(reference.type()))
                    .count();
            if (existingCount + incomingCount > entry.getValue()) {
                String typeName = "video".equals(type) ? "视频" : "图";
                throw new BusinessException(40001, "参考" + typeName + "数量已达上限 " + entry.getValue());
            }
        }
    }

    private static List<SceneMaterialReferenceRequest> distinctReferences(
            List<SceneMaterialReferenceRequest> references) {
        if (references == null || references.isEmpty()) return List.of();
        Map<String, SceneMaterialReferenceRequest> unique = new java.util.LinkedHashMap<>();
        for (SceneMaterialReferenceRequest reference : references) {
            if (reference == null) continue;
            unique.putIfAbsent(referenceKey(reference.type(), reference.purpose(), reference.url()), reference);
        }
        return List.copyOf(unique.values());
    }

    private static String referenceKey(String type, String purpose, String url) {
        String normalizedType = type == null ? "" : type;
        String normalizedPurpose = purpose == null || purpose.isBlank() ? normalizedType : purpose;
        return normalizedType + "\n" + normalizedPurpose + "\n" + (url == null ? "" : url);
    }

    private static SceneReferenceResponse toResponse(SceneReferenceImage reference) {
        return new SceneReferenceResponse(
                reference.getId(),
                reference.getType(),
                reference.getPurpose(),
                reference.getImageUrl(),
                reference.getRole(),
                reference.getSourceType(),
                reference.getSourceId(),
                reference.getSourceConversationId(),
                reference.getFileName(),
                reference.getFileSize());
    }

    private static List<String> distinct(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }
}
