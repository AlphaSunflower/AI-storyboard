package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "conversations", schema = "public")
public class AgentConversation {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String projectId;
    private String title;
    private String status;
    /** 画布编辑模式 JSON（CanvasSessionContext）；空=未锁定画布链路。 */
    private String canvasContext;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
