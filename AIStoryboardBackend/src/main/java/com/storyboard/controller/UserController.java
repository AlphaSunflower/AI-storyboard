package com.storyboard.controller;

import com.storyboard.common.BusinessException;
import com.storyboard.dto.request.ChangePasswordRequest;
import com.storyboard.dto.request.SubmitFeedbackRequest;
import com.storyboard.dto.request.UpdateFeedbackStatusRequest;
import com.storyboard.dto.request.UpdateProfileRequest;
import com.storyboard.dto.request.UpdateUserSettingsRequest;
import com.storyboard.common.ApiResponse;
import com.storyboard.dto.response.FeedbackAdminResponse;
import com.storyboard.dto.response.FeedbackResponse;
import com.storyboard.dto.response.ProfileResponse;
import com.storyboard.dto.response.UserSettingsResponse;
import com.storyboard.dto.response.UserStatsResponse;
import com.storyboard.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户个人信息、统计与反馈接口（JWT 鉴权）。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/profile")
    public ApiResponse<ProfileResponse> profile(Authentication auth) {
        return ApiResponse.ok(userService.getProfile(auth.getName()));
    }

    @PutMapping("/profile")
    public ApiResponse<ProfileResponse> updateProfile(Authentication auth, @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(userService.updateProfile(auth.getName(), request));
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(Authentication auth, @RequestBody ChangePasswordRequest request) {
        userService.changePassword(auth.getName(), request);
        return ApiResponse.ok("密码修改成功", null);
    }

    @GetMapping("/stats")
    public ApiResponse<UserStatsResponse> stats(Authentication auth) {
        return ApiResponse.ok(userService.getStats(auth.getName()));
    }

    @GetMapping("/settings")
    public ApiResponse<UserSettingsResponse> settings(Authentication auth) {
        return ApiResponse.ok(userService.getSettings(auth.getName()));
    }

    @PutMapping("/settings")
    public ApiResponse<UserSettingsResponse> updateSettings(
            Authentication auth, @RequestBody UpdateUserSettingsRequest request) {
        return ApiResponse.ok(userService.updateSettings(auth.getName(), request));
    }

    @PostMapping("/feedback")
    public ApiResponse<FeedbackResponse> feedback(
            Authentication auth, @RequestBody SubmitFeedbackRequest request) {
        return ApiResponse.ok(userService.submitFeedback(auth.getName(), request));
    }

    /** 管理员反馈收件箱；普通成员只提交，不读取他人反馈。 */
    @GetMapping("/feedback")
    public ApiResponse<List<FeedbackAdminResponse>> feedbackList(
            Authentication auth,
            @RequestParam(defaultValue = "100") int limit) {
        requireAdmin(auth);
        return ApiResponse.ok(userService.listFeedback(limit));
    }

    /** 管理员标记反馈已处理 / 重新打开。 */
    @PatchMapping("/feedback/{feedbackId}")
    public ApiResponse<Void> updateFeedbackStatus(
            Authentication auth,
            @PathVariable String feedbackId,
            @RequestBody UpdateFeedbackStatusRequest request) {
        requireAdmin(auth);
        userService.updateFeedbackStatus(feedbackId, request.status());
        return ApiResponse.ok(null);
    }

    private void requireAdmin(Authentication auth) {
        boolean admin = auth != null && auth.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!admin) throw new BusinessException(40301, "仅管理员可查看或处理反馈");
    }
}
