package com.moon.moonagent.ai.agent;

/**
 * 轻量 token 估算器：拿不到宿主 tokenizer 时，用字符构成做近似。
 *
 * <p>规则：CJK（中日韩）字符按 1 token，其余字符按约 4 字符/token。
 * 该比例在中英混排的提示词上接近主流 BPE 词表的实际占用，
 * 用于「上下文组成」三色明细的估算展示，不参与计费口径。
 */
public final class ContextTokenEstimator {

    private ContextTokenEstimator() {
    }

    /** 文本 → 估算 token 数；null / 空串返回 0。 */
    public static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0L;
        long cjk = 0L;
        long other = 0L;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) cjk++;
            else other++;
        }
        return cjk + Math.round(other / 4.0);
    }

    /** CJK 字符判定：基本汉字、扩展 A、兼容汉字、假名、谚文。 */
    static boolean isCjk(char ch) {
        return (ch >= 0x3040 && ch <= 0x30FF)
                || (ch >= 0x3400 && ch <= 0x4DBF)
                || (ch >= 0x4E00 && ch <= 0x9FFF)
                || (ch >= 0xF900 && ch <= 0xFAFF)
                || (ch >= 0xAC00 && ch <= 0xD7AF);
    }
}
