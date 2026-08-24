package com.storyboard.dto.request;

import java.util.List;

public record GenerateScriptRequest(
    String projectId,
    String scriptText,
    String creationType,
    String customTypeDesc,
    String aspectRatio,
    String model,
    String understandingModel,
    List<String> referenceImages,
    // 可选：用户显式勾选的资产库资产 ID 子集（null/空 = 不注入任何资产，默认与资产库无关）
    List<String> assetIds,
    // 可选：写库模式。overwrite=清空项目现有分镜后重写；append/null=追加（默认）
    String mode
) {}
