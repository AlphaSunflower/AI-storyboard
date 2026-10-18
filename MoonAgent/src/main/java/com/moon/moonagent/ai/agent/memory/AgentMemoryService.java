package com.moon.moonagent.ai.agent.memory;

import com.moon.moonagent.entity.AgentMemory;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 项目级长期记忆：显式写入、按项目隔离、可过期、可删除。
 */
public interface AgentMemoryService {

    /** 写入或合并一条记忆（同用户同项目同内容哈希去重）。 */
    AgentMemory remember(String userId, String projectId, String conversationId, String kind,
                         String content, String source, Double confidence,
                         OffsetDateTime expiresAt);

    /** 召回当前项目未过期的记忆；query 为空时按置信度/更新时间返回。 */
    List<AgentMemory> recall(String projectId, String query, int limit);

    /** 列出项目记忆（管理/排查用）。 */
    List<AgentMemory> list(String projectId, int limit);

    /** 列出当前用户全部项目记忆（个人信息页用）。 */
    List<AgentMemory> listByUser(String userId, int limit);

    /** 真删除一条项目记忆。 */
    boolean delete(String projectId, String memoryId);

    /** 真删除当前用户的一条记忆。 */
    boolean deleteForUser(String userId, String memoryId);

    /** 修改当前用户的一条记忆；不存在/不属于该用户返回 null。 */
    AgentMemory updateForUser(String userId, String memoryId, String kind, String content,
                              Double confidence, OffsetDateTime expiresAt);

    /** 注入系统提示词的紧凑块；无记忆返回空串。 */
    String promptContext(String projectId, String query, int limit);
}
