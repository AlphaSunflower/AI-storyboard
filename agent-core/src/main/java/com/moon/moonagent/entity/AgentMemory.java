package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 项目级/会话级长期记忆。只存用户或智能体显式写入的事实，不自动灌对话。
 */
@Data
@TableName(value = "agent_memories", schema = "public")
public class AgentMemory {

    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    private String projectId;
    private String conversationId;
    private String kind;
    private String content;
    private String contentHash;
    private String source;
    private Double confidence;
    private OffsetDateTime expiresAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
