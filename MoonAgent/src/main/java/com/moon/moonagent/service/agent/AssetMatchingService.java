package com.moon.moonagent.service.agent;

import com.moon.moonagent.dto.response.AssetVO;

import java.util.List;
import java.util.Map;

/**
 * 资产 × 提示词/分镜 的 LLM 判定服务（Agent 编排专用，复用网关对话模型）：
 * <ul>
 *   <li>{@link #judgeRelevance}：关联性门禁——用户提示词与勾选资产是否强关联；弱关联时调用方停下让用户澄清</li>
 *   <li>{@link #matchScenes}：分镜自动关联——按剧情判定每个分镜出现哪些资产（写 scene_assets 用）</li>
 * </ul>
 * 两个调用均为纯解析 + 失败降级（返回不相关/空关联），不阻塞主流程。
 */
public interface AssetMatchingService {

    /**
     * 关联性门禁判定：用户提示词 × 勾选资产 → 是否强关联（附理由）。
     *
     * @param prompt 用户原始提示词（分镜需求 / 视频诉求）
     * @param assets 勾选资产（非空；空列表调用方应跳过判定）
     * @return 判定结果；LLM 调用失败时降级为「相关」（不阻塞）
     */
    AssetRelevanceResult judgeRelevance(String prompt, List<AssetVO> assets);

    /**
     * 分镜自动关联判定：分镜列表 × 勾选资产 → 每镜出现的资产 ID 列表。
     *
     * @param scenes 分镜 Map 列表（含 sceneNumber/scriptContent 字段）
     * @param assets 勾选资产
     * @return 每镜关联（sceneNumber 对齐）；LLM 失败/解析失败返回空列表（调用方不关联，降级不阻塞）
     */
    List<SceneAssetMatch> matchScenes(List<Map<String, Object>> scenes, List<AssetVO> assets);
}
