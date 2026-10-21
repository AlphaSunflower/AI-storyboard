package com.moon.moonagent.service.notification;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.moon.moonagent.dto.response.UserNotificationVO;
import com.moon.moonagent.entity.UserNotification;
import com.moon.moonagent.mapper.UserNotificationMapper;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 站内通知：先落库再谈投递，保证「定时任务跑完了」这件事一定能在应用内看到。
 *
 * <p>{@code dedupeKey} 唯一索引让同一次执行的重试/重复写入天然幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final UserNotificationMapper notificationMapper;

    public UserNotificationVO create(String userId, String type, String title, String body,
                                     String linkType, String linkId, String dedupeKey) {
        UserNotification notification = new UserNotification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(trim(title, 200));
        notification.setBody(trim(body, 2000));
        notification.setLinkType(linkType);
        notification.setLinkId(linkId);
        notification.setDedupeKey(dedupeKey);
        notification.setCreatedAt(OffsetDateTime.now());
        try {
            notificationMapper.insert(notification);
        } catch (DuplicateKeyException duplicate) {
            log.debug("通知已存在，跳过重复写入: dedupeKey={}", dedupeKey);
            return null;
        }
        return UserNotificationVO.from(notification);
    }

    public List<UserNotificationVO> list(String userId, int limit) {
        int size = Math.min(Math.max(limit, 1), 100);
        return notificationMapper.selectList(Wrappers.<UserNotification>lambdaQuery()
                        .eq(UserNotification::getUserId, userId)
                        .orderByDesc(UserNotification::getCreatedAt)
                        .last("limit " + size))
                .stream()
                .map(UserNotificationVO::from)
                .toList();
    }

    public long unreadCount(String userId) {
        Long count = notificationMapper.selectCount(Wrappers.<UserNotification>lambdaQuery()
                .eq(UserNotification::getUserId, userId)
                .isNull(UserNotification::getReadAt));
        return count == null ? 0L : count;
    }

    public void markRead(String userId, String notificationId) {
        UserNotification notification = notificationMapper.selectById(notificationId);
        if (notification == null || !userId.equals(notification.getUserId())) {
            throw new BusinessException(40401, "通知不存在或无权访问");
        }
        if (notification.getReadAt() != null) return;
        notificationMapper.update(null, Wrappers.<UserNotification>lambdaUpdate()
                .eq(UserNotification::getId, notificationId)
                .set(UserNotification::getReadAt, OffsetDateTime.now()));
    }

    public int markAllRead(String userId) {
        return notificationMapper.update(null, Wrappers.<UserNotification>lambdaUpdate()
                .eq(UserNotification::getUserId, userId)
                .isNull(UserNotification::getReadAt)
                .set(UserNotification::getReadAt, OffsetDateTime.now()));
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
