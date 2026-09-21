package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 会话级资产组绑定；快照保持不可变，组变化后由用户手动刷新。 */
@Data
@TableName(value = "agent_conversation_asset_groups", schema = "public")
public class AgentConversationAssetGroup {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String conversationId;
    private String groupId;
    private String snapshotId;
    private Long groupVersion;
    private String contentHash;
    private String target;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
