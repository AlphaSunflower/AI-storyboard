package com.moon.xiaoyuecanvas.controller;

import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.entity.Project;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.moonagent.service.ProjectService;
import com.moon.xiaoyuecanvas.service.CanvasGraphService;
import com.moon.xiaoyuecanvas.service.CanvasSummaryService;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Morrow 项目/画布实体 CRUD——Morrow 项目即为 ai分镜 projects 行（同实体共享库），画布元数据落 morrow_canvases。
 * 节点实体内容仍在浏览器 IndexedDB（按画布 id），本地仅做缓存。
 */
@RestController
@RequiredArgsConstructor
public class MorrowProjectController {

    private final ProjectService projectService;
    private final MorrowCanvasService canvasService;
    private final CanvasSummaryService summaryService;
    private final CanvasGraphService graphService;

    @PostMapping("/projects")
    public Map<String, Object> create(@RequestAttribute("userId") String userId, @RequestBody Map<String, String> body) {
        return toProject(projectService.create(userId, body.get("title")));
    }

    @GetMapping("/projects")
    public List<Map<String, Object>> list(@RequestAttribute("userId") String userId) {
        return projectService.listMine(userId).stream()
                .map(project -> toProject(project, summaryService.project(project.getId())))
                .toList();
    }

    @GetMapping("/projects/{id}")
    public Map<String, Object> get(@RequestAttribute("userId") String userId, @PathVariable String id) {
        Project project = projectService.get(userId, id);
        return toProject(project, summaryService.project(project.getId()));
    }

    @PatchMapping("/projects/{id}")
    public Map<String, Object> rename(@RequestAttribute("userId") String userId, @PathVariable String id, @RequestBody Map<String, String> body) {
        Project project = projectService.rename(userId, id, body.get("title"));
        return toProject(project, summaryService.project(project.getId()));
    }

    @DeleteMapping("/projects/{id}")
    public Map<String, Object> delete(@RequestAttribute("userId") String userId, @PathVariable String id) {
        projectService.delete(userId, id);
        return Map.of("ok", true);
    }

    @PostMapping("/projects/{id}/canvases")
    public Map<String, Object> createCanvas(@RequestAttribute("userId") String userId, @PathVariable String id, @RequestBody Map<String, String> body) {
        return toCanvas(canvasService.create(userId, id, body.get("title")));
    }

    @GetMapping("/projects/{id}/canvases")
    public List<Map<String, Object>> canvases(@RequestAttribute("userId") String userId, @PathVariable String id) {
        var summaries = summaryService.byProject(id).stream()
                .collect(java.util.stream.Collectors.toMap(
                        CanvasSummaryService.CanvasSummary::canvasId, item -> item));
        return canvasService.listByProject(userId, id).stream()
                .map(canvas -> toCanvas(canvas, summaries.get(canvas.getId())))
                .toList();
    }

    @PatchMapping("/canvases/{id}")
    public Map<String, Object> renameCanvas(@RequestAttribute("userId") String userId, @PathVariable String id, @RequestBody Map<String, String> body) {
        return toCanvas(canvasService.rename(userId, id, body.get("title")));
    }

    @DeleteMapping("/canvases/{id}")
    public Map<String, Object> deleteCanvas(@RequestAttribute("userId") String userId, @PathVariable String id) {
        canvasService.delete(userId, id);
        return Map.of("ok", true);
    }

    /**
     * 只读当前图 revision：画布前端轮询它感知服务端图命令（智能体建节点/连线）带来的变化。
     * 不返回 graph_json，避免每次轮询搬运整幅图。
     */
    @GetMapping("/canvases/{id}/revision")
    public Map<String, Object> canvasRevision(@RequestAttribute("userId") String userId, @PathVariable String id) {
        return Map.of("revision", graphService.revision(userId, id));
    }

    private Map<String, Object> toProject(Project p) {
        return toProject(p, null);
    }

    private Map<String, Object> toProject(Project p, CanvasSummaryService.ProjectSummary summary) {
        if (p == null) throw new BusinessException(40401, "项目不存在或无权访问");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", p.getId());
        out.put("title", p.getName() == null ? "" : p.getName());
        out.put("updatedAt", p.getUpdatedAt() == null ? "" : p.getUpdatedAt().toString());
        out.put("canvasCount", summary == null ? 0 : summary.canvasCount());
        out.put("nodeCount", summary == null ? 0 : summary.nodeCount());
        out.put("edgeCount", summary == null ? 0 : summary.edgeCount());
        out.put("assetCount", summary == null ? 0 : summary.assetCount());
        return out;
    }

    private Map<String, Object> toCanvas(MorrowCanvas c) {
        return toCanvas(c, null);
    }

    private Map<String, Object> toCanvas(MorrowCanvas c, CanvasSummaryService.CanvasSummary summary) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", c.getId());
        out.put("projectId", c.getProjectId());
        out.put("title", c.getTitle() == null ? "" : c.getTitle());
        out.put("updatedAt", c.getUpdatedAt() == null ? "" : c.getUpdatedAt().toString());
        out.put("nodeCount", summary == null ? 0 : summary.nodeCount());
        out.put("edgeCount", summary == null ? 0 : summary.edgeCount());
        out.put("assetCount", summary == null ? 0 : summary.assetCount());
        out.put("coverAssetId", summary == null ? null : summary.coverAssetId());
        return out;
    }
}
