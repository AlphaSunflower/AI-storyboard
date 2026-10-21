package com.moon.moonagent.ai.agent.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.AgentGlobalMemory;
import com.moon.moonagent.entity.AgentMemory;
import com.moon.moonagent.entity.AgentMemorySetting;
import com.moon.moonagent.mapper.AgentGlobalMemoryMapper;
import com.moon.moonagent.mapper.AgentMemoryMapper;
import com.moon.moonagent.mapper.AgentMemorySettingMapper;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentMemoryServiceImpl implements AgentMemoryService {

    private static final int MAX_CONTENT_CHARS = AgentMemoryLimits.CONTENT_MAX_CHARS;
    private static final int DEFAULT_LIMIT = 5;

    private final AgentMemoryMapper mapper;
    private final AgentGlobalMemoryMapper globalMapper;
    private final AgentMemorySettingMapper settingMapper;

    @Override
    public AgentMemory remember(String userId, String projectId, String conversationId, String kind,
                                String content, String source, Double confidence,
                                OffsetDateTime expiresAt) {
        requireEnabled(userId);
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        String normalized = normalize(content);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        String safeKind = normalizeKind(kind);
        String hash = hash(normalized);
        AgentMemory existing = mapper.selectOne(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .eq(AgentMemory::getProjectId, projectId)
                .eq(AgentMemory::getContentHash, hash)
                .last("limit 1"));
        OffsetDateTime now = OffsetDateTime.now();
        if (existing != null) {
            existing.setKind(safeKind);
            existing.setContent(normalized);
            existing.setSource(source == null || source.isBlank() ? "agent" : source);
            existing.setConfidence(clamp(confidence));
            existing.setExpiresAt(expiresAt);
            existing.setConversationId(conversationId);
            existing.setUpdatedAt(now);
            mapper.updateById(existing);
            return existing;
        }
        AgentMemory memory = new AgentMemory();
        memory.setId(UUID.randomUUID().toString().replace("-", ""));
        memory.setUserId(userId);
        memory.setProjectId(projectId);
        memory.setConversationId(conversationId);
        memory.setKind(safeKind);
        memory.setContent(normalized);
        memory.setContentHash(hash);
        memory.setSource(source == null || source.isBlank() ? "agent" : source);
        memory.setConfidence(clamp(confidence));
        memory.setExpiresAt(expiresAt);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        enforceCapacity(userId, projectId);
        mapper.insert(memory);
        return memory;
    }

    @Override
    public List<AgentMemory> recall(String projectId, String query, int limit) {
        if (projectId == null || projectId.isBlank()) return List.of();
        int capped = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 20);
        List<AgentMemory> rows = mapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getProjectId, projectId)
                .and(w -> w.isNull(AgentMemory::getExpiresAt)
                        .or().gt(AgentMemory::getExpiresAt, OffsetDateTime.now()))
                .orderByDesc(AgentMemory::getConfidence)
                .orderByDesc(AgentMemory::getUpdatedAt)
                .last("limit " + Math.max(capped * 4, 20)));
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        if (!needle.isBlank()) {
            List<String> tokens = tokenize(needle);
            rows = rows.stream()
                    .filter(row -> matches(row, tokens))
                    .toList();
        }
        List<AgentMemory> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparingDouble((AgentMemory m) -> m.getConfidence() == null ? 0 : m.getConfidence())
                .reversed()
                .thenComparing(AgentMemory::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return sorted.size() <= capped ? List.copyOf(sorted) : List.copyOf(sorted.subList(0, capped));
    }

    @Override
    public List<AgentMemory> list(String projectId, int limit) {
        if (projectId == null || projectId.isBlank()) return List.of();
        int capped = limit <= 0 ? 50 : Math.min(limit, 200);
        return mapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getProjectId, projectId)
                .orderByDesc(AgentMemory::getUpdatedAt)
                .last("limit " + capped));
    }

    @Override
    public List<AgentMemory> listByUser(String userId, int limit) {
        if (userId == null || userId.isBlank()) return List.of();
        int capped = limit <= 0 ? 200 : Math.min(limit, 500);
        return mapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .orderByDesc(AgentMemory::getUpdatedAt)
                .last("limit " + capped));
    }

    @Override
    public boolean delete(String projectId, String memoryId) {
        if (projectId == null || projectId.isBlank() || memoryId == null || memoryId.isBlank()) {
            return false;
        }
        return mapper.delete(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getProjectId, projectId)
                .eq(AgentMemory::getId, memoryId)) > 0;
    }

    @Override
    public boolean deleteForUser(String userId, String memoryId) {
        if (userId == null || userId.isBlank() || memoryId == null || memoryId.isBlank()) {
            return false;
        }
        return mapper.delete(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .eq(AgentMemory::getId, memoryId)) > 0;
    }

    @Override
    public AgentMemory updateForUser(String userId, String memoryId, String kind, String content,
                                     Double confidence, OffsetDateTime expiresAt) {
        if (userId == null || userId.isBlank() || memoryId == null || memoryId.isBlank()) {
            return null;
        }
        requireEnabled(userId);
        AgentMemory memory = mapper.selectOne(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .eq(AgentMemory::getId, memoryId)
                .last("limit 1"));
        if (memory == null) return null;
        String normalized = normalize(content);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        String hash = hash(normalized);
        AgentMemory duplicate = mapper.selectOne(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .eq(AgentMemory::getProjectId, memory.getProjectId())
                .eq(AgentMemory::getContentHash, hash)
                .ne(AgentMemory::getId, memoryId)
                .last("limit 1"));
        if (duplicate != null) {
            throw new IllegalArgumentException("同项目已存在相同内容的记忆");
        }
        memory.setKind(normalizeKind(kind));
        memory.setContent(normalized);
        memory.setContentHash(hash);
        memory.setConfidence(clamp(confidence));
        memory.setExpiresAt(expiresAt);
        memory.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(memory);
        return memory;
    }

    @Override
    public String promptContext(String projectId, String query, int limit) {
        // 注入走「项目 top N」而不是关键词过滤：中文查询通常没有空格，
        // 用整句 contains 会误杀本应注入的记忆；召回工具再按 query 精确过滤。
        List<AgentMemory> memories = recall(projectId, "", limit);
        if (memories.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (AgentMemory memory : memories) {
            out.append("- [").append(memory.getKind()).append("] ")
                    .append(memory.getContent()).append('\n');
        }
        return out.toString().trim();
    }

    @Override
    public AgentMemoryLimits limits() {
        return AgentMemoryLimits.DEFAULTS;
    }

    @Override
    public boolean enabled(String userId) {
        if (userId == null || userId.isBlank()) return true;
        AgentMemorySetting setting = settingMapper.selectById(userId);
        return setting == null || !Boolean.FALSE.equals(setting.getEnabled());
    }

    @Override
    public void setEnabled(String userId, boolean enabled) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(40001, "用户未登录");
        }
        AgentMemorySetting setting = settingMapper.selectById(userId);
        OffsetDateTime now = OffsetDateTime.now();
        if (setting == null) {
            setting = new AgentMemorySetting();
            setting.setUserId(userId);
            setting.setEnabled(enabled);
            setting.setUpdatedAt(now);
            settingMapper.insert(setting);
            return;
        }
        setting.setEnabled(enabled);
        setting.setUpdatedAt(now);
        settingMapper.updateById(setting);
    }

    @Override
    public AgentGlobalMemory global(String userId) {
        if (userId == null || userId.isBlank()) return null;
        return globalMapper.selectById(userId);
    }

    @Override
    public AgentGlobalMemory saveGlobal(String userId, String content) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(40001, "用户未登录");
        }
        requireEnabled(userId);
        String raw = content == null ? "" : content;
        if (raw.trim().length() > MAX_CONTENT_CHARS) {
            throw new BusinessException(40001, "全局记忆最多 " + MAX_CONTENT_CHARS + " 个字符");
        }
        String normalized = normalize(content);
        if (normalized.length() < AgentMemoryLimits.CONTENT_MIN_CHARS) {
            throw new BusinessException(40001,
                    "全局记忆至少 " + AgentMemoryLimits.CONTENT_MIN_CHARS + " 个字符");
        }
        OffsetDateTime now = OffsetDateTime.now();
        AgentGlobalMemory memory = globalMapper.selectById(userId);
        if (memory == null) {
            memory = new AgentGlobalMemory();
            memory.setUserId(userId);
            memory.setContent(normalized);
            memory.setContentHash(hash(normalized));
            memory.setUpdatedAt(now);
            globalMapper.insert(memory);
            return memory;
        }
        memory.setContent(normalized);
        memory.setContentHash(hash(normalized));
        memory.setUpdatedAt(now);
        globalMapper.updateById(memory);
        return memory;
    }

    @Override
    public boolean clearGlobal(String userId) {
        if (userId == null || userId.isBlank()) return false;
        return globalMapper.deleteById(userId) > 0;
    }

    @Override
    public String globalPromptContext(String userId) {
        if (!enabled(userId)) return "";
        AgentGlobalMemory memory = global(userId);
        return memory == null || memory.getContent() == null ? "" : memory.getContent().trim();
    }

    /**
     * 容量闸门：过期行先清理，再按「项目 → 账号」两级上限判定。
     *
     * <p>只对「新增行」调用；合并同内容记忆不会增加行数，不应被容量拦截。
     */
    private void enforceCapacity(String userId, String projectId) {
        purgeExpired(userId, projectId);
        long projectCount = count(mapper.selectCount(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getProjectId, projectId)));
        if (projectCount >= AgentMemoryLimits.PROJECT_MAX) {
            throw new BusinessException(40901, "项目记忆已达上限（"
                    + AgentMemoryLimits.PROJECT_MAX + " 条），请先在记忆管理中删除不再需要的条目");
        }
        long userCount = count(mapper.selectCount(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)));
        if (userCount >= AgentMemoryLimits.USER_MAX) {
            throw new BusinessException(40901, "账号记忆已达上限（"
                    + AgentMemoryLimits.USER_MAX + " 条），请先清理部分项目的记忆");
        }
    }

    /** 过期记忆不再参与召回，也不应继续占用容量；每次写入前顺手清掉（本项目 + 该账号其他项目）。 */
    private void purgeExpired(String userId, String projectId) {
        OffsetDateTime now = OffsetDateTime.now();
        mapper.delete(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getProjectId, projectId)
                .isNotNull(AgentMemory::getExpiresAt)
                .lt(AgentMemory::getExpiresAt, now));
        mapper.delete(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .isNotNull(AgentMemory::getExpiresAt)
                .lt(AgentMemory::getExpiresAt, now));
    }

    private static long count(Long value) {
        return value == null ? 0 : value;
    }

    /** 记忆管理关闭时，任何写入都必须是可见的拒绝，不能静默成功。 */
    private void requireEnabled(String userId) {
        if (!enabled(userId)) {
            throw new BusinessException(40901, "记忆管理已关闭，请先在账号页开启后再写入记忆");
        }
    }

    private static boolean matches(AgentMemory memory, List<String> tokens) {
        String haystack = (memory.getKind() + " " + memory.getContent()).toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (haystack.contains(token)) return true;
        }
        return false;
    }

    private static List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        for (String token : query.split("\\s+")) {
            if (token.length() >= 2) tokens.add(token);
        }
        if (tokens.isEmpty() && query.length() >= 2) tokens.add(query);
        return tokens;
    }

    private static String normalize(String content) {
        if (content == null) return "";
        String value = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        return value.length() <= MAX_CONTENT_CHARS ? value : value.substring(0, MAX_CONTENT_CHARS);
    }

    private static String normalizeKind(String kind) {
        String value = kind == null ? "" : kind.toLowerCase(Locale.ROOT).trim();
        return switch (value) {
            case "goal", "style", "constraint", "decision", "feedback" -> value;
            default -> "decision";
        };
    }

    private static double clamp(Double confidence) {
        if (confidence == null) return 0.8;
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
