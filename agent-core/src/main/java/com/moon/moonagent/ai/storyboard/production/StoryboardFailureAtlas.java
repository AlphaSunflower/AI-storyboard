package com.moon.moonagent.ai.storyboard.production;

/**
 * 失败模式 → 修法（failure atlas）。
 *
 * <p>没有这张表时，"重试失败项"对每个错误一视同仁：余额不足会一直重试、参数不支持会一直重试、
 * 漏镜其实只要单独补跑一次。这张表把错误文案映射成一句可执行建议，随失败原因一起落库，
 * 用户和智能体都能看懂下一步该做什么。
 *
 * <p>只做**建议**，不改变重试次数与策略：retry 仍由用户/工具触发，避免自动重试放大费用。
 */
public final class StoryboardFailureAtlas {

    private StoryboardFailureAtlas() {
    }

    /**
     * 按错误文案给出修法建议；识别不了返回空串（调用方不要拼一段空话）。
     */
    public static String advise(String error) {
        String text = error == null ? "" : error.toLowerCase(java.util.Locale.ROOT);
        if (text.isBlank()) return "";

        if (contains(text, "insufficient_balance", "402", "余额", "quota", "欠费")) {
            return "账户额度不足：重试没有意义，先充值或换渠道";
        }
        if (contains(text, "400", "invalid params", "does not support", "不支持", "非法参数")) {
            return "上游拒绝了参数：先把分辨率/画幅/时长收敛到模型支持范围，再重试";
        }
        if (contains(text, "401", "403", "unauthorized", "forbidden", "鉴权", "无权")) {
            return "鉴权失败：检查网关 Key 与资产归属，重试前先修配置";
        }
        if (contains(text, "timeout", "timed out", "超时", "read timed out")) {
            return "调用超时：单镜重试通常可恢复；同一批反复超时就把批大小调小";
        }
        if (contains(text, "json", "解析", "unexpected token", "parse")) {
            return "模型输出不是合法 JSON：重试一次；仍失败则把批大小调小（6 → 3）";
        }
        if (contains(text, "未返回该镜头", "漏镜", "missing shot")) {
            return "批量漏镜：单独补跑该镜即可（本版已自动补跑一次）";
        }
        if (contains(text, "参考素材", "reference", "下载失败", "fetch")) {
            return "参考素材不可读：确认素材归属与 URL 可访问，再重试";
        }
        if (contains(text, "context", "too long", "上下文", "token")) {
            return "上下文超限：先压缩会话或减少一次投喂的镜数，再重试";
        }
        return "";
    }

    private static boolean contains(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }
}
