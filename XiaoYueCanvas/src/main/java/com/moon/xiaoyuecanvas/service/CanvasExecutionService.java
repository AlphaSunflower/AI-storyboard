package com.moon.xiaoyuecanvas.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.xiaoyuecanvas.dto.request.CanvasExecutionCreateRequest;
import com.moon.xiaoyuecanvas.dto.request.JobRequest;
import com.moon.xiaoyuecanvas.dto.response.CanvasExecutionResponse;
import com.moon.xiaoyuecanvas.dto.response.CanvasGraphResponse;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.moon.xiaoyuecanvas.entity.CanvasExecution;
import com.moon.xiaoyuecanvas.entity.CanvasExecutionNode;
import com.moon.xiaoyuecanvas.mapper.CanvasExecutionMapper;
import com.moon.xiaoyuecanvas.mapper.CanvasExecutionNodeMapper;
import com.moon.xiaoyuecanvas.util.JsonUtil;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 画布「执行分组」的服务端执行器。
 *
 * <p>以前批次跑在浏览器里：刷新页面 / Vite HMR / 断网都会把循环掐断，用户看到「只生成了第一个节点」。
 * 现在批次和每个节点的状态都落库，由服务端按顺序推进；浏览器只轮询快照渲染进度，
 * 关掉页面也不会影响执行，重新打开还能接着看（甚至接着跑）。
 *
 * <p>v1 范围：串行执行（maxParallel 固定 1）、失败即停并把剩余节点标 blocked、
 * 取消会同时取消当前 job。并发调度与自动重试留给后续。
 */
@Service
@RequiredArgsConstructor
public class CanvasExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CanvasExecutionService.class);
    /** 单步上限：生视频最长 60 分钟，这里留足余量。 */
    private static final Duration STEP_TIMEOUT = Duration.ofMinutes(70);
    /** 单步失败重试次数（幂等键每次换后缀，拿到的是新的一次生成）。 */
    private static final int MAX_STEP_RETRIES = 1;
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String NODE_PENDING = "pending";
    private static final String NODE_RUNNING = "running";
    private static final String NODE_SUCCEEDED = "succeeded";
    private static final String NODE_FAILED = "failed";
    private static final String NODE_BLOCKED = "blocked";
    private static final String NODE_CANCELLED = "cancelled";

    private final CanvasExecutionMapper executionMapper;
    private final CanvasExecutionNodeMapper nodeMapper;
    private final DesignJobService designJobService;
    private final MorrowCanvasService canvasService;
    private final CanvasAssetService canvasAssetService;
    private final CanvasGraphService canvasGraphService;

    // ─────────────────────────── 对外 ───────────────────────────

    public CanvasExecutionResponse create(String userId, String canvasId, CanvasExecutionCreateRequest request) {
        canvasService.get(userId, canvasId); // 属主校验
        if (request.steps() == null || request.steps().isEmpty()) {
            throw new BusinessException(40001, "执行批次至少要有一个节点");
        }
        CanvasExecution execution = new CanvasExecution();
        execution.setCanvasId(canvasId);
        execution.setUserId(userId);
        execution.setStatus(STATUS_QUEUED);
        execution.setMaxParallel(1);
        executionMapper.insert(execution);

        int position = 0;
        for (CanvasExecutionCreateRequest.Step step : request.steps()) {
            CanvasExecutionNode node = new CanvasExecutionNode();
            node.setExecutionId(execution.getId());
            node.setPosition(position++);
            node.setNodeId(step.nodeId());
            node.setActionId(step.actionId() == null || step.actionId().isBlank() ? "generate" : step.actionId());
            node.setCapabilityId(step.capabilityId());
            node.setClientRequestId(step.clientRequestId() == null || step.clientRequestId().isBlank()
                    ? UUID.randomUUID().toString()
                    : step.clientRequestId());
            node.setParams(JsonUtil.toJson(step.params() == null ? Map.of() : step.params()));
            node.setSourceAssetIds(JsonUtil.toJson(step.sourceAssetIds() == null ? List.of() : step.sourceAssetIds()));
            node.setResultSlotNodeId(step.resultSlotNodeId());
            node.setStatus(NODE_PENDING);
            nodeMapper.insert(node);
        }
        Thread.startVirtualThread(() -> run(execution.getId(), userId, canvasId));
        return snapshot(execution);
    }

    public CanvasExecutionResponse get(String userId, String canvasId, String executionId) {
        return snapshot(owned(userId, canvasId, executionId));
    }

    /** 最近批次（新的在前）：前端刷新后用它恢复「正在跑的那一批」。 */
    public List<CanvasExecutionResponse> list(String userId, String canvasId) {
        canvasService.get(userId, canvasId);
        List<CanvasExecution> executions = executionMapper.selectList(
                new LambdaQueryWrapper<CanvasExecution>()
                        .eq(CanvasExecution::getCanvasId, canvasId)
                        .orderByDesc(CanvasExecution::getCreatedAt)
                        .last("LIMIT 10"));
        return executions.stream().map(this::snapshot).toList();
    }

    public CanvasExecutionResponse cancel(String userId, String canvasId, String executionId) {
        CanvasExecution execution = owned(userId, canvasId, executionId);
        if (!isFinal(execution.getStatus())) {
            execution.setStatus(STATUS_CANCELLED);
            execution.setError("批次已取消");
            executionMapper.updateById(execution);
            CanvasExecutionNode running = runningNode(executionId);
            if (running != null && running.getJobId() != null) {
                try {
                    designJobService.cancelDetached(running.getJobId());
                } catch (Exception e) {
                    log.warn("取消执行批次时取消作业失败 executionId={} jobId={}: {}",
                            executionId, running.getJobId(), e.getMessage());
                }
            }
        }
        return snapshot(owned(userId, canvasId, executionId));
    }

    // ─────────────────────────── 执行 ───────────────────────────

    void run(String executionId, String userId, String canvasId) {
        CanvasExecution execution = executionMapper.selectById(executionId);
        if (execution == null) return;
        execution.setStatus(STATUS_RUNNING);
        executionMapper.updateById(execution);
        try {
            String projectId = canvasService.get(userId, canvasId).getProjectId();
            runSteps(execution, projectId);
        } catch (Exception e) {
            log.error("执行批次失败 executionId={}", executionId, e);
            CanvasExecution failed = executionMapper.selectById(executionId);
            if (failed != null && !isFinal(failed.getStatus())) {
                failed.setStatus(STATUS_FAILED);
                failed.setError(readable(e));
                executionMapper.updateById(failed);
            }
        }
    }

    /**
     * 串行跑完所有步骤：失败即停（剩余节点标 blocked），取消则整批终止。
     * 包级可见，便于单测直接驱动。
     */
    void runSteps(CanvasExecution execution, String projectId) {
        String executionId = execution.getId();
        List<String> nodeIds = new ArrayList<>(orderedNodes(executionId).stream()
                .map(CanvasExecutionNode::getNodeId).toList());
        boolean failed = false;
        for (CanvasExecutionNode node : orderedNodes(executionId)) {
            CanvasExecution fresh = executionMapper.selectById(executionId);
            if (fresh != null && STATUS_CANCELLED.equals(fresh.getStatus())) {
                markRemaining(executionId, "批次已取消", NODE_CANCELLED);
                return;
            }
            if (failed) {
                node.setStatus(NODE_BLOCKED);
                node.setError("上游节点失败，本节点未执行");
                node.setCompletedAt(OffsetDateTime.now());
                nodeMapper.updateById(node);
                continue;
            }
            node.setStatus(NODE_RUNNING);
            node.setStartedAt(OffsetDateTime.now());
            nodeMapper.updateById(node);
            DesignJobService.ExecutionOutcome outcome = null;
            for (int attempt = 0; attempt <= MAX_STEP_RETRIES; attempt++) {
                // 幂等键每轮换一个后缀：同一 clientRequestId 重放会命中上一次失败/完成的作业，拿不到新的一次生成。
                String requestId = attempt == 0
                        ? node.getClientRequestId()
                        : node.getClientRequestId() + "-r" + attempt;
                try {
                    outcome = designJobService.submitAndWait(execution.getUserId(),
                            jobRequest(execution, node, projectId, nodeIds, requestId), STEP_TIMEOUT);
                } catch (Exception e) {
                    outcome = new DesignJobService.ExecutionOutcome(null, "failed", List.of(), readable(e));
                }
                if ("completed".equals(outcome.status()) || STATUS_CANCELLED.equals(outcome.status())) break;
                if (attempt < MAX_STEP_RETRIES) {
                    log.warn("步骤失败，重试 executionId={} nodeId={} attempt={} error={}",
                            executionId, node.getNodeId(), attempt + 1, outcome.error());
                }
            }
            node.setJobId(outcome.jobId());
            if ("completed".equals(outcome.status())) {
                node.setStatus(NODE_SUCCEEDED);
                node.setResultAssetIds(JsonUtil.toJson(outcome.assetIds() == null ? List.of() : outcome.assetIds()));
                attachResult(execution, node);
            } else if (STATUS_CANCELLED.equals(outcome.status())) {
                node.setStatus(NODE_CANCELLED);
                node.setError("任务已取消");
                failed = true;
            } else {
                node.setStatus(NODE_FAILED);
                node.setError(outcome.error() == null ? "生成失败" : outcome.error());
                failed = true;
            }
            node.setCompletedAt(OffsetDateTime.now());
            nodeMapper.updateById(node);
        }
        CanvasExecution latest = executionMapper.selectById(executionId);
        if (latest == null || STATUS_CANCELLED.equals(latest.getStatus())) return;
        latest.setStatus(failed ? STATUS_FAILED : STATUS_SUCCEEDED);
        if (failed) latest.setError("有节点执行失败，批次已停止");
        executionMapper.updateById(latest);
    }

    /**
     * 把生成结果直接挂回画布图（服务端完成，浏览器关着也生效）。
     *
     * <p>顺序：先把 agent_assets 映射成 canvas_assets（前端预览按画布资产 id 取图），
     * 再带 revision 乐观锁写图；冲突说明客户端刚保存过，重读后重试。
     */
    private void attachResult(CanvasExecution execution, CanvasExecutionNode step) {
        List<String> assetIds = step.getResultAssetIds() == null || step.getResultAssetIds().isBlank()
                ? List.of()
                : JsonUtil.parseList(step.getResultAssetIds());
        if (assetIds.isEmpty()) return;
        String canvasAssetId;
        try {
            List<CanvasAsset> linked = canvasAssetService.linkGeneration(
                    execution.getUserId(), execution.getCanvasId(), step.getNodeId(), assetIds.get(0));
            if (linked == null || linked.isEmpty()) return;
            canvasAssetId = linked.get(0).getId();
        } catch (Exception e) {
            log.warn("生成结果映射为画布资产失败 executionId={} nodeId={}: {}",
                    execution.getId(), step.getNodeId(), e.getMessage());
            return;
        }
        String targetId = step.getResultSlotNodeId() == null || step.getResultSlotNodeId().isBlank()
                ? step.getNodeId()
                : step.getResultSlotNodeId();
        for (int attempt = 0; attempt < 3; attempt++) {
            CanvasGraphResponse current;
            try {
                current = canvasGraphService.load(execution.getUserId(), execution.getCanvasId());
            } catch (Exception e) {
                log.warn("读取画布图失败 canvasId={}: {}", execution.getCanvasId(), e.getMessage());
                return;
            }
            List<Map<String, Object>> nodes = new ArrayList<>();
            if (current.graph() != null && current.graph().get("nodes") instanceof List<?> raw) {
                for (Object item : raw) {
                    if (item instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> node = new LinkedHashMap<>((Map<String, Object>) map);
                        nodes.add(node);
                    }
                }
            }
            if (nodes.isEmpty()) return;
            boolean touched = false;
            for (Map<String, Object> node : nodes) {
                Object id = node.get("id");
                boolean isTarget = targetId.equals(id);
                if (!isTarget && !step.getNodeId().equals(id)) continue;
                Map<String, Object> data = dataOf(node);
                if (isTarget) {
                    data.put("assetId", canvasAssetId);
                    data.put("mediaLoadState", "ready");
                    data.put("status", "success");
                }
                data.put("isGenerating", false);
                Map<String, Object> automation = automationOf(data);
                automation.put("phase", "done");
                automation.remove("error");
                touched = true;
            }
            if (!touched) return;
            Map<String, Object> graph = new LinkedHashMap<>(current.graph());
            graph.put("nodes", nodes);
            try {
                canvasGraphService.save(execution.getUserId(), execution.getCanvasId(), graph, current.revision());
                step.setAttachedAssetId(canvasAssetId);
                return;
            } catch (BusinessException conflict) {
                log.info("画布图 revision 冲突，重试挂载 canvasId={} attempt={}", execution.getCanvasId(), attempt + 1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> node) {
        Object data = node.get("data");
        if (data instanceof Map<?, ?> map) return (Map<String, Object>) map;
        Map<String, Object> created = new LinkedHashMap<>();
        node.put("data", created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> automationOf(Map<String, Object> data) {
        Object automation = data.get("automation");
        if (automation instanceof Map<?, ?> map) return (Map<String, Object>) map;
        Map<String, Object> created = new LinkedHashMap<>();
        data.put("automation", created);
        return created;
    }

    private JobRequest jobRequest(CanvasExecution execution, CanvasExecutionNode node,
                                  String projectId, List<String> nodeIds, String clientRequestId) {
        Map<String, Object> params = new LinkedHashMap<>(
                node.getParams() == null || node.getParams().isBlank() ? Map.of() : JsonUtil.parse(node.getParams()));
        // 生成服务按 canvas.producerNodeId 归集结果；客户端没带时在这里补齐。
        params.putIfAbsent("canvas", Map.of(
                "canvasId", execution.getCanvasId(),
                "producerNodeId", node.getNodeId()));
        List<String> sourceAssetIds = node.getSourceAssetIds() == null || node.getSourceAssetIds().isBlank()
                ? List.of()
                : JsonUtil.parseList(node.getSourceAssetIds());
        return new JobRequest(
                clientRequestId,
                null,
                node.getCapabilityId(),
                sourceAssetIds,
                new JobRequest.JobContext(projectId, nodeIds, node.getNodeId()),
                params);
    }

    private void markRemaining(String executionId, String error, String status) {
        for (CanvasExecutionNode node : orderedNodes(executionId)) {
            if (isNodeFinal(node.getStatus()) || NODE_RUNNING.equals(node.getStatus())) continue;
            node.setStatus(status);
            node.setError(error);
            node.setCompletedAt(OffsetDateTime.now());
            nodeMapper.updateById(node);
        }
    }

    // ─────────────────────────── 读取 ───────────────────────────

    private CanvasExecution owned(String userId, String canvasId, String executionId) {
        CanvasExecution execution = executionMapper.selectById(executionId);
        if (execution == null || !canvasId.equals(execution.getCanvasId()) || !userId.equals(execution.getUserId())) {
            throw new BusinessException(40401, "执行批次不存在或无权访问");
        }
        return execution;
    }

    private List<CanvasExecutionNode> orderedNodes(String executionId) {
        return nodeMapper.selectList(new LambdaQueryWrapper<CanvasExecutionNode>()
                .eq(CanvasExecutionNode::getExecutionId, executionId)
                .orderByAsc(CanvasExecutionNode::getPosition));
    }

    private CanvasExecutionNode runningNode(String executionId) {
        List<CanvasExecutionNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<CanvasExecutionNode>()
                .eq(CanvasExecutionNode::getExecutionId, executionId)
                .eq(CanvasExecutionNode::getStatus, NODE_RUNNING)
                .last("LIMIT 1"));
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    private CanvasExecutionResponse snapshot(CanvasExecution execution) {
        List<CanvasExecutionResponse.Node> nodes = orderedNodes(execution.getId()).stream()
                .map(node -> new CanvasExecutionResponse.Node(
                        node.getNodeId(),
                        node.getActionId(),
                        node.getStatus(),
                        node.getJobId(),
                        node.getResultAssetIds() == null || node.getResultAssetIds().isBlank()
                                ? List.of()
                                : JsonUtil.parseList(node.getResultAssetIds()),
                        node.getAttachedAssetId(),
                        node.getError(),
                        epoch(node.getStartedAt()),
                        epoch(node.getCompletedAt())))
                .toList();
        return new CanvasExecutionResponse(
                execution.getId(),
                execution.getCanvasId(),
                execution.getStatus(),
                execution.getMaxParallel(),
                execution.getError(),
                epoch(execution.getCreatedAt()),
                epoch(execution.getUpdatedAt()),
                nodes);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static Long epoch(OffsetDateTime time) {
        return time == null ? null : time.toInstant().toEpochMilli();
    }

    private static boolean isFinal(String status) {
        return STATUS_SUCCEEDED.equals(status) || STATUS_FAILED.equals(status) || STATUS_CANCELLED.equals(status);
    }

    private static boolean isNodeFinal(String status) {
        return NODE_SUCCEEDED.equals(status) || NODE_FAILED.equals(status)
                || NODE_BLOCKED.equals(status) || NODE_CANCELLED.equals(status);
    }

    private static String readable(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "执行失败" : message;
    }
}
