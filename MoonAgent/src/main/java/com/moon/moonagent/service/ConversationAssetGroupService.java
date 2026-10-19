package com.moon.moonagent.service;

import com.moon.moonagent.dto.response.ConversationAssetGroupVO;
import com.moon.moonagent.dto.response.ProjectAssetGroupBindingVO;

import java.util.List;

/** 会话级资产组绑定：快照冻结、过期检测和手动刷新。 */
public interface ConversationAssetGroupService {

    List<ConversationAssetGroupVO> list(String userId, String conversationId);

    /**
     * 项目维度的绑定关系：资产组被哪些对话使用。
     * 只读绑定行，不创建新快照，供项目库展示使用情况。
     */
    List<ProjectAssetGroupBindingVO> listProjectBindings(String userId, String projectId);

    ConversationAssetGroupVO attach(String userId, String conversationId, String groupId);

    void remove(String userId, String conversationId, String groupId);
}
