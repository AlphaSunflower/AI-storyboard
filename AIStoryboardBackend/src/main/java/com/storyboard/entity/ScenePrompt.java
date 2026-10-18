package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 分镜编排/prompt 明细（1:1，PK = scene_id）。由 scenes 宽表纵向拆分而来：
 * 生成轮询/状态回写只写 scenes 基础列，本表承载低频 prompt/参数宽列。
 */
@Data
@TableName(value = "scene_prompts", schema = "public")
public class ScenePrompt {

    /** 分镜 id（= scenes.id，1:1） */
    @TableId(type = IdType.INPUT)
    private String sceneId;

    private String scriptContent;
    private String imagePrompt;
    private String videoPrompt;
    private String negativePrompt;
    /**
     * 本镜与前镜的衔接说明（继承项 / 禁改项）。独立成列而不是混写进 imagePrompt：
     * 否则用户与智能体都没法单独审阅、单独修改。
     */
    private String continuityNote;
    private String cameraMovement;
    private String shotType;
    private String soundDesign;
    private String aiModel;
    private String videoResolution;
    private Integer duration;

    // 分镜生成参数覆盖（null = 跟随全局默认）+ 多图结果
    private String imageModel;
    private String imageSize;
    private String imageQuality;
    private Integer imageN;
    private String videoModel;
    private String videoAspectRatio;
}
