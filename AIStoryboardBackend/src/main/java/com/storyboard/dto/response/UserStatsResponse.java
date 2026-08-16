package com.storyboard.dto.response;

/**
 * 用户统计 VO：生成图片数 / 生成视频数 / 项目总数。
 */
public record UserStatsResponse(
    long imageCount,
    long videoCount,
    long projectCount
) {}
