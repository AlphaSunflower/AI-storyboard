package com.moon.moonagent.ai.agent;

import java.util.List;

/**
 * 分镜 ↔ 资产的自动关联判定结果（LLM 输出解析）：sceneNumber=分镜序号，assetIds=该镜出现的资产 ID。
 */
public record SceneAssetMatch(int sceneNumber, List<String> assetIds) {}
