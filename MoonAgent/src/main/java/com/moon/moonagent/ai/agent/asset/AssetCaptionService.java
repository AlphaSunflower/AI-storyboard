package com.moon.moonagent.ai.agent.asset;

/**
 * 上传素材的语义描述服务。
 *
 * <p>背景：上传的参考素材在智能体眼里此前只有一个标签「会话图片」——
 * {@code SessionAssetService.toRef} 只读 purpose/prompt，兜底成固定文案，从不读原始文件名，
 * 也没有任何一处在看图片内容。于是模型无法判断「哪张是角色定妆、哪张是场景参考」，
 * 「按剧本和提示词把对应资产绑到分镜」根本无从谈起。
 *
 * <p>本服务在上传后异步用视觉模型判定素材用途（角色/场景/道具/风格）并写入
 * {@code agent_assets.description}，作为模型可见的唯一语义标签来源。
 *
 * <p>全部方法都是「尽力而为」语义：失败只记日志，绝不向上抛，不影响上传与生成主链路。
 */
public interface AssetCaptionService {

    /**
     * 上传后立即触发描述生成（异步、幂等）。
     *
     * @param assetId agent_assets 主键；为空或素材类型不支持时静默跳过
     */
    void captionAsync(String assetId);

    /**
     * 读取素材时的兜底触发：仅在 description 为空且当前没有在跑时才真正开始。
     * 用于自愈历史素材（本能力上线前上传的素材没有描述）。
     *
     * @param assetId agent_assets 主键
     */
    void ensureCaptionAsync(String assetId);
}
