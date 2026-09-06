package com.moon.moonagent.ai.agent.storyboard;

/**
 * 分镜素材参考角色（模型无关，供方案设计师分配 / 生成阶段映射为视频模型 role）。
 *
 * <p>与 {@link com.moon.moonagent.ai.video.ReferenceImageRole} 的区别：
 * 这里多出「首尾帧」与「全能参考」两个复合语义（H3：单个 figure 只能选其一，混合素材走全能参考）。
 */
public enum StoryboardReferenceRole {
    /** 首帧：锁定视频起始画面 */
    FIRST_FRAME,
    /** 尾帧：锁定视频结束画面 */
    LAST_FRAME,
    /** 首尾帧：图1 为首帧、图末为尾帧，中间只补动作 */
    FIRST_LAST_FRAME,
    /** 全能参考：多张图混合用途，具体首尾帧关系写进 prompt 的 @图片N 锚定 */
    FULL_REFERENCE;

    /** 宽松归一：非法/缺失返回 null */
    public static StoryboardReferenceRole parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String r = raw.trim().toUpperCase();
        return switch (r) {
            case "FIRST_FRAME", "首帧" -> FIRST_FRAME;
            case "LAST_FRAME", "尾帧" -> LAST_FRAME;
            case "FIRST_LAST_FRAME", "FIRST_LAST", "首尾帧" -> FIRST_LAST_FRAME;
            case "FULL_REFERENCE", "REFERENCE", "全能参考", "参考" -> FULL_REFERENCE;
            default -> null;
        };
    }

    /** 按图片数量给出默认角色（无 LLM 判定时的兜底）。 */
    public static StoryboardReferenceRole defaultRole(int imageCount) {
        return switch (imageCount) {
            case 1 -> FIRST_FRAME;
            case 2 -> FIRST_LAST_FRAME;
            default -> FULL_REFERENCE;
        };
    }
}
