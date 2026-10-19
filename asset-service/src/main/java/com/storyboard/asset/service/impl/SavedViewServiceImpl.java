package com.storyboard.asset.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.asset.client.AssetContracts.LibrarySavedView;
import com.storyboard.asset.client.AssetContracts.SaveViewRequest;
import com.storyboard.asset.client.AssetContracts.UpdateSavedViewRequest;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.asset.service.SavedViewService;
import com.storyboard.common.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SavedViewServiceImpl implements SavedViewService {

    private static final Set<String> ALLOWED_FILTERS = Set.of(
            "q", "type", "tagIds", "tagMode", "minRating", "flag", "colorLabel",
            "origin", "conversationId", "folderId", "includeGlobal");
    private static final Set<String> ALLOWED_VIEW_MODES = Set.of("folders", "tree");
    private static final int MAX_FILTER_LIST = 20;
    private static final int MAX_FILTER_TEXT = 200;

    private final LibraryRepository repository;
    private final ProjectAccessPort projectAccess;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SavedViewServiceImpl(LibraryRepository repository, ProjectAccessPort projectAccess) {
        this.repository = repository;
        this.projectAccess = projectAccess;
    }

    @Override
    public List<LibrarySavedView> list(String userId, String projectId) {
        requireProject(userId, projectId);
        return repository.listSavedViews(userId, projectId);
    }

    @Override
    @Transactional
    public LibrarySavedView create(String userId, SaveViewRequest request) {
        if (request == null) throw new BusinessException(40001, "视图参数不完整");
        requireProject(userId, request.projectId());
        String name = normalizeName(request.name());
        Map<String, Object> filters = sanitizeFilters(request.filters());
        Map<String, Object> sort = sanitizeSort(request.sort());
        String viewMode = normalizeViewMode(request.viewMode());
        boolean pinned = Boolean.TRUE.equals(request.pinned());
        try {
            String id = repository.insertSavedView(
                    userId, request.projectId(), name, normalizeNameKey(name),
                    toJson(filters), toJson(sort), viewMode, pinned);
            return repository.findSavedView(userId, id)
                    .orElseThrow(() -> new BusinessException(50001, "创建视图失败"));
        } catch (DuplicateKeyException error) {
            throw new BusinessException(40901, "同名视图已存在");
        }
    }

    @Override
    @Transactional
    public LibrarySavedView update(String userId, String viewId, UpdateSavedViewRequest request) {
        LibrarySavedView current = repository.findSavedView(userId, viewId)
                .orElseThrow(() -> new BusinessException(40401, "视图不存在"));
        if (request == null) throw new BusinessException(40001, "视图参数不完整");
        String name = request.name() == null ? current.name() : normalizeName(request.name());
        Map<String, Object> filters = request.filters() == null
                ? current.filters() : sanitizeFilters(request.filters());
        Map<String, Object> sort = request.sort() == null
                ? current.sort() : sanitizeSort(request.sort());
        String viewMode = request.viewMode() == null
                ? current.viewMode() : normalizeViewMode(request.viewMode());
        boolean pinned = request.pinned() == null ? current.pinned() : request.pinned();
        try {
            boolean updated = repository.updateSavedView(
                    userId, viewId, name, normalizeNameKey(name),
                    toJson(filters), toJson(sort), viewMode, pinned);
            if (!updated) throw new BusinessException(40401, "视图不存在");
        } catch (DuplicateKeyException error) {
            throw new BusinessException(40901, "同名视图已存在");
        }
        return repository.findSavedView(userId, viewId)
                .orElseThrow(() -> new BusinessException(40401, "视图不存在"));
    }

    @Override
    @Transactional
    public void delete(String userId, String viewId) {
        if (!repository.deleteSavedView(userId, viewId)) {
            throw new BusinessException(40401, "视图不存在");
        }
    }

    private void requireProject(String userId, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(40001, "请先选择项目");
        }
        projectAccess.requireOwned(userId, projectId);
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) throw new BusinessException(40001, "视图名称不能为空");
        String trimmed = name.trim();
        if (trimmed.length() > 60) throw new BusinessException(40001, "视图名称最多 60 个字符");
        return trimmed;
    }

    private static String normalizeNameKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String normalizeViewMode(String viewMode) {
        if (viewMode == null || viewMode.isBlank()) return "folders";
        String value = viewMode.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_VIEW_MODES.contains(value)) {
            throw new BusinessException(40001, "视图模式不合法");
        }
        return value;
    }

    private static Map<String, Object> sanitizeFilters(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        filters.forEach((key, value) -> {
            if (key == null || !ALLOWED_FILTERS.contains(key) || value == null) return;
            if (value instanceof String text) {
                String trimmed = text.trim();
                if (!trimmed.isEmpty()) result.put(key, trimmed.length() > MAX_FILTER_TEXT
                        ? trimmed.substring(0, MAX_FILTER_TEXT) : trimmed);
                return;
            }
            if (value instanceof Number number) {
                result.put(key, number);
                return;
            }
            if (value instanceof Boolean bool) {
                result.put(key, bool);
                return;
            }
            if (value instanceof List<?> list) {
                List<String> values = list.stream()
                        .filter(item -> item instanceof String || item instanceof Number)
                        .map(String::valueOf)
                        .limit(MAX_FILTER_LIST)
                        .toList();
                if (!values.isEmpty()) result.put(key, values);
            }
        });
        return Map.copyOf(result);
    }

    private static Map<String, Object> sanitizeSort(Map<String, Object> sort) {
        if (sort == null || sort.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        Object field = sort.get("field");
        Object direction = sort.get("direction");
        if (field instanceof String text && !text.isBlank() && text.length() <= 40) {
            result.put("field", text.trim());
        }
        if (direction instanceof String text && !text.isBlank() && text.length() <= 8) {
            result.put("direction", text.trim().toLowerCase(Locale.ROOT));
        }
        return Map.copyOf(result);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new BusinessException(40001, "视图条件无法序列化");
        }
    }
}
