package com.storyboard.service;

import com.storyboard.dto.request.ChangePasswordRequest;
import com.storyboard.dto.request.UpdateProfileRequest;
import com.storyboard.dto.response.ProfileResponse;
import com.storyboard.dto.response.UserStatsResponse;

/**
 * 用户服务接口：个人信息查询/更新、修改密码、统计。
 */
public interface UserService {

    /** 查询当前用户个人信息（名称 + 邮箱）。 */
    ProfileResponse getProfile(String userId);

    /** 更新名称 / 邮箱（任一非空），校验邮箱唯一性。 */
    ProfileResponse updateProfile(String userId, UpdateProfileRequest request);

    /** 修改密码：校验旧密码后更新为新密码。 */
    void changePassword(String userId, ChangePasswordRequest request);

    /** 统计：生成图片数 / 生成视频数 / 项目总数。 */
    UserStatsResponse getStats(String userId);
}
