package com.storyboard.controller;

import com.storyboard.dto.request.CreateProjectRequest;
import com.storyboard.dto.request.UpdateProjectRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.ProjectResponse;
import com.storyboard.service.ProjectService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public ApiResponse<List<ProjectResponse>> list(Authentication auth) {
        return ApiResponse.ok(projectService.listByUser(auth.getName()));
    }

    @PostMapping
    public ApiResponse<ProjectResponse> create(Authentication auth, @RequestBody CreateProjectRequest request) {
        return ApiResponse.ok(projectService.create(auth.getName(), request));
    }

    @GetMapping("/draft")
    public ApiResponse<ProjectResponse> getDraft(Authentication auth) {
        ProjectResponse draft = projectService.getLatestDraft(auth.getName());
        return ApiResponse.ok(draft);
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectResponse> get(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(projectService.getById(auth.getName(), id));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProjectResponse> update(Authentication auth, @PathVariable String id,
                                                @RequestBody UpdateProjectRequest request) {
        return ApiResponse.ok(projectService.update(auth.getName(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable String id) {
        projectService.delete(auth.getName(), id);
        return ApiResponse.ok("删除成功", null);
    }
}
