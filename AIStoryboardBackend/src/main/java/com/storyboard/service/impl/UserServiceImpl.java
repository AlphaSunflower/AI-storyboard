package com.storyboard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storyboard.dto.request.ChangePasswordRequest;
import com.storyboard.dto.request.UpdateProfileRequest;
import com.storyboard.dto.response.ProfileResponse;
import com.storyboard.dto.response.UserStatsResponse;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.User;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.UserMapper;
import com.storyboard.security.ScryptPasswordService;
import com.storyboard.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 用户服务实现。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final ScryptPasswordService passwordService;

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

    // ─────────── 私有辅助 ───────────

    private User getOwnedUser(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(40401, "用户不存在");
        return user;
    }

    private ProfileResponse toProfile(User user) {
        return new ProfileResponse(user.getId(), user.getDisplayName(), user.getEmail());
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
