package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 账号级全局记忆：每个账号最多一条，由用户自己维护，跨项目生效。
 *
 * <p>与 {@link AgentMemory} 的分工：项目记忆由智能体在对话中写入、用户只能删除；
 * 全局记忆只有用户能改，智能体只读。
 */
@Data
@TableName(value = "agent_global_memories", schema = "public")
public class AgentGlobalMemory {

    @TableId(type = IdType.INPUT)
    private String userId;
    private String content;
    private String contentHash;
    private OffsetDateTime updatedAt;
}
