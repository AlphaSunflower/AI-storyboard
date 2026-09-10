package com.storyboard.common.generation;

import java.util.List;

/** 生成记录的类型与状态常量，统一 Moon Agent 与 AI 分镜服务的落库值。 */
public final class GenerationRecordTypes {

    public static final String IMAGE = "image";
    public static final String VIDEO = "video";
    public static final String STORYBOARD_IMAGE = "storyboard_image";
    public static final String STORYBOARD_VIDEO = "storyboard_video";

    public static final String QUEUED = "queued";
    public static final String GENERATING = "generating";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";

    private GenerationRecordTypes() {}

    public static String classifyImage(String mode, List<String> referenceImages,
                                       String generatedImageUrl, Integer n) {
        boolean hasRef = (referenceImages != null && !referenceImages.isEmpty())
                || (generatedImageUrl != null && !generatedImageUrl.isBlank());
        if (!hasRef && !"edit".equals(mode)) return "text_to_image";
        boolean multiInput = referenceImages != null && referenceImages.size() > 1;
        boolean multiOutput = n != null && n > 1;
        if (multiInput && multiOutput) return "multi_image_to_multi_image";
        return multiInput ? "multi_image_to_image" : "image_to_image";
    }

    public static String classifyVideo(List<String> referenceImages, String generatedImageUrl) {
        boolean hasRef = (referenceImages != null && !referenceImages.isEmpty())
                || (generatedImageUrl != null && !generatedImageUrl.isBlank());
        return hasRef ? "ref_to_video" : "text_to_video";
    }
}
