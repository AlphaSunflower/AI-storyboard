package com.moon.moonagent.service;

import com.moon.moonagent.dto.response.ProjectOutputMaterialGroup;

import java.util.List;

/** 项目产出素材聚合查询。 */
public interface ProjectOutputMaterialService {

    List<ProjectOutputMaterialGroup> list(String userId, String projectId, String currentConversationId);
}
