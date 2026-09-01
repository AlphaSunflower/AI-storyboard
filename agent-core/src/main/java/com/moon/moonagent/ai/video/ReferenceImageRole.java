package com.moon.moonagent.ai.video;

/**
 * 参考图用途枚举（模型无关的语义层）。
 * 所有视频模型适配器都应支持这些语义，具体实现由适配器负责转换为各模型特定格式。
 */
public enum ReferenceImageRole {

    /** 首帧：锁定视频起始画面 */
    FIRST_FRAME,

    /** 尾帧：锁定视频结束画面（部分模型可能不支持，会降级为 REFERENCE） */
    LAST_FRAME,

    /** 参考图：多模态参考，模型自由演绎 */
    REFERENCE
}
