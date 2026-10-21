package com.moon.moonagent.controller;

import com.moon.moonagent.dto.response.UserNotificationVO;
import com.moon.moonagent.service.notification.NotificationService;
import com.storyboard.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 站内通知：列表、未读数、已读标记。 */
@RestController
@RequestMapping("/api/agent/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<List<UserNotificationVO>> list(Authentication auth,
                                                      @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(notificationService.list(auth.getName(), limit));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount(Authentication auth) {
        return ApiResponse.ok(Map.of("count", notificationService.unreadCount(auth.getName())));
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<Boolean> markRead(Authentication auth, @PathVariable String notificationId) {
        notificationService.markRead(auth.getName(), notificationId);
        return ApiResponse.ok(true);
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllRead(Authentication auth) {
        return ApiResponse.ok(Map.of("updated", notificationService.markAllRead(auth.getName())));
    }
}
