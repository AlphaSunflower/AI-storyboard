package com.moon.moonagent.service;

import java.util.List;

/**
 * 分镜写库后的自动配图入口。
 */
public interface StoryboardAutoImageService {

    /**
     * 给本次生成、且没有任何参考物和已有图片的分镜入队自动生图。
     *
     * @return 实际入队的分镜数
     */
    int enqueueMissingMaterialImages(String conversationId, String projectId, List<Integer> sceneNumbers);
}
