package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_document_index", schema = "public")
public class AgentDocumentIndex {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String assetId;
    private String conversationId;
    private String originalName;
    private String mimeType;
    private String contentHash;
    private Integer charCount;
    private String summary;
    private String status;
    private String parserVersion;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
