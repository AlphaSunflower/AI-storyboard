package com.moon.moonagent.ai.storyboard.production;

/**
 * 分镜提示词组装：把「衔接说明」拼进最终生成提示词。
 *
 * <p>为什么独立一步而不是在写库时混进 imagePrompt/videoPrompt：
 * <ul>
 *   <li>{@code continuity_note} 要能被用户与智能体单独审阅、单独修改（"这句衔接不对"），</li>
 *   <li>混写之后每一镜的提示词会重复堆叠同样的承接句，越到后面越长。</li>
 * </ul>
 * 所以落库分两列，**只在送进模型前那一刻合并**。
 */
public final class StoryboardPromptAssembly {

    /** 合并标记：便于排查"这句是模型写的还是衔接说明加的"。 */
    public static final String CONTINUITY_MARKER = "\n\n[衔接] ";

    private StoryboardPromptAssembly() {
    }

    /**
     * 把衔接说明拼到提示词末尾；任一为空则原样返回（不做无意义的字符串操作）。
     */
    public static String withContinuity(String prompt, String continuityNote) {
        String base = prompt == null ? "" : prompt.trim();
        String note = continuityNote == null ? "" : continuityNote.trim();
        if (note.isEmpty()) return base;
        if (base.isEmpty()) return note;
        if (base.contains(CONTINUITY_MARKER.trim())) return base;
        return base + CONTINUITY_MARKER + note;
    }
}
