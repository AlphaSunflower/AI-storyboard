package com.moon.moonagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.service.AgentConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentConversationServiceImpl implements AgentConversationService {

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;

    @Override
    public synchronized AgentConversation createOrGetActive(String userId, String projectId) {
        // ponytail: 全局进程内锁串行 check-then-insert —— 单实例画布下防并发重复创建同一 (user, project) 活跃会话。
        AgentConversation existing = conversationMapper.selectOne(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getUserId, userId)
                .eq(AgentConversation::getProjectId, projectId)
                .eq(AgentConversation::getStatus, "active")
                .orderByDesc(AgentConversation::getUpdatedAt)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        AgentConversation conv = new AgentConversation();
        conv.setUserId(userId);
        conv.setProjectId(projectId);
        conv.setTitle("新对话");
        conv.setStatus("active");
        conversationMapper.insert(conv);
        return conv;
    }

    @Override
    public void persistMessage(String conversationId, String role, String content) {
        persistMessage(conversationId, role, content, null);
    }

    @Override
    public void persistMessage(String conversationId, String role, String content, java.util.Map<String, Object> metadata) {
        AgentMessage m = new AgentMessage();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        if (metadata != null) m.setMetadata(metadata);
        messageMapper.insert(m);
    }

    @Override
    public List<AgentMessage> listMessages(String conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .orderByAsc(AgentMessage::getCreatedAt));
    }
}
