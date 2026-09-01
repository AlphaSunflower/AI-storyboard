package com.llmgateway.vendor.video.dto;

import java.util.List;

/**
 * 视频创建统一入参（OpenAI 风格请求体解析后的强类型载体）。
 * <p>
 * 所有厂商适配器共用这一个入参，各适配器据此构建自家协议的请求载荷。
 * 厂商差异（JSON content 数组 vs multipart / 参数名 / 取值范围）全部下沉到适配器，不在此对象内。
 */
public record VendorCreateInput(
        String model,
        String prompt,
        String size,
        String resolution,
        String aspectRatio,
        Integer duration,
        String negativePrompt,
        Long seed,
        String imageUrl,
        List<String> referenceImages,
        List<String> referenceVideos,
        List<String> referenceAudios
) {
    public boolean hasReference() {
        return (referenceImages != null && !referenceImages.isEmpty())
                || (referenceVideos != null && !referenceVideos.isEmpty())
                || (referenceAudios != null && !referenceAudios.isEmpty())
                || (imageUrl != null && !imageUrl.isBlank());
    }

    /**
     * 创建一个新的 VendorCreateInput，替换 referenceImages 字段（用于向后兼容的默认适配器实现）。
     */
    public VendorCreateInput withReferenceImages(List<String> newReferenceImages) {
        return new VendorCreateInput(
                this.model,
                this.prompt,
                this.size,
                this.resolution,
                this.aspectRatio,
                this.duration,
                this.negativePrompt,
                this.seed,
                this.imageUrl,
                newReferenceImages,
                this.referenceVideos,
                this.referenceAudios
        );
    }
}
