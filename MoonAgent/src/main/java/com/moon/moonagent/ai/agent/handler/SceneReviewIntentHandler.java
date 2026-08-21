package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.AgentTools;
import com.moon.moonagent.ai.agent.AssetMatchingService;
import com.moon.moonagent.ai.agent.SceneAssetMatch;
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

    /** 从消息中提取分镜编号 */
    private static final Pattern SCENE_NUM = Pattern.compile("分镜(\\d+)");

    @Override
    public String intentType() {
        return "intent-scene-review";
    }

    @Override
    public Set<String> resumeActions() {
        return Set.of("review-optimize", "review-gen-image", "review-gen-video", "review-skip",
                "review-confirm", "review-disagree");
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

        // 4. 分析生成状态
        boolean hasMissingImage = targetScenes.stream().anyMatch(s -> s.getImageUrl() == null || s.getImageUrl().isBlank());
        boolean hasMissingVideo = targetScenes.stream().anyMatch(s -> s.getVideoUrl() == null || s.getVideoUrl().isBlank());

        // 5. 组装状态摘要
        StringBuilder summary = new StringBuilder("📋 分镜审查结果：\n");
        for (Scene s : targetScenes) {
            String img = (s.getImageUrl() != null && !s.getImageUrl().isBlank()) ? "✅有图" : "❌缺图";
            String vid = (s.getVideoUrl() != null && !s.getVideoUrl().isBlank()) ? "✅有视频" : "❌缺视频";
            summary.append(String.format("- 分镜%d：%s | %s\n", s.getSceneNumber(), img, vid));
        }

        // 6. 动态组装 HITL 选项
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(Map.of("id", "review-optimize", "title", "优化方案"));
        if (hasMissingImage) {
            actions.add(Map.of("id", "review-gen-image", "title", "生成图片"));
        }
        if (hasMissingVideo) {
            actions.add(Map.of("id", "review-gen-video", "title", "生成视频"));
        }
        actions.add(Map.of("id", "review-skip", "title", "跳过"));

        String planText = summary + "\n请选择要执行的操作：";

        // 7. HITL 卡片（checkpoint 存储目标分镜编号供 resume 使用）
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                planText, "review-action",
                List.of(Map.of("sceneNums", sceneNums, "projectId", projectId)),
                "human_input",
                actions,
                List.of(), Map.of(), Map.of(), List.of(), List.of(), buildSceneOptions(targetScenes)));
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
            String gate = support.runAssetGate(request, request.getContent(), chosen, "review-video", "");
            if (gate != null) return request.getLastMessage();
            return handleGenVideo(request, targetScenes, ids);
        }
        // 资产门禁澄清卡片续流（asset-gate, source=review-video）
        if ("asset-gate".equals(cpAction)) {
            List<Integer> sceneNums = extractSceneNumsFromPlan(checkpoint);
            List<Scene> targetScenes = resolveTargetScenes(projectId, sceneNums);
            List<String> prevIds = parseAssetIds(support.planField(checkpoint.getPlan(), "assetIds"));
            List<AssetVO> chosen = support.pickAssets(
                    storyboardClient.getProjectAssets(projectId), prevIds);
            AgentOrchestratorSupport.AssetGateResume r = support.resumeAssetGate(request, checkpoint, chosen);
            if (!r.proceed()) return request.getLastMessage();
            return handleGenVideo(request, targetScenes, r.assetIds());
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
            targetScenes = allScenes;
        }

        switch (action) {
            case "review-optimize":
                return handleOptimize(request, targetScenes);
            case "review-gen-image":
                return handleGenImage(request, targetScenes);
            case "review-gen-video":
                // 资产询问：项目有可用资产 → 弹资产选择卡片（勾选/跳过后才生成视频）
                return handleGenVideoWithAssetCheck(request, targetScenes, checkpoint);
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
                    promptConfig.get("services/scene-review"),
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
                    List.of(Map.of("id", "review-confirm", "title", "确认更新"),
                            Map.of("id", "review-disagree", "title", "不满意"),
                            Map.of("id", "custom", "title", "自定义输入")),
                    List.of(), Map.of(), Map.of()));
        } catch (Exception e) {
            log.error("SceneReviewIntentHandler.handleOptimize 失败: {}", e.getMessage(), e);
            return support.sendFriendlyError(request, e.getMessage(), "优化方案暂时没生成出来，请稍后重试。");
        }
    }

    /** 确认优化：更新分镜 → 追问是否生成图/视频 */
    private String handleOptimizeConfirm(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        support.sendMessage(request, "✅ 分镜方案已更新！");

        // 追问是否生成图/视频
        List<Integer> sceneNums = extractSceneNums(checkpoint);
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                "是否需要继续生成图片或视频？",
                "review-gen",
                List.of(Map.of("sceneNums", sceneNums)),
                "human_input",
                List.of(Map.of("id", "review-gen-image", "title", "生成图片"),
                        Map.of("id", "review-gen-video", "title", "生成视频"),
                        Map.of("id", "review-skip", "title", "不需要")),
                List.of(), Map.of(), Map.of()));
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
                // 传 sceneId → 生成后自动更新 scenes.imageUrl（而非落 agent_assets）
                Map<String, Object> result = generationService.generateImage(
                        conv, s.getId(), prompt, null, null, null, 1, null, null, null);
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
                // 传 sceneId → 创建任务后自动关联到 scenes 表
                String taskId = generationService.createVideoTask(
                        conv, s.getId(), prompt, null, null, null, null, null, null, refImages, sourceImage);
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

    /** 分镜 → AssetOption 格式（前端 HumanInputCard 勾选列表渲染） */
    private List<Map<String, Object>> buildSceneOptions(List<Scene> scenes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Scene s : scenes) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", s.getId());
            String desc = s.getScriptContent();
            if (desc != null && desc.length() > 30) desc = desc.substring(0, 30) + "…";
            m.put("name", "分镜" + s.getSceneNumber() + ": " + (desc != null ? desc : ""));
            m.put("type", "scene");
            if (s.getImageUrl() != null && !s.getImageUrl().isBlank()) m.put("image", s.getImageUrl());
            out.add(m);
        }
        return out;
    }

    private String sceneNumsStr(List<Scene> scenes) {
        return scenes.stream().map(s -> String.valueOf(s.getSceneNumber())).collect(Collectors.joining(","));
    }

    /** 逗号分隔资产 ID 字符串 → List（宽松解析） */
    private List<String> parseAssetIds(String s) {
        if (s == null || s.isBlank()) return List.of();
        return java.util.Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
