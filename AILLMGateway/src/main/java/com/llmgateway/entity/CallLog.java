package com.llmgateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

/** 调用日志表（异步落库；video_url 暂存 MiniMax 限时直链供下载端点使用；日志不做逻辑删除，故无 updatedAt/deleted） */
@Data
@TableName("call_log")
public class CallLog {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String model;
    private String channelId;
    private String status;
    private Long durationMs;
    private String error;
    /** 视频 succeeded 时暂存 content.url（限时） */
    private String videoUrl;
    /** 视频任务 ID：下载端点按 taskId+channelId 精确定位限时直链（同渠道并发任务不串号） */
    private String taskId;
    private OffsetDateTime createdAt;
}
