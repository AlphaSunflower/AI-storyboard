package com.moon.xiaoyuecanvas.controller;

import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.entity.Project;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.moonagent.service.ProjectService;
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

    @PostMapping("/projects")
    public Map<String, Object> create(@RequestAttribute("userId") String userId, @RequestBody Map<String, String> body) {
        return toProject(projectService.create(userId, body.get("title")));
    }

    @GetMapping("/projects")
    public List<Map<String, Object>> list(@RequestAttribute("userId") String userId) {
        return projectService.listMine(userId).stream().map(this::toProject).toList();
    }

    @GetMapping("/projects/{id}")
    public Map<String, Object> get(@RequestAttribute("userId") String userId, @PathVariable String id) {
        return toProject(projectService.get(userId, id));
    }

    @PatchMapping("/projects/{id}")
    public Map<String, Object> rename(@RequestAttribute("userId") String userId, @PathVariable String id, @RequestBody Map<String, String> body) {
        return toProject(projectService.rename(userId, id, body.get("title")));
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
        return canvasService.listByProject(userId, id).stream().map(this::toCanvas).toList();
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

    private Map<String, Object> toProject(Project p) {
        if (p == null) throw new BusinessException(40401, "项目不存在或无权访问");
        return Map.of("id", p.getId(), "title", p.getName() == null ? "" : p.getName(),
                "updatedAt", p.getUpdatedAt() == null ? "" : p.getUpdatedAt().toString());
    }

    private Map<String, Object> toCanvas(MorrowCanvas c) {
        return Map.of("id", c.getId(), "projectId", c.getProjectId(),
                "title", c.getTitle() == null ? "" : c.getTitle(),
                "updatedAt", c.getUpdatedAt() == null ? "" : c.getUpdatedAt().toString());
    }
}
