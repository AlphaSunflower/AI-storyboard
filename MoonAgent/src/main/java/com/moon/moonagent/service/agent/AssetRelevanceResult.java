package com.moon.moonagent.service.agent;

/**
 * 提示词 × 资产 关联性判定结果：relevant=是否强关联（false → 需用户澄清提示词）；reason=判定理由（给用户看）。
 */
public record AssetRelevanceResult(boolean relevant, String reason) {}
