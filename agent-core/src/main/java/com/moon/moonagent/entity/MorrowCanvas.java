package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "morrow_canvases", schema = "public")
public class MorrowCanvas {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private String title;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
