package com.storyboard.controller;

import com.storyboard.dto.request.ChangePasswordRequest;
import com.storyboard.dto.request.UpdateProfileRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.ProfileResponse;
import com.storyboard.dto.response.UserStatsResponse;
import com.storyboard.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 用户个人信息与统计接口（JWT 鉴权）。
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
}
