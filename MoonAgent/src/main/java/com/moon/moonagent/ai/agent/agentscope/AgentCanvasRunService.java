package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentCanvasRunEntity;
import com.moon.moonagent.entity.AgentCanvasRunOperationEntity;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.mapper.AgentCanvasRunMapper;
import com.moon.moonagent.mapper.AgentCanvasRunOperationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.service.MorrowCanvasService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Durable state for a canvas graph built by the current Agent turn.
 *
 * <p>A Canvas Run is intentionally not a pause/checkpoint. It is a normal
 * asynchronous artifact: the turn keeps running, every graph command updates
 * the run, and the browser iframe is only a viewer.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AgentCanvasRunService {

    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    /** 画布编辑模式自动接管当前画布时使用的合成 toolCallId 前缀（每个画布一条 run）。 */
    private static final String CANVAS_EDIT_TOOL_CALL_ID = "canvas-edit:";

    /** message metadata 上的画布增量补丁键（前端据此本地增量应用，不再整图重载）。 */
    static final String METADATA_CANVAS_PATCH = "canvasPatch";
    /** 补丁超限标记：前端见到它就退回一次全量重载。 */
    static final String METADATA_CANVAS_PATCH_OVERFLOW = "canvasPatchOverflow";

    /**
     * 补丁累积上限（nodes+edges 合计）。
     *
     * <p>上限必须很小：message metadata 在**每次消息读取**上都会走
     * {@code FileUrlSigner.signMetadata}（递归遍历每个字符串字段做 URL 重签名），
     * 一份 1MB 的补丁会把省下的重载时间全部还回去。400 条足够覆盖"一波增量上屏"，
     * 超出时前端本来就有 4s revision 轮询 + 全量重载兜底，降级是可接受的。
     */
    private static final int CANVAS_PATCH_MAX_ITEMS = 400;
    /** 补丁累积上限（JSON 序列化长度），与 {@link #CANVAS_PATCH_MAX_ITEMS} 先到为准。 */
    private static final int CANVAS_PATCH_MAX_JSON_CHARS = 256 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentCanvasRunMapper runMapper;
    private final AgentCanvasRunOperationMapper operationMapper;
    private final MorrowCanvasService canvasService;
    private final AgentMessageMapper messageMapper;
    @Autowired(required = false)
    private transient PlatformTransactionManager transactionManager;
    private volatile TransactionTemplate transactionTemplate;

    public CanvasRunView create(AgentConversation conversation,
                                String turnId,
                                String toolCallId,
                                String title,
                                String intent,
                                List<Map<String, Object>> plan) {
        return createInternal(conversation, turnId, toolCallId, null, title, intent, plan);
    }

    public CanvasRunView createExisting(AgentConversation conversation,
                                        String turnId,
                                        String toolCallId,
                                        String canvasId,
                                        String intent,
                                        String title,
                                        List<Map<String, Object>> plan) {
        return createInternal(conversation, turnId, toolCallId, canvasId, title, intent, plan);
    }

    /**
     * 画布编辑模式：把用户当前打开的画布直接纳入本轮 run 作用域。
     *
     * <p>用户已经在某张画布上作业时，不应该被迫先建任务、更不该另建画布；这里复用本轮已有
     * run，没有就为「当前画布」建一条 run（{@link #createExisting} 只取画布，不新建）。
     */
    public CanvasRunView adoptCurrentCanvas(AgentConversation conversation, String turnId, String canvasId) {
        if (conversation == null || canvasId == null || canvasId.isBlank()) return null;
        CanvasRunView active = activeByTurn(turnId);
        if (active != null) return active;
        return createExisting(conversation, turnId, CANVAS_EDIT_TOOL_CALL_ID + canvasId,
                canvasId, "canvas-edit", "", List.of());
    }

    private CanvasRunView createInternal(AgentConversation conversation,
                                         String turnId,
                                         String toolCallId,
                                         String existingCanvasId,
                                         String title,
                                         String intent,
                                         List<Map<String, Object>> plan) {
        if (conversation == null || conversation.getId() == null || conversation.getId().isBlank()) {
            throw new IllegalArgumentException("缺少会话，无法创建画布任务");
        }
        if (conversation.getProjectId() == null || conversation.getProjectId().isBlank()) {
            throw new IllegalStateException("当前会话尚未绑定项目，无法创建画布任务");
        }
        CanvasRunView existing = find(turnId, toolCallId);
        if (existing != null) return existing;

        String normalizedTitle = normalizeTitle(title);
        MorrowCanvas canvas = existingCanvasId == null || existingCanvasId.isBlank()
                ? canvasService.create(conversation.getUserId(), conversation.getProjectId(), normalizedTitle)
                : canvasService.get(conversation.getUserId(), existingCanvasId);

        return inTransaction(() ->
                persistCreated(conversation, turnId, toolCallId, canvas, intent, normalizedTitle, plan));
    }

    private CanvasRunView persistCreated(AgentConversation conversation,
                                         String turnId,
                                         String toolCallId,
                                         MorrowCanvas canvas,
                                         String intent,
                                         String normalizedTitle,
                                         List<Map<String, Object>> plan) {
        AgentCanvasRunEntity entity = new AgentCanvasRunEntity();
        entity.setConversationId(conversation.getId());
        entity.setUserId(conversation.getUserId());
        entity.setProjectId(conversation.getProjectId());
        entity.setTurnId(turnId);
        entity.setToolCallId(toolCallId);
        entity.setCanvasId(canvas.getId());
        entity.setTitle(canvas.getTitle() == null || canvas.getTitle().isBlank()
                ? normalizedTitle : canvas.getTitle());
        entity.setStatus(RUNNING);
        entity.setProgress(0);
        entity.setNodeCount(0);
        entity.setEdgeCount(0);
        entity.setRevision(0);
        entity.setPlanJson(write(plan == null ? List.of() : plan));
        runMapper.insert(entity);

        AgentMessage message = new AgentMessage();
        message.setConversationId(conversation.getId());
        message.setRole("assistant");
        message.setContent("");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "canvas-run");
        metadata.put("canvasRunId", entity.getId());
        metadata.put("canvasId", entity.getCanvasId());
        metadata.put("turnId", turnId);
        metadata.put("title", entity.getTitle());
        metadata.put("intent", intent == null ? "" : intent);
        metadata.put("status", entity.getStatus());
        metadata.put("progress", entity.getProgress());
        metadata.put("nodeCount", 0);
        metadata.put("edgeCount", 0);
        metadata.put("revision", 0);
        message.setMetadata(metadata);
        messageMapper.insert(message);
        entity.setMessageId(message.getId());
        runMapper.updateById(entity);

        return view(entity);
    }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            synchronized (this) {
                if (transactionTemplate == null) {
                    TransactionTemplate template = new TransactionTemplate(transactionManager);
                    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    transactionTemplate = template;
                }
            }
        }
        return transactionTemplate;
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        if (transactionManager == null) return action.get();
        return transactionTemplate().execute(tx -> action.get());
    }

    public CanvasRunView find(String turnId, String toolCallId) {
        if (turnId == null || turnId.isBlank() || toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        AgentCanvasRunEntity entity = runMapper.selectOne(
                new LambdaQueryWrapper<AgentCanvasRunEntity>()
                        .eq(AgentCanvasRunEntity::getTurnId, turnId)
                        .eq(AgentCanvasRunEntity::getToolCallId, toolCallId)
                        .last("LIMIT 1"));
        return entity == null ? null : view(entity);
    }

    public CanvasRunView get(String runId) {
        if (runId == null || runId.isBlank()) return null;
        AgentCanvasRunEntity entity = runMapper.selectById(runId);
        return entity == null ? null : view(entity);
    }

    public CanvasRunView activeByTurn(String turnId) {
        if (turnId == null || turnId.isBlank()) return null;
        AgentCanvasRunEntity entity = runMapper.selectOne(
                new LambdaQueryWrapper<AgentCanvasRunEntity>()
                        .eq(AgentCanvasRunEntity::getTurnId, turnId)
                        .orderByDesc(AgentCanvasRunEntity::getCreatedAt)
                        .last("LIMIT 1"));
        return entity == null ? null : view(entity);
    }

    @Transactional
    public CanvasRunView updateFromGraphResult(String runId, Map<String, Object> result) {
        AgentCanvasRunEntity entity = runId == null || runId.isBlank()
                ? null : runMapper.selectById(runId);
        if (entity == null) return null;
        Map<String, Object> payload = result == null ? Map.of() : result;
        entity.setProgress(integer(payload.get("progress"), entity.getProgress()));
        entity.setNodeCount(integer(payload.get("nodeCount"), entity.getNodeCount()));
        entity.setEdgeCount(integer(payload.get("edgeCount"), entity.getEdgeCount()));
        entity.setRevision(integer(payload.get("revision"), entity.getRevision()));
        if (!Boolean.FALSE.equals(payload.get("ok"))) {
            entity.setStatus(RUNNING);
        }
        runMapper.updateById(entity);

        AgentMessage message = entity.getMessageId() == null
                ? null : messageMapper.selectById(entity.getMessageId());
        if (message != null) {
            Map<String, Object> metadata = new LinkedHashMap<>(
                    message.getMetadata() == null ? Map.of() : message.getMetadata());
            metadata.put("status", entity.getStatus());
            metadata.put("progress", entity.getProgress());
            copyInteger(payload, metadata, "revision");
            copyInteger(payload, metadata, "nodeCount");
            copyInteger(payload, metadata, "edgeCount");
            if (payload.get("error") != null) metadata.put("error", payload.get("error"));
            applyCanvasPatch(metadata, payload.get("patch"));
            message.setMetadata(metadata);
            messageMapper.updateById(message);
        }
        return view(entity);
    }

    /**
     * 把 run 对应消息上累积的增量补丁取成 SSE payload 片段。
     *
     * <p>补丁只存在于 message metadata（run 表没有这个列），而直播事件发的是
     * {@link CanvasRunView#toMap()}——它不含补丁。所以直播路径必须显式把补丁合并进
     * {@code canvas_run_updated.payload}：否则前端在直播阶段读不到补丁，只能退回整图重载，
     * 增量通道就退化成"只有刷新后才有效"（2026-10-16 复核时发现的跨模块缺口）。
     *
     * <p>读取失败或无补丁时返回空 map，绝不抛错：补丁只是加速通道，缺席时前端会走全量重载。
     */
    public Map<String, Object> canvasPatchPayload(String runId) {
        if (runId == null || runId.isBlank()) return Map.of();
        try {
            AgentCanvasRunEntity entity = runMapper.selectById(runId);
            if (entity == null || entity.getMessageId() == null) return Map.of();
            AgentMessage message = messageMapper.selectById(entity.getMessageId());
            if (message == null || message.getMetadata() == null) return Map.of();
            Map<String, Object> metadata = message.getMetadata();
            Map<String, Object> out = new LinkedHashMap<>();
            Object patch = metadata.get(METADATA_CANVAS_PATCH);
            if (patch instanceof Map<?, ?>) out.put(METADATA_CANVAS_PATCH, patch);
            if (Boolean.TRUE.equals(metadata.get(METADATA_CANVAS_PATCH_OVERFLOW))) {
                out.put(METADATA_CANVAS_PATCH_OVERFLOW, Boolean.TRUE);
            }
            return out;
        } catch (RuntimeException error) {
            log.warn("[Canvas] 读取增量补丁失败 runId={}: {}", runId, error.getMessage());
            return Map.of();
        }
    }

    /**
     * 把一条命令返回的增量补丁并进 message metadata。
     *
     * <p>一个批次里会有多条画布命令，而 metadata 是"读-改-写整份覆写"，只保留最后一条补丁
     * 会丢掉同批其他命令建的节点/边，因此必须**按 id upsert 合并累积**（removed 取并集）。
     * 非法结构只打 WARN 不动 metadata，绝不因为补丁格式问题让工具调用失败（老版本 canvas
     * 服务不返回 patch，零回归）。
     */
    private static void applyCanvasPatch(Map<String, Object> metadata, Object rawPatch) {
        if (Boolean.TRUE.equals(metadata.get(METADATA_CANVAS_PATCH_OVERFLOW))) {
            // 溢出闩锁：此后不再累积，也不保留任何（可能不完整的）补丁
            metadata.remove(METADATA_CANVAS_PATCH);
            return;
        }
        if (!(rawPatch instanceof Map<?, ?> map)) return;
        Map<String, Object> patch = new LinkedHashMap<>();
        map.forEach((key, value) -> patch.put(String.valueOf(key), value));
        PatchAccumulation accumulated = accumulatePatch(
                asPatchMap(metadata.get(METADATA_CANVAS_PATCH)), patch);
        if (accumulated.overflow()) {
            // 部分补丁 + 全量重载兜底会造成状态歧义：超限就清空补丁，只留标记让前端整图重载。
            metadata.remove(METADATA_CANVAS_PATCH);
            metadata.put(METADATA_CANVAS_PATCH_OVERFLOW, true);
            log.warn("[Canvas] 增量补丁超过上限（items>{} 或 json>{} 字符），已停止累积，前端将全量重载",
                    CANVAS_PATCH_MAX_ITEMS, CANVAS_PATCH_MAX_JSON_CHARS);
            return;
        }
        if (!accumulated.changed()) return;
        metadata.put(METADATA_CANVAS_PATCH, accumulated.patch());
    }

    /** 已累积补丁 → 可合并的 map；缺失或非法结构返回空 map（不会抛）。 */
    private static Map<String, Object> asPatchMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    /**
     * 纯函数：把 {@code incoming} 补丁按 id upsert 合并进 {@code existing}，并做上限判定。
     *
     * @param existing 已累积的补丁（可为 null/非法）
     * @param incoming 本次命令返回的补丁
     * @return 合并结果；{@code changed=false} 表示结构非法或没有可合并内容（调用方不动 metadata）
     */
    static PatchAccumulation accumulatePatch(Map<String, Object> existing, Map<String, Object> incoming) {
        if (!hasPatchKeys(incoming)) {
            // 没有补丁键（老版本 canvas 服务或非法结构）：原样保留已累积的补丁，不算变化
            return new PatchAccumulation(normalizePatch(existing), false, false);
        }
        Map<String, Object> base = normalizePatch(existing);
        Map<String, Object> addition = normalizePatch(incoming);
        Map<String, Map<String, Object>> nodes = indexById(base.get("nodes"));
        Map<String, Map<String, Object>> edges = indexById(base.get("edges"));
        Set<String> removedNodeIds = idSet(base.get("removedNodeIds"));
        Set<String> removedEdgeIds = idSet(base.get("removedEdgeIds"));

        indexById(addition.get("nodes")).forEach((id, node) -> {
            nodes.put(id, node);
            removedNodeIds.remove(id);
        });
        indexById(addition.get("edges")).forEach((id, edge) -> {
            edges.put(id, edge);
            removedEdgeIds.remove(id);
        });
        idSet(addition.get("removedNodeIds")).forEach(id -> {
            nodes.remove(id);
            removedNodeIds.add(id);
        });
        idSet(addition.get("removedEdgeIds")).forEach(id -> {
            edges.remove(id);
            removedEdgeIds.add(id);
        });

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("nodes", List.copyOf(nodes.values()));
        merged.put("edges", List.copyOf(edges.values()));
        merged.put("removedNodeIds", List.copyOf(removedNodeIds));
        merged.put("removedEdgeIds", List.copyOf(removedEdgeIds));

        boolean overflow = nodes.size() + edges.size() > CANVAS_PATCH_MAX_ITEMS
                || serializedLength(merged) > CANVAS_PATCH_MAX_JSON_CHARS;
        return new PatchAccumulation(merged, true, overflow);
    }

    /** 补丁是否至少带一个约定的键；不带就是"没有补丁"，调用方不得改动 metadata。 */
    private static boolean hasPatchKeys(Map<String, Object> raw) {
        return raw != null && (raw.containsKey("nodes") || raw.containsKey("edges")
                || raw.containsKey("removedNodeIds") || raw.containsKey("removedEdgeIds"));
    }

    /**
     * 归一化补丁：四个键都补齐，保证对外形状固定。
     *
     * <p>缺失的键落成 {@code null}，由 {@link #indexById} / {@link #idSet} 容忍成空集合，
     * 因此本方法**永不返回 null**。
     */
    private static Map<String, Object> normalizePatch(Map<String, Object> raw) {
        Map<String, Object> source = raw == null ? Map.of() : raw;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", source.get("nodes"));
        out.put("edges", source.get("edges"));
        out.put("removedNodeIds", source.get("removedNodeIds"));
        out.put("removedEdgeIds", source.get("removedEdgeIds"));
        return out;
    }

    /** 列表 → 以 {@code id} 为键的有序索引；缺 id 或非 Map 的条目直接跳过。 */
    private static Map<String, Map<String, Object>> indexById(Object rawList) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (!(rawList instanceof List<?> list)) return out;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            map.forEach((key, value) -> entry.put(String.valueOf(key), value));
            String id = entry.get("id") == null ? "" : String.valueOf(entry.get("id"));
            if (id.isBlank()) continue;
            out.put(id, entry);
        }
        return out;
    }

    /** 字符串列表 → 有序去重集合。 */
    private static Set<String> idSet(Object rawList) {
        Set<String> out = new LinkedHashSet<>();
        if (!(rawList instanceof List<?> list)) return out;
        for (Object item : list) {
            if (item == null) continue;
            String id = String.valueOf(item);
            if (!id.isBlank()) out.add(id);
        }
        return out;
    }

    private static int serializedLength(Map<String, Object> patch) {
        try {
            return JSON.writeValueAsString(patch).length();
        } catch (Exception ignored) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 补丁累积结果。
     *
     * @param patch    合并后的补丁（形状固定为 nodes/edges/removedNodeIds/removedEdgeIds）
     * @param changed  是否有可写回 metadata 的变化
     * @param overflow 是否超过上限（超限时调用方清空补丁并打 overflow 标记）
     */
    record PatchAccumulation(Map<String, Object> patch, boolean changed, boolean overflow) {
    }

    @Transactional
    public Map<String, Object> recordGraphResult(String runId,
                                                 String operationKey,
                                                 Map<String, Object> result) {
        Map<String, Object> existing = findOperationResult(runId, operationKey);
        if (existing != null) return existing;
        updateFromGraphResult(runId, result);
        try {
            return rememberOperationInternal(runId, operationKey, result);
        } catch (DuplicateKeyException duplicate) {
            Map<String, Object> winner = findOperationResult(runId, operationKey);
            return winner == null ? (result == null ? Map.of() : result) : winner;
        }
    }

    @Transactional
    public Map<String, Object> rememberOperation(String runId,
                                                 String operationKey,
                                                 Map<String, Object> result) {
        Map<String, Object> existing = findOperationResult(runId, operationKey);
        if (existing != null) return existing;

        return rememberOperationInternal(runId, operationKey, result);
    }

    private Map<String, Object> rememberOperationInternal(String runId,
                                                          String operationKey,
                                                          Map<String, Object> result) {
        AgentCanvasRunOperationEntity operation = new AgentCanvasRunOperationEntity();
        operation.setRunId(runId);
        operation.setOperationKey(operationKey);
        operation.setResultJson(writeMap(result));
        operationMapper.insert(operation);
        return result == null ? Map.of() : result;
    }

    public Map<String, Object> findOperationResult(String runId, String operationKey) {
        if (runId == null || runId.isBlank()
                || operationKey == null || operationKey.isBlank()) {
            return null;
        }
        AgentCanvasRunOperationEntity existing = operationMapper.selectOne(
                new LambdaQueryWrapper<AgentCanvasRunOperationEntity>()
                        .eq(AgentCanvasRunOperationEntity::getRunId, runId)
                        .eq(AgentCanvasRunOperationEntity::getOperationKey, operationKey)
                        .last("LIMIT 1"));
        return existing == null ? null : readMap(existing.getResultJson());
    }

    @Transactional
    public CanvasRunView complete(String runId, Map<String, Object> summary) {
        AgentCanvasRunEntity entity = runId == null || runId.isBlank()
                ? null : runMapper.selectById(runId);
        if (entity == null) return null;
        entity.setStatus(COMPLETED);
        entity.setProgress(100);
        entity.setNodeCount(integer(summary == null ? null : summary.get("nodeCount"), entity.getNodeCount()));
        entity.setEdgeCount(integer(summary == null ? null : summary.get("edgeCount"), entity.getEdgeCount()));
        entity.setRevision(integer(summary == null ? null : summary.get("revision"), entity.getRevision()));
        entity.setCompletedAt(OffsetDateTime.now());
        entity.setError(null);
        runMapper.updateById(entity);
        updateMessage(entity, summary, COMPLETED, 100);
        return view(entity);
    }

    @Transactional
    public CanvasRunView fail(String runId, String error) {
        AgentCanvasRunEntity entity = runId == null || runId.isBlank()
                ? null : runMapper.selectById(runId);
        if (entity == null) return null;
        entity.setStatus(FAILED);
        entity.setError(error == null || error.isBlank() ? "画布任务失败" : error);
        entity.setCompletedAt(OffsetDateTime.now());
        runMapper.updateById(entity);
        updateMessage(entity, Map.of("error", entity.getError()), FAILED, entity.getProgress());
        return view(entity);
    }

    private static CanvasRunView view(AgentCanvasRunEntity entity) {
        return new CanvasRunView(
                entity.getId(),
                entity.getConversationId(),
                entity.getProjectId(),
                entity.getTurnId(),
                entity.getCanvasId(),
                entity.getTitle(),
                entity.getStatus(),
                entity.getProgress() == null ? 0 : entity.getProgress(),
                entity.getNodeCount() == null ? 0 : entity.getNodeCount(),
                entity.getEdgeCount() == null ? 0 : entity.getEdgeCount(),
                entity.getRevision() == null ? 0 : entity.getRevision(),
                readMaps(entity.getPlanJson()),
                entity.getError(),
                entity.getCreatedAt(),
                entity.getCompletedAt());
    }

    private void updateMessage(AgentCanvasRunEntity entity,
                               Map<String, Object> payload,
                               String status,
                               int progress) {
        AgentMessage message = entity.getMessageId() == null
                ? null : messageMapper.selectById(entity.getMessageId());
        if (message == null) return;
        Map<String, Object> metadata = new LinkedHashMap<>(
                message.getMetadata() == null ? Map.of() : message.getMetadata());
        Map<String, Object> values = payload == null ? Map.of() : payload;
        metadata.put("status", status);
        metadata.put("progress", progress);
        copyInteger(values, metadata, "revision");
        copyInteger(values, metadata, "nodeCount");
        copyInteger(values, metadata, "edgeCount");
        if (entity.getError() != null && !entity.getError().isBlank()) {
            metadata.put("error", entity.getError());
        }
        // turn 收尾（complete/fail）：补丁只服务于「进行中的增量上屏」，turn 一结束前端已应用完毕。
        // 留在 metadata 里会让**每一次**消息读取都白走一遍 FileUrlSigner 的递归签名，必须清掉。
        metadata.remove(METADATA_CANVAS_PATCH);
        metadata.remove(METADATA_CANVAS_PATCH_OVERFLOW);
        message.setMetadata(metadata);
        messageMapper.updateById(message);
    }

    private static List<Map<String, Object>> readMaps(String raw) {
        try {
            return JSON.readValue(raw == null ? "[]" : raw,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private static String writeMap(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private static Map<String, Object> readMap(String raw) {
        try {
            return JSON.readValue(raw == null ? "{}" : raw,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static int integer(Object value, Integer fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? (fallback == null ? 0 : fallback)
                    : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback == null ? 0 : fallback;
        }
    }

    private static void copyInteger(Map<String, Object> source,
                                    Map<String, Object> target,
                                    String key) {
        if (source.get(key) instanceof Number number) target.put(key, number.intValue());
    }

    private static String normalizeTitle(String title) {
        return title == null || title.isBlank() ? "AI 设计画布" : title.trim();
    }

    public record CanvasRunView(
            String runId,
            String conversationId,
            String projectId,
            String turnId,
            String canvasId,
            String title,
            String status,
            int progress,
            int nodeCount,
            int edgeCount,
            int revision,
            List<Map<String, Object>> plan,
            String error,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", "canvas-run");
            out.put("canvasRunId", runId);
            out.put("conversationId", conversationId);
            out.put("projectId", projectId);
            out.put("turnId", turnId);
            out.put("canvasId", canvasId);
            out.put("title", title);
            out.put("status", status);
            out.put("progress", progress);
            out.put("nodeCount", nodeCount);
            out.put("edgeCount", edgeCount);
            out.put("revision", revision);
            out.put("plan", plan);
            if (error != null && !error.isBlank()) out.put("error", error);
            return out;
        }
    }
}
