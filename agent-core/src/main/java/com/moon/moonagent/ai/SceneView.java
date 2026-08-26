package com.moon.moonagent.ai;

import java.util.List;

/**
 * 场景生成相关字段的轻量视图 —— agent-core 不依赖分镜 {@code Scene} 实体。
 * {@link SceneGenerationBridge} 的实现（MoonAgent 侧）负责 SceneView ↔ Scene 双向映射。
 * 字段为 public（仅作数据传输载体，避免 Lombok 在共享库的额外开销）。
 */
public class SceneView {

    /** 场景 id（更新时用于回写） */
    public String id;

    // ── 图片生成 ──
    /** 图片模型（分镜覆盖） */
    public String imageModel;
    /** 图片尺寸（分镜覆盖） */
    public String imageSize;
    /** 图片质量（分镜覆盖） */
    public String imageQuality;
    /** 图片数量（分镜覆盖） */
    public Integer imageN;
    /** 生成结果首图 URL（相对 /api/files/... 路径） */
    public String imageUrl;
    /** 生成结果多图 URL（逗号分隔） */
    public String imageUrls;
    /** 图片生成状态（generating/completed/failed） */
    public String imageStatus;

    // ── 视频生成 ──
    /** 视频模型（分镜覆盖） */
    public String videoModel;
    /** 视频分辨率（分镜覆盖） */
    public String videoResolution;
    /** 视频宽高比（分镜覆盖） */
    public String videoAspectRatio;
    /** 视频时长（秒，分镜覆盖） */
    public Integer duration;
    /** 视频异步任务 ID */
    public String videoTaskId;
    /** 视频状态（generating/processing/completed/failed） */
    public String videoStatus;
    /** 视频结果 URL */
    public String videoUrl;

    // ── 参考图（资产库多模态引用，若后续需要扩展） ──
    /** 仅供未来扩展使用 */
    public List<String> referenceImages;
}
