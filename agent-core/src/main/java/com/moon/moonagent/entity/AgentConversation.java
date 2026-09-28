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
    /** 历史编排标记：LEGACY 仅用于只读归档识别；新会话固定 UNIFIED_LOOP。 */
    private String orchestrationMode;
    /** 引擎/状态协议版本，用于新旧检查点隔离。 */
    private String engineVersion;
    /** 用户端是否显示新旧编排对比开关。 */
    private Boolean debugComparisonEnabled;
    /** 会话级 AgentScope 审批模式：PLAN_APPROVAL / REQUEST_APPROVAL / FULL_ACCESS。 */
    private String approvalMode;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
