package com.moon.xiaoyuecanvas.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.xiaoyuecanvas.dto.response.CanvasGraphResponse;
import com.moon.xiaoyuecanvas.entity.CanvasGraph;
import com.moon.xiaoyuecanvas.mapper.CanvasGraphMapper;
import com.moon.xiaoyuecanvas.service.CanvasGraphService;
import com.moon.xiaoyuecanvas.util.JsonUtil;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CanvasGraphServiceImpl implements CanvasGraphService {

    private final CanvasGraphMapper canvasGraphMapper;
    private final MorrowCanvasService canvasService;

    /** 空图：CanvasGraphV2 {schemaVersion, nodes, edges, viewport, generationRecords}。 */
    private static final Map<String, Object> EMPTY_GRAPH = Map.of(
            "schemaVersion", 2,
            "nodes", List.of(),
            "edges", List.of(),
            "viewport", Map.of("x", 0, "y", 0, "zoom", 1),
            "generationRecords", List.of());

    @Override
    public CanvasGraphResponse load(String userId, String canvasId) {
        canvasService.get(userId, canvasId); // 属主校验
        CanvasGraph g = canvasGraphMapper.selectById(canvasId);
        if (g == null) {
            return new CanvasGraphResponse(EMPTY_GRAPH, 0, null);
        }
        return new CanvasGraphResponse(parseGraph(g.getGraphJson()), g.getRevision(), g.getUpdatedAt() == null ? null : g.getUpdatedAt().toString());
    }

    @Override
    public CanvasGraphResponse save(String userId, String canvasId, Map<String, Object> graph, Integer baseRevision) {
        canvasService.get(userId, canvasId); // 属主校验
        Map<String, Object> payload = graph == null ? EMPTY_GRAPH : graph;
        String json = JsonUtil.toJson(payload);

        CanvasGraph existing = canvasGraphMapper.selectById(canvasId);
        int currentRevision = existing == null ? 0 : existing.getRevision();

        // 乐观锁：baseRevision 与当前不一致 → 冲突，message 携带当前 revision 供前端重放/刷新
        if (existing != null && baseRevision != null && !baseRevision.equals(currentRevision)) {
            throw new BusinessException(40901, "画布已被他人修改，请刷新后重试|revision=" + currentRevision);
        }

        if (existing == null) {
            // 首次保存（或无记录）：插入 revision 0 → 1
            CanvasGraph g = new CanvasGraph();
            g.setCanvasId(canvasId);
            g.setGraphJson(json);
            g.setRevision(1);
            canvasGraphMapper.insert(g);
            return new CanvasGraphResponse(payload, 1, g.getUpdatedAt() == null ? null : g.getUpdatedAt().toString());
        }

        // 更新：CAS WHERE canvas_id AND revision = currentRevision，成功才 revision+1
        int rows = canvasGraphMapper.update(null, new LambdaUpdateWrapper<CanvasGraph>()
                .eq(CanvasGraph::getCanvasId, canvasId)
                .eq(CanvasGraph::getRevision, currentRevision)
                .set(CanvasGraph::getGraphJson, json)
                .set(CanvasGraph::getRevision, currentRevision + 1));
        if (rows == 0) {
            // 并发兜底：读最新 revision 抛冲突
            CanvasGraph fresh = canvasGraphMapper.selectById(canvasId);
            int latest = fresh == null ? 0 : fresh.getRevision();
            throw new BusinessException(40901, "画布已被他人修改，请刷新后重试|revision=" + latest);
        }
        CanvasGraph updated = canvasGraphMapper.selectById(canvasId);
        return new CanvasGraphResponse(payload, updated.getRevision(), updated.getUpdatedAt() == null ? null : updated.getUpdatedAt().toString());
    }

    private Map<String, Object> parseGraph(String json) {
        Map<String, Object> map = new java.util.HashMap<>(JsonUtil.parse(json));
        // 补齐缺省键，保证三键结构稳定
        map.putIfAbsent("schemaVersion", 2);
        map.putIfAbsent("nodes", List.of());
        map.putIfAbsent("edges", List.of());
        map.putIfAbsent("viewport", Map.of("x", 0, "y", 0, "zoom", 1));
        map.putIfAbsent("generationRecords", List.of());
        return map;
    }
}
