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
    /** 跨重试复用的逻辑调用 ID，用于串联网关日志 */
    private String callId;
    /** 视频 succeeded 时暂存 content.url（限时） */
    private String videoUrl;
    /** 视频任务 ID：下载端点按 taskId+channelId 精确定位限时直链（同渠道并发任务不串号） */
    private String taskId;
    /** 实际发给上游的模型名（配置了上游映射时与 model 不同） */
    private String upstreamModel;
    /** 第几次尝试（从 1 开始；含渠道切换与重试） */
    private Integer attempt;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private OffsetDateTime createdAt;
}
