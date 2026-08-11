package com.storyboard.service;

import com.storyboard.dto.request.CreateProjectRequest;
import com.storyboard.dto.request.UpdateProjectRequest;
import com.storyboard.dto.response.ProjectResponse;

import java.util.List;

/**
 * 项目服务接口：项目列表、创建、查询、更新、删除、最近草稿。
 */
public interface ProjectService {

    /** 查询指定用户的项目列表。 */
    List<ProjectResponse> listByUser(String userId);

    /** 创建项目（缺省名称「未命名项目」、类型 movie、比例 16:9、状态 draft）。 */
    ProjectResponse create(String userId, CreateProjectRequest request);

    /** 按 ID 查询项目（校验归属，不存在或非本人抛 40401）。 */
    ProjectResponse getById(String userId, String projectId);

    /** 更新项目（仅更新请求中非 null 的字段）。 */
    ProjectResponse update(String userId, String projectId, UpdateProjectRequest request);

    /** 删除项目（校验归属）。 */
    void delete(String userId, String projectId);

    /** 查询用户最近一次草稿项目（无则返回 null）。 */
    ProjectResponse getLatestDraft(String userId);
}
