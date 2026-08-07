package com.storyboard.dto.request;

/** 图生视频方案确认后生成请求（video_plan 事件「开始生成视频」按钮触发） */
public record AgentVideoPlanGenerateRequest(
    String planToken   // video_plan 事件返回的一次性方案令牌（消费即移除，防重放）
) {}
