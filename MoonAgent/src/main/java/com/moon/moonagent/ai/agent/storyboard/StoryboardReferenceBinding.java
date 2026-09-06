package com.moon.moonagent.ai.agent.storyboard;

/**
 * 单个素材与其提示词/用途的绑定（整理智能体输出）。
 *
 * @param assetId         素材标识（资产 id 或「图N」用户编号），供方案/生成阶段指代
 * @param url             素材图片地址（可为空，纯文本资产无图）
 * @param kind            asset（带文字约束，无需整理）| reference（无文字说明，需视觉映射）
 * @param purposeText     用途说明（未说明时 = 「用户未明确说明」）
 * @param supportedPrompt 该素材支撑的提示词/剧情描述（asset 可为空）
 * @param groupLabel      成组绑定标签（如「场景A」；可空）
 * @param role            参考角色（可空；由方案设计师/兜底补齐）
 */
public record StoryboardReferenceBinding(
        String assetId,
        String url,
        String kind,
        String purposeText,
        String supportedPrompt,
        String groupLabel,
        String role
) {
    public StoryboardReferenceBinding withRole(String r) {
        return new StoryboardReferenceBinding(assetId, url, kind, purposeText, supportedPrompt, groupLabel, r);
    }
}
