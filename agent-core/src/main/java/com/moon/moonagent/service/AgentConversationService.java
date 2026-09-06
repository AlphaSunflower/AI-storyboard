package com.moon.moonagent.service;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;

import java.util.List;

/**
 * 共享会话数据服务——画布(Morrow)与 ai分镜/MoonAgent 复用同一张 conversations / agent_messages 表。
 * 会话按 (userId, projectId, active) 取或建，保证同一项目跨端共享同一对话。
 */
public interface AgentConversationService {

    /**
     * 按 (userId, projectId) 取该项目的活跃会话（无则建一条默认）。
     *
     * @param userId    属主
     * @param projectId 项目（画布或 ai分镜 的项目 id）
     * @return 活跃会话（已落库）
     */
    AgentConversation createOrGetActive(String userId, String projectId);

    /**
     * 追加一条消息到会话。
     *
     * @param conversationId 会话 id
     * @param role           user | assistant
     * @param content        消息正文
     */
    void persistMessage(String conversationId, String role, String content);

    /**
     * 追加一条消息到会话（带结构化元数据）。
     *
     * @param conversationId 会话 id
     * @param role           user | assistant
     * @param content        消息正文
     * @param metadata       结构化元数据（HITL/推理/trace 等；null 时存空对象）
     */
    void persistMessage(String conversationId, String role, String content, java.util.Map<String, Object> metadata);

    /**
     * 按时间正序取会话消息历史。
     *
     * @param conversationId 会话 id
     * @return 消息列表（created_at 升序）
     */
    List<AgentMessage> listMessages(String conversationId);
}
