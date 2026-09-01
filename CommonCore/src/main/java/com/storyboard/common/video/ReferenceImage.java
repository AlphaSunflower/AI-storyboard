package com.storyboard.common.video;

/**
 * 参考图参数（携带 role 信息）。
 * 替代原来的 List<String> referenceImages，支持每个参考图指定不同用途。
 *
 * @param url  图片 URL（本地路径 / 外部链接 / data URI）
 * @param role 用途
 */
public record ReferenceImage(
        String url,
        ReferenceImageRole role
) {

    /**
     * 便捷构造：默认为 REFERENCE 用途
     */
    public static ReferenceImage ofReference(String url) {
        return new ReferenceImage(url, ReferenceImageRole.REFERENCE);
    }

    /**
     * 便捷构造：首帧
     */
    public static ReferenceImage ofFirstFrame(String url) {
        return new ReferenceImage(url, ReferenceImageRole.FIRST_FRAME);
    }

    /**
     * 便捷构造：尾帧
     */
    public static ReferenceImage ofLastFrame(String url) {
        return new ReferenceImage(url, ReferenceImageRole.LAST_FRAME);
    }
}
