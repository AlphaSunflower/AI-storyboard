package com.moon.moonagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.Project;
import com.moon.moonagent.mapper.ProjectMapper;
import com.moon.moonagent.service.ProjectService;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectMapper projectMapper;

    @Override
    public synchronized Project createOrGet(String userId, String key) {
        // ponytail: 全局进程内锁串行 check-then-insert —— 单实例画布下防并发重复创建同一 (user, name) 项目；
        // 若未来多实例/集群，改用 (user_id,name) 唯一索引 + DuplicateKeyException 兜底或按 key 分段锁。
        Project existing = projectMapper.selectOne(new LambdaQueryWrapper<Project>()
                .eq(Project::getUserId, userId)
                .eq(Project::getName, key)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        Project p = new Project();
        p.setUserId(userId);
        p.setName(key);
        p.setStatus("draft");
        p.setCreationType("morrow");
        p.setAspectRatio("16:9");
        p.setAiModel("minimax-video-model");
        projectMapper.insert(p);
        return p;
    }

    @Override
    public Project create(String userId, String title) {
        Project p = new Project();
        p.setUserId(userId);
        p.setName(title == null || title.isBlank() ? "未命名项目" : title);
        p.setStatus("draft");
        p.setCreationType("morrow");
        p.setAspectRatio("16:9");
        p.setAiModel("minimax-video-model");
        projectMapper.insert(p);
        return p;
    }

    @Override
    public List<Project> listMine(String userId) {
        // 项目跨系统共享（ai分镜/Morrow 同一 projects 表，按 id 锚点）：列出该用户全部项目，不区分 movie/morrow 类型
        return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getUserId, userId)
                .orderByDesc(Project::getUpdatedAt));
    }

    @Override
    public Project get(String userId, String id) {
        Project p = projectMapper.selectById(id);
        if (p == null || !userId.equals(p.getUserId())) {
            throw new BusinessException(40401, "项目不存在或无权访问");
        }
        return p;
    }

    @Override
    public Project rename(String userId, String id, String title) {
        Project p = get(userId, id);
        p.setName(title);
        projectMapper.updateById(p);
        return p;
    }

    @Override
    public void delete(String userId, String id) {
        Project p = get(userId, id);
        projectMapper.deleteById(p.getId());
    }
}
