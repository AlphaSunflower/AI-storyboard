package com.moon.moonagent.ai.agent.memory;

import com.moon.moonagent.entity.AgentGlobalMemory;
import com.moon.moonagent.entity.AgentMemory;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 项目级长期记忆 + 账号级全局记忆。
 *
 * <p>分工：项目记忆由智能体在对话中显式写入，用户只能查看/删除；全局记忆只有用户能改、
 * 智能体只读。记忆管理总开关关闭后，写与召回都被拒绝，但删除仍可用。
 */
public interface AgentMemoryService {

    /** 写入或合并一条项目记忆（同用户同项目同内容哈希去重）；记忆管理关闭时拒绝写入。 */
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

    /** 修改当前用户的一条记忆；不存在/不属于该用户返回 null；记忆管理关闭时拒绝。 */
    AgentMemory updateForUser(String userId, String memoryId, String kind, String content,
                              Double confidence, OffsetDateTime expiresAt);

    /** 注入系统提示词的项目记忆块；无记忆返回空串。 */
    String promptContext(String projectId, String query, int limit);

    /** 当前生效的容量上限（接口与提示词共用的单一真源）。 */
    AgentMemoryLimits limits();

    /** 记忆管理是否启用；缺行视为启用。 */
    boolean enabled(String userId);

    /** 打开/关闭记忆管理。 */
    void setEnabled(String userId, boolean enabled);

    /** 当前账号的全局记忆；未设置返回 null。 */
    AgentGlobalMemory global(String userId);

    /** 保存（新增或覆盖）全局记忆；记忆管理关闭时拒绝。 */
    AgentGlobalMemory saveGlobal(String userId, String content);

    /** 清空全局记忆。 */
    boolean clearGlobal(String userId);

    /** 注入系统提示词的全局记忆正文；未设置或记忆管理关闭时返回空串。 */
    String globalPromptContext(String userId);
}
