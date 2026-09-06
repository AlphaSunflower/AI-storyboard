package com.moon.moonagent.ai.agent.storyboard;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.config.PromptConfig;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.ai.agent.AgentSceneItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 分镜素材管线（三个子智能体的应用层编排，符合 Spring AI 2.0「无原生多智能体，用 planner/executor」约定）：
 * <ol>
 *   <li>整理智能体 {@link #bindMaterials}：提示词 ↔ 素材图 一一绑定；</li>
 *   <li>分镜方案设计师 {@link #designPlan}：决定素材进哪个分镜、首帧/尾帧/首尾帧/全能参考、全局画幅；</li>
 *   <li>JSON 转换智能体 {@link #toScenes}：把已确认方案转标准分镜 JSON。</li>
 * </ol>
 * 任一 LLM 失败都走确定性兜底（不阻塞链路），底层复用网关默认文本模型 + BeanOutputConverter。
 */
@Service
public class StoryboardPortfolioService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardPortfolioService.class);

    private final GatewayModelService gatewayModelService;
    private final PromptConfig promptConfig;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile ChatClient client;

    public StoryboardPortfolioService(GatewayModelService gatewayModelService,
                                      PromptConfig promptConfig,
                                      ChatClient.Builder chatClientBuilder) {
        this.gatewayModelService = gatewayModelService;
        this.promptConfig = promptConfig;
        this.chatClientBuilder = chatClientBuilder;
    }

    /** LLM 结构化输出目标：素材绑定结果 */
    public record BindResult(List<StoryboardReferenceBinding> bindings, String message) {}

    /** LLM 结构化输出目标：分镜方案 */
    public record DesignResult(List<StoryboardScenePlan> scenes, String aspectRatio, String message) {}

    // ================= 整理智能体 =================

    /**
     * 素材整理与绑定：输入 = 用户剧情/提示词 + 本次上传图（PicUrls）+ 带文字约束的资产。
     * 判定：text 非空 → kind=asset（不整理）；text 空 → kind=reference（视觉/文本映射支撑哪句提示词）；
     * 未说明用途 → purposeText=「用户未明确说明」；成组「图1~N」→ 同 groupLabel。
     *
     * @param materialPurposes 上传素材 URL → 用户填写的用途（agent_assets.purpose，可空）
     * @param materialKinds    上传素材 URL → kind（reference/asset，可空）
     */
    public List<StoryboardReferenceBinding> bindMaterials(String content,
                                                          List<String> picUrls,
                                                          List<AssetVO> assets,
                                                          Map<String, String> materialPurposes,
                                                          Map<String, String> materialKinds) {
        Map<String, String> purposes = materialPurposes == null ? Map.of() : materialPurposes;
        Map<String, String> kinds = materialKinds == null ? Map.of() : materialKinds;
        // 1) 项目资产（带文字约束）：无需整理，直接作为 asset 绑定
        List<StoryboardReferenceBinding> assetBindings = new ArrayList<>();
        if (assets != null) {
            for (AssetVO a : assets) {
                if (a.images() == null || a.images().isEmpty()) continue;
                for (var img : a.images()) {
                    String purpose = (img.text() == null || img.text().isBlank())
                            ? (a.description() == null || a.description().isBlank() ? "用户未明确说明" : a.description())
                            : img.text();
                    assetBindings.add(new StoryboardReferenceBinding(
                            a.id() + "#" + (img.id() == null ? "" : img.id()),
                            img.url(), "asset", purpose, purpose, a.name(), null));
                }
            }
        }
        // 2) 用户本次上传：kind=asset + 已填用途 → 视为资产（无需整理）；其余 → reference 候选
        List<StoryboardReferenceBinding> refBindings = new ArrayList<>();
        int uploadIndex = 0;
        if (picUrls != null) {
            for (String u : picUrls) {
                if (u == null || u.isBlank()) continue;
                uploadIndex++;
                String purpose = purposes.getOrDefault(u, "");
                boolean isAsset = "asset".equalsIgnoreCase(kinds.getOrDefault(u, "reference"))
                        && !purpose.isBlank();
                StoryboardReferenceBinding b = new StoryboardReferenceBinding(
                        "图" + uploadIndex, u,
                        isAsset ? "asset" : "reference",
                        purpose.isBlank() ? "用户未明确说明" : purpose,
                        isAsset ? purpose : "",
                        null, null);
                if (isAsset) assetBindings.add(b); else refBindings.add(b);
            }
        }
        // 3) 整理智能体只对 reference 候选做「提示词↔图」映射；asset 直挂用途不重写
        List<StoryboardReferenceBinding> organizedRefs = organizeReferenceBindings(content, refBindings);
        List<StoryboardReferenceBinding> out = new ArrayList<>(assetBindings);
        out.addAll(organizedRefs);
        return out;
    }

    /** reference 类素材的整理 LLM：输出 bindings；失败/空输出回退候选（含用户用途）。 */
    private List<StoryboardReferenceBinding> organizeReferenceBindings(String content,
                                                                      List<StoryboardReferenceBinding> refs) {
        if (refs == null || refs.isEmpty()) return List.of();
        try {
            StringBuilder inventory = new StringBuilder();
            int idx = 1;
            for (StoryboardReferenceBinding b : refs) {
                inventory.append("图片").append(idx++).append("：").append(b.url())
                        .append("（用途：").append(b.purposeText()).append("）\n");
            }
            String system = promptConfig.get("orchestrator/material-bind");
            String user = "用户剧情/提示词：\n" + content
                    + "\n\n素材清单：\n" + inventory
                    + "\n\n只输出这些 reference 图的 bindings（assetId 用「图片N」编号，kind=reference，"
                    + "purposeText/supportedPrompt 填写该图支撑的提示词或用途；未说明则 purposeText=用户未明确说明）。";
            String raw = call(system, user, false);
            Map<String, Object> parsed = StoryboardJsonParser.parseBindResult(raw);
            Object rawBindings = parsed.getOrDefault("bindings", List.of());
            if (rawBindings instanceof List<?> list && !list.isEmpty()) {
                List<StoryboardReferenceBinding> mapped = new ArrayList<>();
                for (Object item : list) {
                    try {
                        StoryboardReferenceBinding b = objectMapper.convertValue(item, StoryboardReferenceBinding.class);
                        if (b != null && "reference".equalsIgnoreCase(b.kind())) mapped.add(b);
                    } catch (Exception ignored) {}
                }
                if (!mapped.isEmpty()) return mapped;
            }
        } catch (Exception e) {
            log.warn("素材整理 LLM 调用失败，走候选兜底: {}", e.getMessage());
        }
        return refs;
    }

    // ================= 分镜方案设计师 =================

    /**
     * 分镜方案设计：输入 = 剧情/提示词 + 素材绑定；输出逐镜方案（每个分镜的素材集合 + 参考角色 + 全局画幅）。
     * 参考角色规则在提示词与兜底中落地。
     */
    public StoryboardDesignPlan designPlan(String content, List<StoryboardReferenceBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return new StoryboardDesignPlan(List.of(), "16:9", "无素材，走常规分镜生成");
        }
        try {
            String system = promptConfig.get("orchestrator/storyboard-plan-designer");
            String user = "素材绑定：\n" + bindingsText(bindings)
                    + "\n\n用户剧情/提示词：\n" + content
                    + "\n\n请输出逐镜方案：决定每个素材进哪个分镜、作首帧/尾帧/首尾帧/全能参考；全局画幅；每个分镜的 videoPrompt 按 H3 三段式（含 @图片N 与参考素材说明）。";
            String raw = call(system, user, false);
            Map<String, Object> parsed = StoryboardJsonParser.parseDesignResult(raw);
            Object rawScenes = parsed.getOrDefault("scenes", List.of());
            if (rawScenes instanceof List<?> list && !list.isEmpty()) {
                List<StoryboardScenePlan> scenePlans = new ArrayList<>();
                for (Object item : list) {
                    try {
                        StoryboardScenePlan p = objectMapper.convertValue(item, StoryboardScenePlan.class);
                        if (p != null) scenePlans.add(p);
                    } catch (Exception ignored) {}
                }
                if (!scenePlans.isEmpty()) {
                    return new StoryboardDesignPlan(scenePlans,
                            String.valueOf(parsed.getOrDefault("aspectRatio", "16:9")),
                            String.valueOf(parsed.getOrDefault("message", "")));
                }
            }
            return fallbackDesign(content, bindings);
        } catch (Exception e) {
            log.warn("分镜方案设计 LLM 调用失败，走确定性兜底: {}", e.getMessage());
            return fallbackDesign(content, bindings);
        }
    }

    private StoryboardDesignPlan fallbackDesign(String content, List<StoryboardReferenceBinding> bindings) {
        StoryboardReferenceRole role = bindings.size() == 1 ? StoryboardReferenceRole.FIRST_FRAME
                : bindings.size() == 2 ? StoryboardReferenceRole.FIRST_LAST_FRAME
                : StoryboardReferenceRole.FULL_REFERENCE;
        List<StoryboardReferenceBinding> sceneBindings = bindings.stream()
                .map(b -> b.withRole(b.role() == null || b.role().isBlank() ? role.name() : StoryboardReferenceRole.parse(b.role()).name()))
                .toList();
        String videoPrompt = buildH3VideoPrompt(content, sceneBindings);
        StoryboardScenePlan scene = new StoryboardScenePlan(1, content, content, videoPrompt,
                "模糊,畸变,多余文字,水印,logo,形变,多余肢体,低清晰度,噪点", "", "全景", "", sceneBindings);
        return new StoryboardDesignPlan(List.of(scene), "16:9", "已按 H3 规范生成分镜方案");
    }

    /** H3 三段式 videoPrompt 组装（纯逻辑，供兜底与单元测试）。 */
    public static String buildH3VideoPrompt(String content, List<StoryboardReferenceBinding> bindings) {
        StringBuilder sb = new StringBuilder();
        if (bindings != null && !bindings.isEmpty()) {
            sb.append("【参考素材说明】");
            List<String> notes = new ArrayList<>();
            for (int i = 0; i < bindings.size(); i++) {
                StoryboardReferenceBinding b = bindings.get(i);
                String name = "图片" + (i + 1);
                String purpose = b.purposeText() == null || b.purposeText().isBlank() ? "参考素材" : b.purposeText();
                String roleNote = switch (StoryboardReferenceRole.parse(b.role())) {
                    case FIRST_FRAME -> "作为首帧（0 秒画面起点）";
                    case LAST_FRAME -> "作为尾帧（结束画面）";
                    case FIRST_LAST_FRAME -> (i == 0 ? "作为首帧" : (i == bindings.size() - 1 ? "作为尾帧" : "中间过渡参考"));
                    default -> "作为全能参考";
                };
                notes.add("@" + name + " 是" + purpose + "（" + roleNote + "）");
            }
            sb.append(String.join("；", notes)).append("。\n");
        }
        sb.append("【核心创意】").append(content == null || content.isBlank() ? "动态画面" : content).append("\n");
        sb.append("【画面过程说明】依据剧情分段：全景交代空间 → 中景承载动作 → 特写强调细节，写明运镜、动作、音效。\n");
        sb.append("非叙事性音乐：N/A");
        return sb.toString();
    }

    private String bindingsText(List<StoryboardReferenceBinding> bindings) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (StoryboardReferenceBinding b : bindings) {
            sb.append("图").append(i++).append("（").append(b.assetId()).append("）：")
                    .append(b.kind()).append(" · ").append(b.purposeText())
                    .append(b.supportedPrompt() == null || b.supportedPrompt().isBlank() ? "" : " · 对应提示词：" + b.supportedPrompt())
                    .append(b.groupLabel() == null || b.groupLabel().isBlank() ? "" : " · 分组：" + b.groupLabel())
                    .append("\n");
        }
        return sb.toString();
    }

    // ================= JSON 转换智能体 =================

    /**
     * JSON 转换：把已确认的分镜方案转标准分镜 JSON（8 字段 + referenceBindings）。
     * 方案已含全部字段，此处主要做字段规范化与兜底（不额外调 LLM）。
     */
    public List<AgentSceneItem> toScenes(StoryboardDesignPlan plan) {
        List<AgentSceneItem> out = new ArrayList<>();
        if (plan == null || plan.scenes() == null) return out;
        for (StoryboardScenePlan s : plan.scenes()) {
            out.add(new AgentSceneItem(s.sceneNumber(),
                    nvl(s.scriptContent(), "分镜" + s.sceneNumber()),
                    nvl(s.imagePrompt(), s.scriptContent()),
                    nvl(s.videoPrompt(), buildH3VideoPrompt(s.scriptContent(), s.referenceBindings())),
                    nvl(s.negativePrompt(), "模糊,畸变,多余文字,水印,logo,形变,多余肢体,低清晰度,噪点"),
                    nvl(s.cameraMovement(), ""),
                    nvl(s.shotType(), "全景"),
                    nvl(s.soundDesign(), "")));
        }
        return out;
    }

    private static String nvl(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    // ================= 底稿 =================

    private String call(String system, String user, boolean vision) {
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                ChatClient.ChatClientRequestSpec spec = client().prompt()
                        .system(system)
                        .user(user);
                if (!vision) {
                    // Spring AI 2.0：options 直接传 Builder（不要 .build()）
                    spec = spec.options(OpenAiChatOptions.builder().model(gatewayModelService.getDefaultTextModel()));
                }
                String raw = spec.call().content();
                return raw == null ? "" : raw.trim();
            } catch (Exception e) {
                if (attempts < 2 && isTransient(e)) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                throw e;
            }
        }
    }

    private static boolean isTransient(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof org.springframework.web.client.HttpStatusCodeException sce) {
                int s = sce.getStatusCode().value();
                if (s == 429 || s >= 500) return true;
            }
            if (c instanceof java.net.ConnectException
                    || c instanceof java.net.http.HttpTimeoutException
                    || c instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private ChatClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = chatClientBuilder
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .timeout(Duration.ofSeconds(120)))
                            .build();
                }
            }
        }
        return client;
    }

}
