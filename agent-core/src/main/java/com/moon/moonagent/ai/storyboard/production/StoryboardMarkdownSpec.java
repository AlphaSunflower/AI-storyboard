package com.moon.moonagent.ai.storyboard.production;

/**
 * Shared Markdown output contract for storyboard text that is shown to users.
 * Kept in one place so every prompt in the plot/storyboard chain asks for the
 * same structure, and downstream renderers can rely on it.
 */
final class StoryboardMarkdownSpec {

    private StoryboardMarkdownSpec() {
    }

    static final String SPEC = """

            ## 输出格式规范（强制）
            面向用户展示的正文必须是标准 Markdown，禁止大段无格式纯文本：
            - 一级标题由外层提供，正文最高从 ## 开始，逐级使用 ##、###，禁止跳级；
              不要用「一、二、三」或「【A段】」这类纯文本充当标题。
            - 结构化信息（镜头/分镜时间轴、参数对照、清单、属性）优先用 Markdown 表格；
              能用表格就不要写大段文字。
            - 段落不超过 3 行，长文本必须拆分；模块之间空一行，表格前后保留空行。
            - 关键结论、数值、名词用 **加粗**；补充说明用 *斜体*；字段名、参数、标识符用 `行内代码`。
            - 并列要点用 - 无序列表；步骤用 1. 有序列表；计划/检查项优先用 - [ ] 任务列表。
            - 引用原文或提示用 > 引用块；不同模块之间用 --- 分割线分隔。
            - 仅最高优先级重点或必须留意的警告使用 ==高亮==。
            - 禁止大段无格式纯文本、禁止整段滥用加粗、禁止无意义的多余换行。
            """;
}
