package com.storyboard.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 反馈收件箱条目：正文、截图和提交人信息。
 */
public record FeedbackAdminResponse(
    String id,
    String userId,
    String userDisplayName,
    String userEmail,
    String content,
    List<String> imageUrls,
    String status,
    OffsetDateTime createdAt
) {}
