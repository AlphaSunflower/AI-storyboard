package com.moon.moonagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.mapper.MorrowCanvasMapper;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.moonagent.service.ProjectService;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MorrowCanvasServiceImpl implements MorrowCanvasService {

    private final MorrowCanvasMapper canvasMapper;
    private final ProjectService projectService;

    @Override
    public MorrowCanvas create(String userId, String projectId, String title) {
        projectService.get(userId, projectId); // 归属校验
        MorrowCanvas c = new MorrowCanvas();
        c.setProjectId(projectId);
        int count = (int) listByProject(userId, projectId).stream().filter(x -> x.getTitle() != null && x.getTitle().startsWith("画布 ")).count();
        c.setTitle(title == null || title.isBlank() ? "画布 " + (count + 1) : title);
        canvasMapper.insert(c);
        return c;
    }

    @Override
    public List<MorrowCanvas> listByProject(String userId, String projectId) {
        projectService.get(userId, projectId);
        return canvasMapper.selectList(new LambdaQueryWrapper<MorrowCanvas>()
                .eq(MorrowCanvas::getProjectId, projectId)
                .orderByDesc(MorrowCanvas::getUpdatedAt));
    }

    @Override
    public MorrowCanvas get(String userId, String canvasId) {
        MorrowCanvas c = canvasMapper.selectById(canvasId);
        if (c == null) throw new BusinessException(40401, "画布不存在或无权访问");
        projectService.get(userId, c.getProjectId());
        return c;
    }

    @Override
    public MorrowCanvas rename(String userId, String canvasId, String title) {
        MorrowCanvas c = get(userId, canvasId);
        c.setTitle(title);
        canvasMapper.updateById(c);
        return c;
    }

    @Override
    public void delete(String userId, String canvasId) {
        MorrowCanvas c = get(userId, canvasId);
        canvasMapper.deleteById(c.getId());
    }
}
