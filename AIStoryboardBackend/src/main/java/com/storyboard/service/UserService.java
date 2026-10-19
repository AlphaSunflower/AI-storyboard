package com.storyboard.service;

import com.storyboard.dto.request.ChangePasswordRequest;
import com.storyboard.dto.request.SubmitFeedbackRequest;
import com.storyboard.dto.request.UpdateProfileRequest;
import com.storyboard.dto.request.UpdateUserSettingsRequest;
import com.storyboard.dto.response.FeedbackAdminResponse;
import com.storyboard.dto.response.FeedbackResponse;
import com.storyboard.dto.response.ProfileResponse;
import com.storyboard.dto.response.UserSettingsResponse;
import com.storyboard.dto.response.UserStatsResponse;

import java.util.List;

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

    /** 账号级偏好设置；没有记录时返回默认值。 */
    UserSettingsResponse getSettings(String userId);

    /** 局部更新偏好设置，字段为 null 表示保持不变。 */
    UserSettingsResponse updateSettings(String userId, UpdateUserSettingsRequest request);

    /** 提交帮助与反馈（截图只接受站内 /api/files/ 相对路径）。 */
    FeedbackResponse submitFeedback(String userId, SubmitFeedbackRequest request);

    /** 管理员反馈收件箱：按提交时间倒序。 */
    List<FeedbackAdminResponse> listFeedback(int limit);

    /** 管理员标记反馈状态：open / resolved。 */
    void updateFeedbackStatus(String feedbackId, String status);
}
