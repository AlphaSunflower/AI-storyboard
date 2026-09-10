package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.ai.video.ReferenceImageRole;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.PlanField;
import com.moon.moonagent.ai.agent.AgentTools;
import com.moon.moonagent.ai.agent.AssetMatchingService;
import com.moon.moonagent.ai.agent.SceneAssetMatch;
import com.moon.moonagent.dto.request.StoryboardVideoEnqueueItem;
import com.moon.moonagent.dto.request.StoryboardVideoEnqueueRequest;
import com.moon.moonagent.service.StoryboardVideoQueueService;
import com.moon.moonagent.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 分镜审查意图处理器：用户通过前端「上传分镜」功能发送已有分镜列表，
 * 本处理器分析每个分镜的生成状态（有图?有视频?），给出 HITL 人工介入选项。
 */
@Component
@RequiredArgsConstructor
public class SceneReviewIntentHandler implements IntentHandler {

    private static final Logger log = LoggerFactory.getLogger(SceneReviewIntentHandler.class);

    private final AgentOrchestratorSupport support;
    private final StoryboardClient storyboardClient;
    private final AgentTools agentTools;
    private final AgentGenerationService generationService;
    private final com.moon.moonagent.config.PromptConfig promptConfig;
    private final AssetMatchingService assetMatchingService;
    private final StoryboardVideoQueueService storyboardVideoQueueService;
    private final SceneReviewCardService sceneReviewCardService;
    private final AssetService assetService;

    /** 从消息中提取分镜编号 */
    private static final Pattern SCENE_NUM = Pattern.compile("分镜(\\d+)");

    @Override
    public String intentType() {
        return "intent-scene-review";
    }

    @Override
    public Set<String> resumeActions() {
        return Set.of("review-optimize", "review-gen-image", "review-gen-video", "review-skip",
                "review-confirm", "review-disagree", "reference-role-select", "review-video-auto");
    }

    @Override
    public String handle(OrchestrationRequest request) {
        String content = request.getContent();
        String projectId = request.getConversation().getProjectId();

        // 1. 解析用户提到的分镜编号
        List<Integer> sceneNums = new ArrayList<>();
        Matcher m = SCENE_NUM.matcher(content);
        while (m.find()) {
            sceneNums.add(Integer.parseInt(m.group(1)));
        }

        // 2. 获取项目分镜
        List<Scene> allScenes;
        try {
            allScenes = storyboardClient.getProjectScenes(projectId);
        } catch (Exception e) {
            log.error("获取项目分镜失败: projectId={}, error={}", projectId, e.getMessage());
            return support.sendFriendlyError(request, e.getMessage(), "获取分镜列表失败，请稍后重试。");
        }
        if (allScenes == null || allScenes.isEmpty()) {
            support.sendMessage(request, "当前项目没有分镜，请先生成分镜后再使用此功能。");
            return "";
        }

        // 3. 过滤用户提到的分镜（如果提到了）；否则用全部
        List<Scene> targetScenes;
        if (!sceneNums.isEmpty()) {
            targetScenes = allScenes.stream()
                    .filter(s -> sceneNums.contains(s.getSceneNumber()))
                    .toList();
        } else {
            targetScenes = allScenes;
        }

        if (targetScenes.isEmpty()) {
            support.sendMessage(request, "未找到你提到的分镜，请检查分镜编号。");
            return "";
        }

        return sceneReviewCardService.openReview(request, targetScenes);
    }

    @Override
    public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String action = request.getAction();
        String cpAction = checkpoint.getAction();
        String projectId = request.getConversation().getProjectId();

        // 资产选择卡片续流（asset-selection, source=review-video）：记录勾选 → 关联性门禁 → 生视频
        if ("asset-selection".equals(cpAction)) {
            List<Integer> sceneNums = extractSceneNumsFromPlan(checkpoint);
            // 「不生成视频」→ 直接结束
            if ("review-skip".equals(request.getAction())) {
                support.sendMessage(request, "好的，不生成视频。");
                return "";
            }
            List<Scene> targetScenes = resolveTargetScenes(projectId, sceneNums);
            List<String> ids = "asset-skip".equals(request.getAction())
                    ? List.of() : request.getAssetIds() == null ? List.of() : request.getAssetIds();
            List<AssetVO> chosen = support.pickAssets(
                    storyboardClient.getProjectAssets(projectId), ids);
            // 关联性门禁：弱关联弹澄清卡片；相关/判定失败/无资产放行
            String gate = support.runAssetGate(request, request.getContent(), chosen, "review-video", java.util.List.of());
            if (gate != null) return request.getLastMessage();
            return handleGenVideo(request, targetScenes, ids);
        }
        // 资产门禁澄清卡片续流（asset-gate, source=review-video）
        if ("asset-gate".equals(cpAction)) {
            List<Integer> sceneNums = extractSceneNumsFromPlan(checkpoint);
            List<Scene> targetScenes = resolveTargetScenes(projectId, sceneNums);
            List<String> prevIds = parseAssetIds(support.planField(checkpoint.getPlan(), PlanField.ASSET_IDS));
            List<AssetVO> chosen = support.pickAssets(
                    storyboardClient.getProjectAssets(projectId), prevIds);
            AgentOrchestratorSupport.AssetGateResume r = support.resumeAssetGate(request, checkpoint, chosen);
            if (!r.proceed()) return request.getLastMessage();
            return handleGenVideo(request, targetScenes, r.assetIds());
        }

        // 参考图 role 选择卡片续流（reference-role-select）：用户选择了参考图用途 → 执行视频生成
        if ("reference-role-select".equals(cpAction)) {
            return handleReferenceRoleSelect(request, checkpoint);
        }


        // 优先用前端传来的 assetIds（用户勾选的分镜 ID），回退到 checkpoint 的 sceneNums
        List<String> selectedIds = request.getAssetIds();
        List<Integer> sceneNums = extractSceneNums(checkpoint);
        List<Scene> allScenes;
        try {
            allScenes = storyboardClient.getProjectScenes(projectId);
        } catch (Exception e) {
            return support.sendFriendlyError(request, e.getMessage(), "获取分镜列表失败。");
        }
        if (allScenes == null) allScenes = List.of();

        List<Scene> targetScenes;
        if (selectedIds != null && !selectedIds.isEmpty()) {
            targetScenes = allScenes.stream()
                    .filter(s -> selectedIds.contains(s.getId()))
                    .toList();
        } else if (!sceneNums.isEmpty()) {
            targetScenes = allScenes.stream()
                    .filter(s -> sceneNums.contains(s.getSceneNumber()))
                    .toList();
        } else {
            // 没有任何选中的分镜（前端勾选 id 与 checkpoint 的 sceneNums 均缺失）：
            // 不再回退全量静默入队，明确提示用户先勾选分镜。
            String noSelectionMsg = "没有选择分镜，请先勾选要生成的分镜。";
            support.sendMessage(request, noSelectionMsg);
            support.sendEvent(request, "message_end", Map.of("messageId", "", "sceneCount", -1L, "content", noSelectionMsg));
            return "";
        }

        switch (action) {
            case "review-optimize":
                return handleOptimize(request, targetScenes);
            case "review-gen-image":
                return handleGenImage(request, targetScenes);
            case "review-gen-video":
                // 资产询问：项目有可用资产 → 弹资产选择卡片（勾选/跳过后才生成视频）
                return handleGenVideoWithAssetCheck(request, targetScenes, checkpoint);
            case "review-video-auto":
                return handleGenVideoAuto(request, targetScenes);
            case "review-confirm":
                return handleOptimizeConfirm(request, checkpoint);
            case "review-disagree":
                return handleOptimize(request, targetScenes); // 重新优化
            case "review-skip":
                support.sendMessage(request, "已跳过，不做任何操作。");
                return "";
            default:
                log.warn("SceneReviewIntentHandler: 未知 action={}", action);
                return support.sendFriendlyError(request, "未知操作", "不支持的操作类型。");
        }
    }

    /**
     * 全自动生成视频（门禁「选项一」）：勾选分镜直接入队，AI 不介入。
     * 每镜 prompt/配置从对应分镜取（行内已写回 Scene），参考素材聚合交给队列入队服务。
     */
    private String handleGenVideoAuto(OrchestrationRequest request, List<Scene> scenes) {
        String projectId = request.getConversation().getProjectId();
        if (scenes == null || scenes.isEmpty()) {
            support.sendMessage(request, "没有可生成视频的分镜。");
            return "";
        }
        // 与 review-gen-video 保持一致：仅入队缺视频的分镜，已有视频不重生成。
        List<Scene> missingVideo = scenes.stream()
                .filter(s -> s.getVideoUrl() == null || s.getVideoUrl().isBlank())
                .toList();
        if (missingVideo.isEmpty()) {
            support.sendMessage(request, "所选分镜已有视频，无需生成。");
            return "";
        }
        List<StoryboardVideoEnqueueItem> items = new ArrayList<>();
        for (Scene s : missingVideo) {
            String prompt = (s.getVideoPrompt() != null && !s.getVideoPrompt().isBlank())
                    ? s.getVideoPrompt() : s.getScriptContent();
            items.add(new StoryboardVideoEnqueueItem(
                    s.getId(), prompt, s.getVideoModel(), s.getVideoResolution(), null,
                    s.getVideoAspectRatio(), s.getDuration(), s.getNegativePrompt(),
                    s.getImageUrl(), null, null, java.util.List.of()));
        }
        int count = items.size();
        support.sendEvent(request, "workflow", Map.of("title", "正在提交视频生成队列…", "status", "node_started"));
        storyboardVideoQueueService.enqueue(new StoryboardVideoEnqueueRequest(
                request.getConversation().getId(), projectId, items));
        String resultMsg = String.format("已将 %d 个分镜加入视频生成队列，按顺序依次生成，可随时取消。", count);
        support.sendMessage(request, resultMsg);
        support.sendEvent(request, "message_end", Map.of("messageId", "", "sceneCount", -1L, "content", resultMsg));
        return resultMsg;
    }

    /**
     * 生成视频前的资产检测：项目有可用资产 → 弹资产选择卡片；无资产 → 直接生成。
     */
    private String handleGenVideoWithAssetCheck(OrchestrationRequest request, List<Scene> scenes,
                                                 AgentCheckpoint checkpoint) {
        String projectId = request.getConversation().getProjectId();
        List<AssetVO> projectAssets = storyboardClient.getProjectAssets(projectId);
        if (projectAssets != null && !projectAssets.isEmpty()) {
            // 有资产 → 弹资产选择卡片，checkpoint 存储 sceneNums 供 resume 恢复
            List<Integer> sceneNums = scenes.stream().map(Scene::getSceneNumber).toList();
            return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                    "检测到资产库，本次生成视频要使用哪些资产？（可多选）",
                    "asset-selection",
                    List.of(Map.of("sceneNums", sceneNums, "projectId", projectId, "source", "review-video")),
                    "human_input",
                    List.of(Map.of("id", "asset-confirm", "title", "使用所选资产"),
                            Map.of("id", "asset-skip", "title", "不使用资产，直接生成"),
                            Map.of("id", "review-skip", "title", "不生成视频")),
                    List.of(), Map.of(), Map.of(), List.of(), List.of(),
                    support.buildAssetOptions(projectAssets)));
        }
        // 无资产 → 直接生成
        return handleGenVideo(request, scenes, List.of());
    }

    /**
     * 解析 checkpoint plan 中的 sceneNums 并从数据库获取对应分镜。
     * 供 asset-selection(source=review-video) / asset-gate(source=review-video) resume 使用。
     */
    private List<Scene> resolveTargetScenes(String projectId, List<Integer> sceneNums) {
        try {
            List<Scene> all = storyboardClient.getProjectScenes(projectId);
            if (all == null) return List.of();
            if (sceneNums.isEmpty()) return all;
            return all.stream().filter(s -> sceneNums.contains(s.getSceneNumber())).toList();
        } catch (Exception e) {
            log.warn("获取分镜失败: projectId={}, error={}", projectId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 checkpoint plan 中提取 sceneNums（review-asset-selection / review-asset-gate 专用）。
     */
    private List<Integer> extractSceneNumsFromPlan(AgentCheckpoint cp) {
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(cp.getPlan(), Object.class);
            Map<String, Object> plan;
            if (parsed instanceof List<?> list && !list.isEmpty()) {
                plan = (Map<String, Object>) list.get(0);
            } else if (parsed instanceof Map) {
                plan = (Map<String, Object>) parsed;
            } else {
                return List.of();
            }
            Object nums = plan.get("sceneNums");
            if (nums instanceof List<?> numList) {
                return numList.stream()
                        .map(n -> n instanceof Number ? ((Number) n).intValue() : Integer.parseInt(n.toString()))
                        .toList();
            }
        } catch (Exception ignore) {}
        return List.of();
    }

    /** 优化方案：调 LLM 优化分镜内容 → 展示 → HITL 确认 */
    private String handleOptimize(OrchestrationRequest request, List<Scene> scenes) {
        try {
            support.sendEvent(request, "workflow", Map.of("title", "正在优化分镜方案…", "status", "node_started"));

            // 拼接当前分镜信息给 LLM（每项截断防超长）
            StringBuilder context = new StringBuilder("请优化以下分镜的方案（包括镜头描述、画面构图、拍摄角度等），直接输出优化后的内容：\n\n");
            // ponytail: 全量 scenes 拼接，>10 个分镜可能超 token；按需截断
            for (Scene s : scenes) {
                context.append(String.format("【分镜%d】\n", s.getSceneNumber()));
                String content = s.getScriptContent() != null ? s.getScriptContent() : "无描述";
                if (content.length() > 120) content = content.substring(0, 120) + "…";
                context.append("内容：").append(content).append("\n");
                context.append("\n");
            }

            String optimized = support.streamPlanWithMessage(
                    support.prompt(request, "scene-review", "services/scene-review", null),
                    context.toString(), request);

            // 流式失败/返回空 → 友好提示，不发空 HITL 卡片
            if (optimized == null || optimized.isBlank() || optimized.length() < 10) {
                support.sendEvent(request, "workflow", Map.of("title", "优化失败", "status", "node_finished"));
                return support.sendFriendlyError(request, "stream empty", "优化方案生成失败（网络超时），请稍后重试或减少选择的分镜数量。");
            }

            support.sendEvent(request, "workflow", Map.of("title", "优化完成", "status", "node_finished"));

            String planText = "优化后的分镜方案：\n\n" + optimized
                    + "\n\n请点击「确认更新」更新分镜，或「不满意」重新优化。";

            return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                    planText, "review-confirm",
                    List.of(Map.of("optimized", optimized, "sceneNums", sceneNumsStr(scenes))),
                    "human_input",
                    support.buildHITLCard(request, planText, List.of(
                            Map.of("id", "review-confirm", "title", "确认更新"),
                            Map.of("id", "review-disagree", "title", "不满意"),
                            Map.of("id", "custom", "title", "自定义输入"))),
                    List.of(), Map.of(), Map.of()));
        } catch (Exception e) {
            log.error("SceneReviewIntentHandler.handleOptimize 失败: {}", e.getMessage(), e);
            return support.sendFriendlyError(request, e.getMessage(), "优化方案暂时没生成出来，请稍后重试。");
        }
    }

    /** 确认优化：把优化后的内容写回对应分镜（只更新 scriptContent，保留图/视频提示词与素材关联）→ 追问是否生成图/视频 */
    private String handleOptimizeConfirm(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        // 读 checkpoint 存的优化文本与目标分镜编号（handleOptimize 落库 {optimized, sceneNums}）
        String optimized = support.planField(checkpoint.getPlan(), "optimized");
        List<Integer> sceneNums = extractSceneNums(checkpoint);
        if (optimized != null && !optimized.isBlank() && !sceneNums.isEmpty()) {
            int updated = writeOptimizedBack(request, optimized, sceneNums);
            if (updated > 0) {
                support.sendMessage(request, "✅ 已更新 " + updated + " 个分镜的优化内容！");
            } else {
                support.sendMessage(request, "⚠ 未能解析优化内容，请重新优化。");
            }
        } else {
            support.sendMessage(request, "✅ 分镜方案已更新！");
        }

        // 追问是否生成图/视频
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                "是否需要继续生成图片或视频？",
                "review-gen",
                List.of(Map.of("sceneNums", sceneNums)),
                "human_input",
                support.buildHITLCard(request, "是否需要继续生成图片或视频？", List.of(
                        Map.of("id", "review-gen-image", "title", "生成图片"),
                        Map.of("id", "review-gen-video", "title", "生成视频"),
                        Map.of("id", "review-skip", "title", "不需要"))),
                List.of(), Map.of(), Map.of()));
    }

    /**
     * 把「优化后的分镜方案」文本（【分镜N】...段落）解析并写回对应分镜的 scriptContent。
     * 只更新 scriptContent（镜头描述优化），保留 imagePrompt/videoPrompt/素材关联等已生成资产——
     * 通过按 scene_number 定位现有分镜、updateScene 保留 id 实现。
     *
     * @return 成功更新数
     */
    private int writeOptimizedBack(OrchestrationRequest request, String optimized, List<Integer> sceneNums) {
        String projectId = request.getConversation().getProjectId();
        List<Scene> allScenes;
        try {
            allScenes = storyboardClient.getProjectScenes(projectId);
        } catch (Exception e) {
            log.warn("获取分镜失败: {}", e.getMessage());
            return 0;
        }
        if (allScenes == null) return 0;
        // 解析【分镜N】标题段 → sceneNumber → 优化文本
        java.util.Map<Integer, String> byNum = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("【分镜\\s*(\\d+)】([\\s\\S]*?)(?=【分镜\\s*\\d+】|\\z)").matcher(optimized);
        while (m.find()) {
            byNum.put(Integer.parseInt(m.group(1)), m.group(2).trim());
        }
        int updated = 0;
        for (Scene s : allScenes) {
            if (!sceneNums.contains(s.getSceneNumber())) continue;
            String newText = byNum.get(s.getSceneNumber());
            if (newText == null || newText.isBlank()) continue;
            s.setScriptContent(newText);
            try {
                storyboardClient.updateScene(s); // 保留原 id → 不丢 imageUrl/videoUrl/素材关联
                updated++;
            } catch (Exception e) {
                log.warn("写回分镜{}优化内容失败: {}", s.getSceneNumber(), e.getMessage());
            }
        }
        return updated;
    }

    /** 生成图片：遍历缺图分镜 → 逐个生图 */
    private String handleGenImage(OrchestrationRequest request, List<Scene> scenes) {
        List<Scene> missingImage = scenes.stream()
                .filter(s -> s.getImageUrl() == null || s.getImageUrl().isBlank())
                .toList();

        if (missingImage.isEmpty()) {
            support.sendMessage(request, "所有分镜已有图片，无需生成。");
            return "";
        }

        // ponytail: 批量生图很慢（每个 30-60s），限 3 个防超时；超过提示分批
        List<Scene> batch = missingImage.size() > 3 ? missingImage.subList(0, 3) : missingImage;
        if (missingImage.size() > 3) {
            support.sendMessage(request, "本次最多处理 3 个分镜的图片生成（共 " + missingImage.size() + " 个需要生成），其余请分批操作。");
        }

        support.sendEvent(request, "workflow", Map.of("title", "正在生成图片…", "status", "node_started"));
        AgentConversation conv = request.getConversation();
        int success = 0;
        for (int i = 0; i < batch.size(); i++) {
            Scene s = batch.get(i);
            support.sendEvent(request, "message", Map.of("content",
                    String.format("正在生成分镜%d的图片（%d/%d）…\n", s.getSceneNumber(), i + 1, batch.size())));
            try {
                String prompt = s.getImagePrompt() != null && !s.getImagePrompt().isBlank()
                        ? s.getImagePrompt() : s.getScriptContent();
                if (prompt == null || prompt.isBlank()) prompt = "分镜" + s.getSceneNumber() + "画面";
                List<String> refImages = mergeImageReferences(
                        storyboardClient.getSceneReferences(s.getId()),
                        assetService.buildReferenceImages(assetService.sceneAssets(s.getId(), "image")));
                // 传 sceneId → 生成后自动更新 scenes.imageUrl（而非落 agent_assets）
                Map<String, Object> result = generationService.generateImage(
                        conv, s.getId(), prompt, null, null, null, 1, null, refImages, null);
                @SuppressWarnings("unchecked")
                List<String> urls = (List<String>) result.getOrDefault("imageUrls", List.of());
                if (!urls.isEmpty()) {
                    success++;
                    // 图片 URL 用 markdown 格式发到对话，前端 MessageBubble 渲染为 <img>
                    support.sendEvent(request, "message", Map.of("content",
                            String.format("分镜%d图片生成完成：\n![分镜%d](%s)\n", s.getSceneNumber(), s.getSceneNumber(), urls.get(0))));
                } else {
                    support.sendEvent(request, "message", Map.of("content", String.format("分镜%d图片生成失败。\n", s.getSceneNumber())));
                }
            } catch (Exception e) {
                log.warn("分镜{}生图失败: {}", s.getSceneNumber(), e.getMessage());
                support.sendEvent(request, "message", Map.of("content", String.format("分镜%d图片生成失败：%s\n", s.getSceneNumber(), e.getMessage())));
            }
        }
        support.sendEvent(request, "workflow", Map.of("title", "图片生成完成", "status", "node_finished"));
        String resultMsg = String.format("图片生成完成：%d/%d 成功。请刷新分镜列表查看。", success, batch.size());
        support.sendMessage(request, resultMsg);
        support.sendEvent(request, "message_end", Map.of("messageId", "", "sceneCount", -1L, "content", resultMsg));
        return resultMsg;
    }

    /**
     * 生成视频：遍历缺视频分镜 → 逐个生视频（限 2 个防超时）。
     * assetIds 非空时，用 AssetMatchingService 匹配每个分镜的资产，资产图作视频首帧参考。
     */
    private String handleGenVideo(OrchestrationRequest request, List<Scene> scenes, List<String> assetIds) {
        List<Scene> missingVideo = scenes.stream()
                .filter(s -> s.getVideoUrl() == null || s.getVideoUrl().isBlank())
                .toList();

        if (missingVideo.isEmpty()) {
            support.sendMessage(request, "所有分镜已有视频，无需生成。");
            return "";
        }

        // ponytail: 视频更慢（每个 2-5min），限 2 个
        List<Scene> batch = missingVideo.size() > 2 ? missingVideo.subList(0, 2) : missingVideo;
        if (missingVideo.size() > 2) {
            support.sendMessage(request, "本次最多处理 2 个分镜的视频生成（共 " + missingVideo.size() + " 个需要生成），其余请分批操作。");
        }

        // 资产匹配：分镜列表 × 勾选资产 → 每镜出现的资产（LLM 判定，失败降级不关联）
        Map<Integer, List<String>> sceneAssetMap = Map.of();
        String projectId = request.getConversation().getProjectId();
        if (assetIds != null && !assetIds.isEmpty()) {
            List<AssetVO> chosen = support.pickAssets(storyboardClient.getProjectAssets(projectId), assetIds);
            if (!chosen.isEmpty()) {
                List<Map<String, Object>> sceneMaps = batch.stream().map(s -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("sceneNumber", s.getSceneNumber());
                    m.put("scriptContent", s.getScriptContent() != null ? s.getScriptContent() : "");
                    return m;
                }).toList();
                List<SceneAssetMatch> matches = assetMatchingService.matchScenes(sceneMaps, chosen);
                sceneAssetMap = matches.stream().collect(Collectors.toMap(
                        SceneAssetMatch::sceneNumber, SceneAssetMatch::assetIds, (a, b) -> a));
            }
        }

        support.sendEvent(request, "workflow", Map.of("title", "正在生成视频…", "status", "node_started"));
        AgentConversation conv = request.getConversation();
        int success = 0;
        for (int i = 0; i < batch.size(); i++) {
            Scene s = batch.get(i);
            support.sendEvent(request, "message", Map.of("content",
                    String.format("正在生成分镜%d的视频（%d/%d）…\n", s.getSceneNumber(), i + 1, batch.size())));
            try {
                String prompt = s.getVideoPrompt() != null && !s.getVideoPrompt().isBlank()
                        ? s.getVideoPrompt() : s.getScriptContent();
                if (prompt == null || prompt.isBlank()) prompt = "分镜" + s.getSceneNumber() + "视频";
                // 资产匹配结果：该分镜关联的资产图作参考图
                List<String> matchedAssetIds = sceneAssetMap.getOrDefault(s.getSceneNumber(), List.of());
                List<String> refImages = List.of();
                String sourceImage = s.getImageUrl();
                if (!matchedAssetIds.isEmpty()) {
                    List<AssetVO> allAssets = storyboardClient.getProjectAssets(projectId);
                    refImages = matchedAssetIds.stream()
                            .flatMap(id -> allAssets.stream().filter(a -> a.id().equals(id)))
                            .flatMap(a -> a.images() != null ? a.images().stream() : java.util.stream.Stream.empty())
                            .map(com.moon.moonagent.dto.response.AssetImageVO::url)
                            .filter(url -> url != null && !url.isBlank())
                            .toList();
                    // 无分镜图时，用第一张资产图作首帧
                    if ((sourceImage == null || sourceImage.isBlank()) && !refImages.isEmpty()) {
                        sourceImage = refImages.get(0);
                    }
                }
                List<Map<String, Object>> sceneRefs = storyboardClient.getSceneReferences(s.getId());
                refImages = mergeImageReferences(sceneRefs, refImages);
                List<String> referenceVideos = sceneRefs.stream()
                        .filter(ref -> ref != null && "video".equalsIgnoreCase(String.valueOf(ref.get("type"))))
                        .map(ref -> ref.get("url") == null ? "" : String.valueOf(ref.get("url")))
                        .filter(url -> !url.isBlank())
                        .distinct()
                        .toList();
                // 传 sceneId → 创建任务后自动关联到 scenes 表
                List<ReferenceImage> refImageList = refImages.stream().map(ReferenceImage::ofReference).toList();
                String taskId = generationService.createVideoTaskWithRole(
                        conv, s.getId(), prompt, null, null, null, null, null, null,
                        refImageList, sourceImage, referenceVideos);
                if (taskId != null && !taskId.isBlank()) {
                    success++;
                    support.sendEvent(request, "message", Map.of("content", String.format("分镜%d视频任务已提交（%s）。\n", s.getSceneNumber(), taskId)));
                } else {
                    support.sendEvent(request, "message", Map.of("content", String.format("分镜%d视频任务创建失败。\n", s.getSceneNumber())));
                }
            } catch (Exception e) {
                log.warn("分镜{}生视频失败: {}", s.getSceneNumber(), e.getMessage());
                support.sendEvent(request, "message", Map.of("content", String.format("分镜%d视频生成失败：%s\n", s.getSceneNumber(), e.getMessage())));
            }
        }
        support.sendEvent(request, "workflow", Map.of("title", "视频任务已提交", "status", "node_finished"));
        String resultMsg = String.format("视频任务已提交：%d/%d，后台生成中。请刷新分镜列表查看进度。", success, batch.size());
        support.sendMessage(request, resultMsg);
        support.sendEvent(request, "message_end", Map.of("messageId", "", "sceneCount", -1L, "content", resultMsg));
        return resultMsg;
    }

    /**
     * 弹出参考图 role 选择卡片（人工介入）。
     * 当分镜有 1-2 张参考图时，询问用户用途（首帧/尾帧/参考图）。
     */
    private String showReferenceRoleCard(OrchestrationRequest request, Scene scene, List<String> refUrls) {
        int count = refUrls.size();

        List<Map<String, Object>> actions = new ArrayList<>();
        if (count == 1) {
            actions.add(Map.of("id", "first_frame", "title", "作为首帧", "description", "锁定视频起始画面"));
            actions.add(Map.of("id", "last_frame", "title", "作为尾帧", "description", "锁定视频结束画面"));
            actions.add(Map.of("id", "reference", "title", "作为参考图", "description", "模型自由演绎"));
        } else {
            actions.add(Map.of("id", "first_last_frame", "title", "作为首尾帧", "description", "图1为首帧，图2为尾帧"));
            actions.add(Map.of("id", "reference", "title", "作为参考图", "description", "两张都作为参考图"));
        }
        actions.add(Map.of("id", "cancel", "title", "取消", "description", "取消本次视频生成"));

        StringBuilder planText = new StringBuilder();
        planText.append(String.format("🎬 分镜%d有%d张参考图片，请选择用途：\n\n", scene.getSceneNumber(), count));
        for (int i = 0; i < refUrls.size(); i++) {
            planText.append(String.format("- 图%d: %s\n", i + 1, refUrls.get(i)));
        }

        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                planText.toString(), "reference-role-select",
                List.of(Map.of("sceneId", scene.getId(), "sceneNumber", scene.getSceneNumber(),
                        "refUrls", refUrls, "prompt", scene.getVideoPrompt() != null ? scene.getVideoPrompt() : scene.getScriptContent())),
                "human_input",
                actions,
                List.of(), Map.of(), Map.of(), List.of(), List.of(), List.of()));
    }

    /**
     * 处理参考图 role 选择结果（resume 方法中调用）。
     */
    private String handleReferenceRoleSelect(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String action = request.getAction();
        if ("cancel".equals(action)) {
            support.sendMessage(request, "已取消视频生成。");
            return "";
        }

        // 解析 checkpoint 中的分镜信息
        String sceneId = extractStringFromPlan(checkpoint, "sceneId");
        int sceneNumber = extractIntFromPlan(checkpoint, "sceneNumber");
        List<String> refUrls = extractListFromPlan(checkpoint, "refUrls");
        String prompt = extractStringFromPlan(checkpoint, "prompt");

        if (sceneId == null || refUrls == null || refUrls.isEmpty()) {
            support.sendMessage(request, "参考图信息丢失，请重试。");
            return "";
        }

        // 根据用户选择构建 ReferenceImage 列表
        List<ReferenceImage> refImages;
        if ("first_last_frame".equals(action) && refUrls.size() >= 2) {
            refImages = List.of(
                    ReferenceImage.ofFirstFrame(refUrls.get(0)),
                    ReferenceImage.ofLastFrame(refUrls.get(1))
            );
            log.info("参考图 role 选择: 分镜{} 使用首尾帧模式", sceneNumber);
        } else {
            ReferenceImageRole role = switch (action) {
                case "first_frame" -> ReferenceImageRole.FIRST_FRAME;
                case "last_frame" -> ReferenceImageRole.LAST_FRAME;
                default -> ReferenceImageRole.REFERENCE;
            };
            refImages = refUrls.stream()
                    .map(url -> new ReferenceImage(url, role))
                    .toList();
            log.info("参考图 role 选择: 分镜{} 使用 {} 模式, 图片数={}", sceneNumber, role, refImages.size());
        }

        // 执行视频生成
        try {
            support.sendEvent(request, "workflow", Map.of("title", "正在生成视频…", "status", "node_started"));
            AgentConversation conv = request.getConversation();
            String taskId = generationService.createVideoTaskWithRole(
                    conv, sceneId, prompt, null, null, null, null, null, null, refImages, null);

            if (taskId != null && !taskId.isBlank()) {
                String resultMsg = String.format("✅ 分镜%d视频任务已提交（%s），后台生成中。", sceneNumber, taskId);
                support.sendMessage(request, resultMsg);
                support.sendEvent(request, "message", Map.of("content", resultMsg));
            } else {
                support.sendMessage(request, String.format("❌ 分镜%d视频任务创建失败。", sceneNumber));
            }
            support.sendEvent(request, "workflow", Map.of("title", "视频任务已提交", "status", "node_finished"));
        } catch (Exception e) {
            log.warn("分镜{}生视频失败: {}", sceneNumber, e.getMessage());
            support.sendMessage(request, String.format("❌ 分镜%d视频生成失败：%s", sceneNumber, e.getMessage()));
        }
        return "";
    }

    /** 从 checkpoint plan 中提取字符串字段 */
    private String extractStringFromPlan(AgentCheckpoint cp, String field) {
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(cp.getPlan(), Object.class);
            Map<String, Object> plan;
            if (parsed instanceof List<?> list && !list.isEmpty()) {
                plan = (Map<String, Object>) list.get(0);
            } else if (parsed instanceof Map) {
                plan = (Map<String, Object>) parsed;
            } else {
                return null;
            }
            Object value = plan.get(field);
            return value != null ? value.toString() : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 从 checkpoint plan 中提取整数字段 */
    private int extractIntFromPlan(AgentCheckpoint cp, String field) {
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(cp.getPlan(), Object.class);
            Map<String, Object> plan;
            if (parsed instanceof List<?> list && !list.isEmpty()) {
                plan = (Map<String, Object>) list.get(0);
            } else if (parsed instanceof Map) {
                plan = (Map<String, Object>) parsed;
            } else {
                return 0;
            }
            Object value = plan.get(field);
            if (value instanceof Number num) return num.intValue();
            return Integer.parseInt(value.toString());
        } catch (Exception ignore) {
            return 0;
        }
    }

    /** 从 checkpoint plan 中提取列表字段 */
    @SuppressWarnings("unchecked")
    private List<String> extractListFromPlan(AgentCheckpoint cp, String field) {
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(cp.getPlan(), Object.class);
            Map<String, Object> plan;
            if (parsed instanceof List<?> list && !list.isEmpty()) {
                plan = (Map<String, Object>) list.get(0);
            } else if (parsed instanceof Map) {
                plan = (Map<String, Object>) parsed;
            } else {
                return List.of();
            }
            Object value = plan.get(field);
            if (value instanceof List<?> list) {
                return list.stream().map(Object::toString).toList();
            }
            return List.of();
        } catch (Exception ignore) {
            return List.of();
        }
    }


    // ===== 工具方法 =====

    @SuppressWarnings("unchecked")
    private List<Integer> extractSceneNums(AgentCheckpoint cp) {
        try {
            if (cp.getPlan() == null) return List.of();
            // plan 是 JSON 数组 [{"sceneNums": [...], ...}]，取第一个元素
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(cp.getPlan(), Object.class);
            Map<String, Object> plan;
            if (parsed instanceof List<?> list && !list.isEmpty()) {
                plan = (Map<String, Object>) list.get(0);
            } else if (parsed instanceof Map) {
                plan = (Map<String, Object>) parsed;
            } else {
                return List.of();
            }
            Object nums = plan.get("sceneNums");
            if (nums instanceof List<?> numList) {
                return numList.stream()
                        .map(n -> n instanceof Number ? ((Number) n).intValue() : Integer.parseInt(n.toString()))
                        .toList();
            }
            if (nums instanceof String str && !str.isBlank()) {
                return java.util.Arrays.stream(str.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(Integer::parseInt).toList();
            }
        } catch (Exception ignore) {}
        return List.of();
    }

    private String sceneNumsStr(List<Scene> scenes) {
        return scenes.stream().map(s -> String.valueOf(s.getSceneNumber())).collect(Collectors.joining(","));
    }

    /** 逗号分隔资产 ID 字符串 → List（宽松解析） */
    private List<String> parseAssetIds(String s) {
        if (s == null || s.isBlank()) return List.of();
        return java.util.Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    /** 合并分镜已挂参考图和 AI 资产图，仅保留图片并按出现顺序去重。 */
    static List<String> mergeImageReferences(List<Map<String, Object>> sceneRefs, List<String> assetImages) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (sceneRefs != null) {
            for (Map<String, Object> ref : sceneRefs) {
                if (ref == null) continue;
                Object type = ref.get("type");
                if (type != null && !type.toString().isBlank() && !"image".equalsIgnoreCase(type.toString())) continue;
                Object url = ref.get("url");
                if (url != null && !url.toString().isBlank()) merged.add(url.toString());
            }
        }
        if (assetImages != null) {
            assetImages.stream().filter(url -> url != null && !url.isBlank()).forEach(merged::add);
        }
        return List.copyOf(merged);
    }
}








