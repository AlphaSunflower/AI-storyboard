package com.moon.moonagent.dto.response;

import com.moon.moonagent.entity.UserNotification;

import java.time.OffsetDateTime;

/** 站内通知视图。 */
public record UserNotificationVO(
        String id,
        String type,
        String title,
        String body,
        String linkType,
        String linkId,
        OffsetDateTime readAt,
        OffsetDateTime createdAt
) {
    public static UserNotificationVO from(UserNotification notification) {
        return new UserNotificationVO(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getLinkType(),
                notification.getLinkId(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }
}
