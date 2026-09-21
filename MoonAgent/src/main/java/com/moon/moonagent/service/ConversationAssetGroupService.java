package com.moon.moonagent.service;

import com.moon.moonagent.dto.response.ConversationAssetGroupVO;

import java.util.List;

/** 会话级资产组绑定：快照冻结、过期检测和手动刷新。 */
public interface ConversationAssetGroupService {

    List<ConversationAssetGroupVO> list(String userId, String conversationId);

    ConversationAssetGroupVO attach(String userId, String conversationId, String groupId);

    void remove(String userId, String conversationId, String groupId);
}
