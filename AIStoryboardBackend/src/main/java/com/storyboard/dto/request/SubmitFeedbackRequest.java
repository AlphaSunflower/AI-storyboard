package com.storyboard.dto.request;

import java.util.List;

/**
 * 提交帮助与反馈：正文必填，截图最多 6 张。
 */
public record SubmitFeedbackRequest(
    String content,
    List<String> imageUrls
) {}
