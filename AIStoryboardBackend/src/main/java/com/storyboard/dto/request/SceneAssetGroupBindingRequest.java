package com.storyboard.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 分镜关联资产组；purpose 决定快照仅保留图片还是同时保留图/视频/音频。 */
public record SceneAssetGroupBindingRequest(
        @NotBlank String groupId,
        @NotBlank @Pattern(regexp = "image|video") String purpose
) {
}
