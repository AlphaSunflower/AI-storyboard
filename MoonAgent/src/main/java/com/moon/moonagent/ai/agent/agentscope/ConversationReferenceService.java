package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * {@code @对话} 引用解析：只允许读取「同一用户 + 同一项目」的其他会话。
 *
 * <p>这里不生成 LLM 摘要，而是取最近若干条 user/assistant 消息做确定性摘录。
 * 这样引用结果可复现、无额外模型成本，也不会把工具事件和内部 metadata 带进上下文。
 */
@Service
@RequiredArgsConstructor
public class ConversationReferenceService {

    static final int MAX_MESSAGES = 30;
    static final int MAX_CHARS = 3200;
    static final int MAX_MESSAGE_CHARS = 600;

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;

    public ConversationReferenceView resolve(String currentUserId, String currentProjectId,
                                              String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return ConversationReferenceView.unresolved("会话 ID 为空");
        }
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return ConversationReferenceView.unresolved("会话不存在或已删除");
        }
        if (currentUserId == null || !Objects.equals(currentUserId, conversation.getUserId())) {
            return ConversationReferenceView.unresolved("无权读取该会话");
        }
        if (currentProjectId == null || !Objects.equals(currentProjectId, conversation.getProjectId())) {
            return ConversationReferenceView.unresolved("该会话不在当前项目");
        }
        if ("temporary".equalsIgnoreCase(conversation.getStatus())) {
            return ConversationReferenceView.unresolved("临时会话不可引用");
        }

        List<AgentMessage> recent = new java.util.ArrayList<>(messageMapper.selectList(
                new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getConversationId, conversationId)
                        .in(AgentMessage::getRole, "user", "assistant")
                        .orderByDesc(AgentMessage::getCreatedAt)
                        .last("LIMIT " + MAX_MESSAGES)));
        Collections.reverse(recent);
        Long total = messageMapper.selectCount(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId));
        return ConversationReferenceView.resolved(
                conversation.getId(),
                conversation.getTitle(),
                total == null ? 0 : total.intValue(),
                excerpt(recent));
    }

    private static String excerpt(List<AgentMessage> messages) {
        StringBuilder text = new StringBuilder();
        for (AgentMessage message : messages) {
            String content = message.getContent();
            if (content == null || content.isBlank()) continue;
            String role = "user".equals(message.getRole()) ? "用户" : "助手";
            String line = "【" + role + "】" + content.strip()
                    .replace('\r', ' ').replace('\n', ' ').replaceAll(" {2,}", " ");
            if (line.length() > MAX_MESSAGE_CHARS) {
                line = line.substring(0, MAX_MESSAGE_CHARS) + "…";
            }
            int remaining = MAX_CHARS - text.length();
            if (remaining <= 1) break;
            if (line.length() > remaining) {
                text.append(line, 0, Math.max(0, remaining - 1)).append('…');
                break;
            }
            if (!text.isEmpty()) text.append('\n');
            text.append(line);
        }
        return text.toString();
    }

    /** 解析结果；失败时 {@code reason} 明确说明原因，调用方不得编造内容。 */
    public record ConversationReferenceView(boolean resolved, String reason, String conversationId,
                                            String title, int messageCount, String excerpt) {
        static ConversationReferenceView unresolved(String reason) {
            return new ConversationReferenceView(false, reason, "", "", 0, "");
        }

        static ConversationReferenceView resolved(String conversationId, String title,
                                                   int messageCount, String excerpt) {
            return new ConversationReferenceView(true, "", conversationId, title, messageCount, excerpt);
        }
    }
}
