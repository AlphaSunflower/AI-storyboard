package com.storyboard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storyboard.dto.request.ChangePasswordRequest;
import com.storyboard.dto.request.SubmitFeedbackRequest;
import com.storyboard.dto.request.UpdateProfileRequest;
import com.storyboard.dto.request.UpdateUserSettingsRequest;
import com.storyboard.dto.response.FeedbackAdminResponse;
import com.storyboard.dto.response.FeedbackResponse;
import com.storyboard.dto.response.ProfileResponse;
import com.storyboard.dto.response.UserSettingsResponse;
import com.storyboard.dto.response.UserStatsResponse;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.User;
import com.storyboard.entity.UserFeedback;
import com.storyboard.entity.UserSettings;
import com.storyboard.common.BusinessException;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.UserFeedbackMapper;
import com.storyboard.mapper.UserMapper;
import com.storyboard.mapper.UserSettingsMapper;
import com.storyboard.security.AuthSessionService;
import com.storyboard.security.FileUrlSigner;
import com.storyboard.security.ScryptPasswordService;
import com.storyboard.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 用户服务实现。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_APPROVAL_MODE = "PLAN_APPROVAL";
    private static final String DEFAULT_BUSY_SEND_BEHAVIOR = "steer";
    private static final String DEFAULT_WORK_STEP_DISPLAY = "detailed";
    private static final Set<String> APPROVAL_MODES =
            Set.of("PLAN_APPROVAL", "REQUEST_APPROVAL", "FULL_ACCESS");
    private static final Set<String> BUSY_SEND_BEHAVIORS = Set.of("queue", "steer");
    private static final Set<String> WORK_STEP_DISPLAYS = Set.of("thinking", "standard", "detailed");
    private static final int MAX_FEEDBACK_IMAGES = 6;

    private final UserMapper userMapper;
    private final UserSettingsMapper userSettingsMapper;
    private final UserFeedbackMapper userFeedbackMapper;
    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final ScryptPasswordService passwordService;
    private final AuthSessionService authSessionService;
    private final FileUrlSigner fileUrlSigner;

    @Override
    public ProfileResponse getProfile(String userId) {
        User user = getOwnedUser(userId);
        return toProfile(user);
    }

    @Override
    public ProfileResponse updateProfile(String userId, UpdateProfileRequest request) {
        User user = getOwnedUser(userId);
        if (request.displayName() != null) {
            if (request.displayName().isBlank()) throw new BusinessException(40001, "名称不能为空");
            user.setDisplayName(request.displayName().trim());
        }
        if (request.email() != null) {
            String email = request.email().trim().toLowerCase();
            if (email.isBlank() || !email.contains("@")) throw new BusinessException(40001, "邮箱格式不正确");
            User existing = userMapper.findByEmail(email);
            if (existing != null && !existing.getId().equals(userId)) {
                throw new BusinessException(40001, "邮箱已被占用");
            }
            user.setEmail(email);
        }
        userMapper.updateById(user);
        return toProfile(user);
    }

    @Override
    public void changePassword(String userId, ChangePasswordRequest request) {
        User user = getOwnedUser(userId);
        if (request.oldPassword() == null || request.newPassword() == null) {
            throw new BusinessException(40001, "旧密码和新密码不能为空");
        }
        if (!passwordService.verifyPassword(request.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException(40001, "旧密码错误");
        }
        if (request.newPassword().length() < 6) {
            throw new BusinessException(40001, "新密码至少 6 位");
        }
        try {
            user.setPasswordHash(passwordService.hashPassword(request.newPassword()));
        } catch (Exception e) {
            throw new BusinessException(50000, "密码加密失败");
        }
        userMapper.updateById(user);
        authSessionService.revokeAll(userId);
    }

    @Override
    public UserStatsResponse getStats(String userId) {
        long projectCount = projectMapper.selectCount(
                new LambdaQueryWrapper<Project>().eq(Project::getUserId, userId));
        List<Scene> scenes = sceneMapper.findByUserId(userId);
        long videoCount = scenes.stream()
                .filter(s -> s.getVideoUrl() != null && !s.getVideoUrl().isBlank())
                .count();
        long imageCount = scenes.stream().mapToLong(this::countSceneImages).sum();
        return new UserStatsResponse(imageCount, videoCount, projectCount);
    }

    @Override
    public UserSettingsResponse getSettings(String userId) {
        UserSettings row = userSettingsMapper.selectById(userId);
        return toSettingsResponse(row);
    }

    @Override
    public UserSettingsResponse updateSettings(String userId, UpdateUserSettingsRequest request) {
        UserSettings row = userSettingsMapper.selectById(userId);
        OffsetDateTime now = OffsetDateTime.now();
        boolean insert = row == null;
        if (insert) {
            row = new UserSettings();
            row.setUserId(userId);
            row.setDefaultApprovalMode(DEFAULT_APPROVAL_MODE);
            row.setBusySendBehavior(DEFAULT_BUSY_SEND_BEHAVIOR);
            row.setWorkStepDisplay(DEFAULT_WORK_STEP_DISPLAY);
            row.setCreatedAt(now);
        }
        if (request.defaultApprovalMode() != null) {
            String mode = request.defaultApprovalMode().trim().toUpperCase();
            if (!APPROVAL_MODES.contains(mode)) {
                throw new BusinessException(40001, "不支持审批模式：" + request.defaultApprovalMode());
            }
            row.setDefaultApprovalMode(mode);
        }
        if (request.busySendBehavior() != null) {
            String behavior = request.busySendBehavior().trim().toLowerCase();
            if (!BUSY_SEND_BEHAVIORS.contains(behavior)) {
                throw new BusinessException(40001, "不支持繁忙发送行为：" + request.busySendBehavior());
            }
            row.setBusySendBehavior(behavior);
        }
        if (request.workStepDisplay() != null) {
            String display = request.workStepDisplay().trim().toLowerCase();
            if (!WORK_STEP_DISPLAYS.contains(display)) {
                throw new BusinessException(40001, "不支持工作步骤展示级别：" + request.workStepDisplay());
            }
            row.setWorkStepDisplay(display);
        }
        // 历史行/手工插入可能出现空值：保存时一并收敛到默认值。
        if (row.getDefaultApprovalMode() == null) row.setDefaultApprovalMode(DEFAULT_APPROVAL_MODE);
        if (row.getBusySendBehavior() == null) row.setBusySendBehavior(DEFAULT_BUSY_SEND_BEHAVIOR);
        if (row.getWorkStepDisplay() == null) row.setWorkStepDisplay(DEFAULT_WORK_STEP_DISPLAY);
        row.setUpdatedAt(now);
        if (insert) {
            userSettingsMapper.insert(row);
        } else {
            userSettingsMapper.updateById(row);
        }
        return toSettingsResponse(row);
    }

    @Override
    public FeedbackResponse submitFeedback(String userId, SubmitFeedbackRequest request) {
        getOwnedUser(userId);
        String content = request.content() == null ? "" : request.content().trim();
        if (content.length() < 5) throw new BusinessException(40001, "反馈内容至少 5 个字");
        if (content.length() > 2000) throw new BusinessException(40001, "反馈内容不能超过 2000 个字");
        List<String> images = normalizeFeedbackImages(request.imageUrls());
        UserFeedback feedback = new UserFeedback();
        feedback.setId(UUID.randomUUID().toString());
        feedback.setUserId(userId);
        feedback.setContent(content);
        feedback.setImageUrls(images.isEmpty() ? null : String.join(",", images));
        feedback.setStatus("open");
        feedback.setCreatedAt(OffsetDateTime.now());
        userFeedbackMapper.insert(feedback);
        return new FeedbackResponse(feedback.getId(), feedback.getStatus());
    }

    @Override
    public List<FeedbackAdminResponse> listFeedback(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return userFeedbackMapper.listWithUser(safeLimit).stream()
                .map(row -> new FeedbackAdminResponse(
                        row.getId(),
                        row.getUserId(),
                        row.getUserDisplayName(),
                        row.getUserEmail(),
                        row.getContent(),
                        splitFeedbackImages(row.getImageUrls()).stream()
                                .map(fileUrlSigner::signIfLocal)
                                .toList(),
                        row.getStatus(),
                        row.getCreatedAt()))
                .toList();
    }

    @Override
    public void updateFeedbackStatus(String feedbackId, String status) {
        String next = status == null ? "" : status.trim().toLowerCase();
        if (!Set.of("open", "resolved").contains(next)) {
            throw new BusinessException(40001, "不支持的反馈状态：" + status);
        }
        UserFeedback feedback = userFeedbackMapper.selectById(feedbackId);
        if (feedback == null) throw new BusinessException(40401, "反馈不存在");
        feedback.setStatus(next);
        userFeedbackMapper.updateById(feedback);
    }

    // ─────────── 私有辅助 ───────────

    private UserSettingsResponse toSettingsResponse(UserSettings row) {
        if (row == null) {
            return new UserSettingsResponse(
                    DEFAULT_APPROVAL_MODE, DEFAULT_BUSY_SEND_BEHAVIOR, DEFAULT_WORK_STEP_DISPLAY);
        }
        String mode = row.getDefaultApprovalMode() != null
                && APPROVAL_MODES.contains(row.getDefaultApprovalMode())
                ? row.getDefaultApprovalMode() : DEFAULT_APPROVAL_MODE;
        String behavior = row.getBusySendBehavior() != null
                && BUSY_SEND_BEHAVIORS.contains(row.getBusySendBehavior())
                ? row.getBusySendBehavior() : DEFAULT_BUSY_SEND_BEHAVIOR;
        String display = row.getWorkStepDisplay() != null
                && WORK_STEP_DISPLAYS.contains(row.getWorkStepDisplay())
                ? row.getWorkStepDisplay() : DEFAULT_WORK_STEP_DISPLAY;
        return new UserSettingsResponse(mode, behavior, display);
    }

    /** 只接受站内相对路径；去掉签名 query，避免 30 天后截图链接失效。 */
    private List<String> normalizeFeedbackImages(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        if (raw.size() > MAX_FEEDBACK_IMAGES) {
            throw new BusinessException(40001, "最多上传 " + MAX_FEEDBACK_IMAGES + " 张截图");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) continue;
            String path = item.trim();
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            if (path.isEmpty()) continue;
            if (!path.startsWith("/api/files/") || path.contains("..")) {
                throw new BusinessException(40001, "截图地址不合法");
            }
            if (path.length() > 512) throw new BusinessException(40001, "截图地址过长");
            normalized.add(path);
        }
        if (normalized.size() > MAX_FEEDBACK_IMAGES) {
            throw new BusinessException(40001, "最多上传 " + MAX_FEEDBACK_IMAGES + " 张截图");
        }
        return new ArrayList<>(normalized);
    }

    private List<String> splitFeedbackImages(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private User getOwnedUser(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(40401, "用户不存在");
        return user;
    }

    private ProfileResponse toProfile(User user) {
        return new ProfileResponse(
                user.getId(), user.getDisplayName(), user.getEmail(), user.getRole());
    }

    /** 单分镜图片数：imageUrls 逗号分隔计数，否则单图 imageUrl 计 1。 */
    private long countSceneImages(Scene s) {
        String urls = s.getImageUrls();
        if (urls != null && !urls.isBlank()) {
            return Arrays.stream(urls.split(",")).map(String::trim).filter(u -> !u.isEmpty()).count();
        }
        return (s.getImageUrl() != null && !s.getImageUrl().isBlank()) ? 1 : 0;
    }
}
