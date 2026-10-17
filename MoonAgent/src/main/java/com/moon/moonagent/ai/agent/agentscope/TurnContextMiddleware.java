package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.document.AgentDocumentService;
import com.moon.moonagent.ai.agent.document.DocumentSummaryView;
import com.moon.moonagent.ai.agent.document.LibraryReferenceAssetService;
import com.moon.moonagent.ai.agent.canvas.CanvasContextPromptBuilder;
import com.moon.moonagent.ai.agent.AgentGenerationPreferences;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.scene.SceneReferenceService;
import com.moon.moonagent.ai.agent.scene.SceneReferenceView;
import com.moon.moonagent.dto.request.AgentAttachmentRequest;
import com.moon.moonagent.dto.request.AgentReferenceRequest;
import com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowService;
import com.moon.moonagent.ai.agent.creative.ConstraintExtractionService;
import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Slf4j
final class TurnContextMiddleware implements MiddlewareBase {

    /** {@code <turn-references>} 整块字符预算；超出按引用顺序截断并标 {@code truncated=true}。 */
    static final int REFERENCE_BUDGET_CHARS = 6000;

    /** {@code token / name} 等用户来源文本的单段长度上限（防止单条引用挤掉整块预算）。 */
    private static final int REFERENCE_TOKEN_MAX_CHARS = 120;

    /** 解析不到分镜时的兜底说明（{@code reason} 为空时使用）。 */
    private static final String SCENE_UNRESOLVED_FALLBACK = "没有找到该分镜，请向用户确认";

    private final AgentWorkflowService workflows;
    private final ProjectCreativeContextService creativeContext;
    private final ConstraintExtractionService constraints;
    private final AgentDocumentService documents;
    /** 能力可用性上报器；未接线时为 null，此时不注入能力块。 */
    private final TurnCapabilityReporter capabilities;
    /** 分镜引用解析服务；未接线时为 null，此时分镜引用按「解析不到」渲染。 */
    private final SceneReferenceService sceneReferences;
    /** 项目库文件 → 会话资产转存服务；未接线时为 null，此时项目库文件只能给元数据（正文不可用）。 */
    private final LibraryReferenceAssetService libraryAssets;

    TurnContextMiddleware(AgentWorkflowService workflows,
                          ProjectCreativeContextService creativeContext,
                          ConstraintExtractionService constraints,
                          AgentDocumentService documents) {
        this(workflows, creativeContext, constraints, documents, null, null, null);
    }

    TurnContextMiddleware(AgentWorkflowService workflows,
                          ProjectCreativeContextService creativeContext,
                          ConstraintExtractionService constraints,
                          AgentDocumentService documents,
                          TurnCapabilityReporter capabilities) {
        this(workflows, creativeContext, constraints, documents, capabilities, null, null);
    }

    TurnContextMiddleware(AgentWorkflowService workflows,
                          ProjectCreativeContextService creativeContext,
                          ConstraintExtractionService constraints,
                          AgentDocumentService documents,
                          TurnCapabilityReporter capabilities,
                          SceneReferenceService sceneReferences) {
        this(workflows, creativeContext, constraints, documents, capabilities, sceneReferences, null);
    }

    TurnContextMiddleware(AgentWorkflowService workflows,
                          ProjectCreativeContextService creativeContext,
                          ConstraintExtractionService constraints,
                          AgentDocumentService documents,
                          TurnCapabilityReporter capabilities,
                          SceneReferenceService sceneReferences,
                          LibraryReferenceAssetService libraryAssets) {
        this.workflows = workflows;
        this.creativeContext = creativeContext;
        this.constraints = constraints;
        this.documents = documents;
        this.capabilities = capabilities;
        this.sceneReferences = sceneReferences;
        this.libraryAssets = libraryAssets;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        if (context == null) return Mono.just(prompt);
        AgentScopeRunContext runContext = context.get(AgentScopeRunContext.class);
        if (runContext == null || runContext.request() == null) return Mono.just(prompt);
        OrchestrationRequest request = runContext.request();
        List<String> lines = new ArrayList<>();
        String canvasMode = CanvasContextPromptBuilder.build(request.getCanvasContext());
        if (!canvasMode.isBlank()) lines.add(canvasMode);
        AgentScopeTurnState turnState = runContext.turnState();
        if (turnState != null) {
            lines.add("Reference input mode: " + turnState.referenceInputMode().name());
            switch (turnState.referenceInputMode()) {
                case ASSET_ONLY -> lines.add(
                        "Asset handling: call list_asset_groups first and use its file contentUrl values. "
                                + "Do not describe the asset group as a user-uploaded image.");
                case UPLOAD_ONLY -> lines.add(
                        "Upload handling: attached images are already supplied as native image content. "
                                + "Inspect them directly and do not call web_fetch or "
                                + "list_asset_groups or ask for asset groups.");
                case MIXED -> lines.add(
                        "Mixed handling: call list_asset_groups for asset metadata, keep uploaded image URLs, "
                                + "and use the reference-locator subagent when both sources need reconciliation. "
                                + "Choose only the files that match the user's intent.");
                default -> { }
            }
        }
        if (request.getPicUrls() != null && !request.getPicUrls().isEmpty()) {
            lines.add("Current turn attached image count: " + request.getPicUrls().size()
                    + ". The image bytes are already attached to the user message.");
            List<AgentAttachmentRequest> attachments = request.getAttachments() == null
                    ? List.of() : request.getAttachments();
            if (!attachments.isEmpty()) {
                lines.add("Current turn attachment metadata for canvas_graph_import_media:");
                for (AgentAttachmentRequest attachment : attachments) {
                    StringBuilder value = new StringBuilder("- kind=")
                            .append(attachment.kind());
                    if (attachment.mediaId() != null && !attachment.mediaId().isBlank()) {
                        value.append(" mediaId=").append(attachment.mediaId());
                    }
                    if (attachment.assetId() != null && !attachment.assetId().isBlank()) {
                        value.append(" assetId=").append(attachment.assetId());
                    }
                    if (attachment.url() != null && !attachment.url().isBlank()) {
                        value.append(" url=").append(attachment.url());
                    }
                    if (attachment.source() != null && !attachment.source().isBlank()) {
                        value.append(" source=").append(attachment.source());
                    }
                    if (attachment.name() != null && !attachment.name().isBlank()) {
                        value.append(" name=").append(attachment.name());
                    }
                    if (attachment.mimeType() != null && !attachment.mimeType().isBlank()) {
                        value.append(" mimeType=").append(attachment.mimeType());
                    }
                    lines.add(value.toString());
                }
                if (attachments.size() > 1) {
                    lines.add("When building or extending the canvas, put EVERY attachment inline as the "
                            + "`media` field of its plan node in ONE canvas_graph_apply_plan call "
                            + "(for source=library items use mediaId; never fetch the signed URL). "
                            + "Only call canvas_graph_import_media_batch when importing media WITHOUT "
                            + "building a plan.");
                } else {
                    lines.add("When building or extending the canvas, put this attachment inline as the "
                            + "`media` field of its plan node in canvas_graph_apply_plan "
                            + "(for source=library items use mediaId; never fetch the signed URL). "
                            + "Only call canvas_graph_import_media when importing media WITHOUT building a plan.");
                }
            } else {
                lines.add("Current turn attached image URLs for canvas_graph_import_media: "
                        + request.getPicUrls());
            }
        }
        if (request.getAssetIds() != null && !request.getAssetIds().isEmpty()) {
            lines.add("Current turn selected asset IDs: " + request.getAssetIds());
        }
        appendGenerationPreferenceContext(lines, request);
        if (request.getReferenceVideos() != null && !request.getReferenceVideos().isEmpty()) {
            lines.add("Current turn reference video URLs: " + request.getReferenceVideos());
        }
        appendDocumentContext(lines, request, runContext);
        appendReferenceContext(lines, request, runContext);
        if (request.getCanvasContext() != null
                && !request.getCanvasContext().normalizedMediaRefs().isEmpty()) {
            lines.add("Current canvas media refs: "
                    + request.getCanvasContext().normalizedMediaRefs().stream()
                    .map(ref -> {
                        StringBuilder value = new StringBuilder(ref.kind());
                        if (ref.url() != null && !ref.url().isBlank()) {
                            value.append(" url=").append(ref.url());
                        }
                        if (ref.assetId() != null && !ref.assetId().isBlank()) {
                            value.append(" assetId=").append(ref.assetId());
                        }
                        return value.toString();
                    })
                    .toList());
        }
        if (runContext.conversation() != null) {
            String workflow = workflows.activeContext(runContext.conversation().getId());
            if (!workflow.isBlank()) lines.add(workflow);
            String projectContext = creativeContext.promptContext(
                    runContext.conversation().getProjectId(), request.getContent());
            if (!projectContext.isBlank()) {
                lines.add("<project-creative-context>\n" + projectContext
                        + "\n</project-creative-context>");
            }
        }
        ConstraintLedger ledger = constraints.extract(request.getContent());
        lines.add("Creative mode: " + ledger.mode());
        appendConstraintLedger(lines, ledger);
        appendCapabilityContext(lines);
        if (lines.isEmpty()) return Mono.just(prompt);
        return Mono.just((prompt == null ? "" : prompt)
                + "\n\n<turn-context>\n" + String.join("\n", lines) + "\n</turn-context>");
    }

    /**
     * 把机器已判定的约束账本原样交给模型，替代提示词里「自行区分 HARD 与 SOFT」的散文化要求。
     * 无可验证硬约束且未识别到任何条目时声明 CLARIFY，由全局提示词触发一次澄清。
     */
    private static void appendConstraintLedger(List<String> lines, ConstraintLedger ledger) {
        boolean clarify = ledger.hard().isEmpty() && ledger.all().isEmpty()
                && !ledger.unresolved().isEmpty();
        String mode = clarify ? "CLARIFY" : ledger.mode().name();
        List<String> body = new ArrayList<>();
        if (!ledger.hard().isEmpty()) {
            body.add("Hard (不可修改、不可替换、不可删除):");
            ledger.hard().forEach(item -> body.add("- " + ledgerItem(item)));
        }
        if (!ledger.soft().isEmpty()) {
            body.add("Soft (可调整，但需说明理由):");
            ledger.soft().forEach(item -> body.add("- " + ledgerItem(item)));
        }
        if (!ledger.unresolved().isEmpty()) {
            body.add("Needs clarification (生成前必须与用户确认):");
            ledger.unresolved().forEach(item -> body.add("- " + item));
        }
        lines.add("<constraint-ledger mode=\"" + mode + "\">");
        if (body.isEmpty()) {
            lines.add("本轮没有识别到可验证的硬约束或软约束。");
        } else {
            lines.addAll(body);
        }
        lines.add("</constraint-ledger>");
    }

    private static String ledgerItem(ConstraintLedger.ConstraintItem item) {
        StringBuilder value = new StringBuilder(item.statement());
        if (item.value() != null && !item.value().isBlank()) {
            value.append("（取值：").append(item.value()).append("）");
        }
        if (item.verifier() != null && !item.verifier().isBlank()) {
            value.append("（校验方式：").append(item.verifier()).append("）");
        }
        return value.toString();
    }

    /**
     * 声明本轮真实可用的能力与子智能体，避免提示词静态点名在能力缺席时制造虚假承诺。
     */
    private void appendCapabilityContext(List<String> lines) {
        if (capabilities == null) return;
        String report = capabilities.capabilityReport();
        if (report == null || report.isBlank()) return;
        lines.add("<capabilities>");
        lines.add(report);
        lines.add("</capabilities>");
    }

    /**
     * 生成偏好（用户既定选择）：自定义时声明比例 / 清晰度 / 模型，忽略方案时声明媒体生成无需再次确认。
     * 自动模式不注入任何内容（由智能体自行决定），保持既有行为。
     */
    private static void appendGenerationPreferenceContext(List<String> lines,
                                                          OrchestrationRequest request) {
        AgentGenerationPreferences preferences = request.getGenerationPreferences();
        if (!preferences.hasPromptHints()) return;
        String image = preferences.describe(preferences.image());
        String video = preferences.describe(preferences.video());
        if (!image.isBlank() || !video.isBlank()) {
            lines.add("<generation-preferences>");
            lines.add("The user has fixed these media generation settings. Use them as-is for "
                    + "generate_image / generate_image_batch / generate_video and the storyboard "
                    + "image or video tools; do not ask the user to reconfirm them.");
            if (!image.isBlank()) lines.add("Image: " + image);
            if (!video.isBlank()) lines.add("Video: " + video);
            lines.add("Omit any parameter the user left on automatic and decide it yourself.");
            lines.add("</generation-preferences>");
        }
        if (preferences.ignoreGenerationPlan()) {
            lines.add("The user asked to skip generation plans for media: call the media "
                    + "generation tool right away instead of proposing a plan or options. "
                    + "This exemption covers media generation tools only; every other "
                    + "high-risk tool still needs the usual confirmation.");
        }
    }

    private void appendDocumentContext(List<String> lines, OrchestrationRequest request,
                                       AgentScopeRunContext runContext) {
        List<AgentAttachmentRequest> attachments = request.getAttachments() == null
                ? List.of() : request.getAttachments();
        List<AgentAttachmentRequest> documentAttachments = attachments.stream()
                .filter(item -> item != null && ("file".equalsIgnoreCase(item.kind())
                        || isDocumentMime(item.mimeType())))
                .toList();
        List<DocumentSummaryView> recent = runContext.conversation() == null
                ? List.of()
                : documents.recentSummaries(runContext.conversation().getId(), 5);
        if (documentAttachments.isEmpty() && recent.isEmpty()) return;

        lines.add("Document reading policy: use persisted summaries for general follow-up questions. "
                + "Call read_document when the user asks for exact values, pages, tables, quotations, "
                + "full content, or when the summary is insufficient or uncertain.");
        lines.add("文档规则：常规追问使用摘要；精确数值、指定页/表、引文、全文或摘要不足时"
                + "必须调用 read_document。");
        if (!documentAttachments.isEmpty()) {
            lines.add("Current turn document attachments:");
            for (AgentAttachmentRequest attachment : documentAttachments) {
                StringBuilder line = new StringBuilder("- ");
                if (attachment.assetId() != null && !attachment.assetId().isBlank()) {
                    line.append("assetId=").append(attachment.assetId()).append(' ');
                }
                if (attachment.name() != null && !attachment.name().isBlank()) {
                    line.append("name=").append(attachment.name()).append(' ');
                }
                if (attachment.mimeType() != null && !attachment.mimeType().isBlank()) {
                    line.append("mimeType=").append(attachment.mimeType()).append(' ');
                }
                String summary = recent.stream()
                        .filter(item -> item.assetId() != null
                                && item.assetId().equals(attachment.assetId()))
                        .map(DocumentSummaryView::summary)
                        .findFirst()
                        .orElse("");
                line.append(summary.isBlank() ? "summary=(not generated yet)" : "summary=" + summary);
                lines.add(line.toString());
            }
        }
        if (!recent.isEmpty()) {
            lines.add("Recent conversation document summaries:");
            for (DocumentSummaryView item : recent) {
                lines.add("- assetId=" + item.assetId()
                        + " name=" + (item.name() == null ? "" : item.name())
                        + " status=" + item.status()
                        + " summary=" + item.summary());
            }
        }
    }

    /**
     * 注入本轮 {@code @} 引用资料块：先声明「{@code @名称} = 引用资料标记」的约定（防全局提示词落后），
     * 再逐条给出本轮解析结果。
     *
     * <p>零回归：本轮没有引用时一行都不加。整块受 {@link #REFERENCE_BUDGET_CHARS} 约束，
     * 超出按引用顺序截断并显式标 {@code truncated=true}。
     *
     * <p>注入内容是<b>数据不是指令</b>：{@code token / name} 由用户输入或资料标题派生，
     * 一律折叠换行（防伪造块内行）并剔除 {@code <} / {@code >}（防破坏块结构 / 注入标签），
     * 字段值同样折叠成单行。
     */
    private void appendReferenceContext(List<String> lines, OrchestrationRequest request,
                                        AgentScopeRunContext runContext) {
        List<AgentReferenceRequest> references = request.getReferences();
        if (references.isEmpty()) return;
        String projectId = runContext.conversation() == null
                ? "" : runContext.conversation().getProjectId();
        List<SceneReferenceView> sceneViews = sceneReferences == null
                ? List.of() : sceneReferences.resolve(projectId, references);

        List<String> header = List.of(
                "@ 标记约定：正文里以 @ 开头的名称是引用资料标记，指向你可读范围内的资料，"
                        + "必须先读取再回答，不要要求用户重新上传；读不到（越权、已删除或无法解析）"
                        + "必须明确说明，不要编造内容。",
                "@ marks a reference material the user mentioned: the material is within your readable "
                        + "scope, so read it before answering instead of asking the user to upload it "
                        + "again; if it cannot be read, say so plainly and never invent its content.",
                "本轮引用资料解析结果：");
        StringBuilder block = new StringBuilder("<turn-references>\n")
                .append(String.join("\n", header)).append('\n');
        boolean truncated = false;
        // 预留闭合标签 "\n</turn-references>"（18 字符）；每行还要算上 '\n'
        int budget = REFERENCE_BUDGET_CHARS - block.length() - 18;
        for (AgentReferenceRequest reference : references) {
            List<String> rendered = renderReference(reference, sceneViews, runContext);
            if (rendered == null) continue;
            int cost = rendered.stream().mapToInt(item -> item.length() + 1).sum();
            if (cost > budget) {
                truncated = true;
                break;
            }
            rendered.forEach(item -> block.append(item).append('\n'));
            budget -= cost;
        }
        if (truncated) {
            block.append("truncated=true（其余引用未展开，可用工具按需读取）").append('\n');
        }
        block.append("</turn-references>");
        lines.add(block.toString());
    }

    /** 渲染一条引用；未知 kind 返回 null（不注入任何行）。 */
    private List<String> renderReference(AgentReferenceRequest reference,
                                         List<SceneReferenceView> sceneViews,
                                         AgentScopeRunContext runContext) {
        if (reference == null) return null;
        String kind = reference.kind() == null ? "" : reference.kind().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (kind) {
            case "scene" -> renderSceneReference(reference, sceneViews);
            case "local_file" -> renderLocalFile(reference, runContext);
            case "library_file" -> renderLibraryFile(reference, runContext);
            case "conversation" -> renderConversation(reference);
            default -> null;
        };
    }

    /** 分镜：解析成功时注入真实内容，失败归入 {@code kind=unresolved} 并说明原因。 */
    private static List<String> renderSceneReference(AgentReferenceRequest reference,
                                                     List<SceneReferenceView> sceneViews) {
        String token = sanitizeToken(reference.token());
        SceneReferenceView view = sceneViews.stream()
                .filter(item -> token.equals(sanitizeToken(item.token()))
                        || (reference.id() != null && reference.id().equals(item.sceneId())))
                .findFirst()
                .orElse(null);
        if (view == null || !view.resolved()) {
            String reason = view == null || view.reason() == null || view.reason().isBlank()
                    ? SCENE_UNRESOLVED_FALLBACK : sanitizeLine(view.reason());
            return List.of("- token=" + token + " kind=unresolved 说明：" + reason);
        }
        List<String> rendered = new ArrayList<>();
        rendered.add("- token=" + token
                + " kind=scene sceneId=" + sanitizeLine(view.sceneId())
                + " 分镜号=" + (view.sceneNumber() == null ? "?" : view.sceneNumber())
                + " 有图=" + yesNo(view.hasImage())
                + " 有视频=" + yesNo(view.hasVideo()));
        view.fields().forEach((label, value) ->
                rendered.add("  " + label + "：" + sanitizeLine(value)));
        return rendered;
    }

    /** 本地文件：摘要可从本轮文档摘要里复用，摘要缺失时明确标注尚未生成。 */
    private List<String> renderLocalFile(AgentReferenceRequest reference,
                                         AgentScopeRunContext runContext) {
        String assetId = reference.assetId() == null ? reference.id() : reference.assetId();
        assetId = assetId == null ? "" : assetId;
        List<String> rendered = new ArrayList<>();
        rendered.add("- token=" + sanitizeToken(reference.token())
                + " kind=local_file assetId=" + sanitizeLine(assetId)
                + (isBlank(reference.mimeType()) ? "" : " mimeType=" + sanitizeLine(reference.mimeType())));
        String summary = recentSummary(runContext, assetId);
        rendered.add("  文档摘要：" + (isBlank(summary) ? "(not generated yet)" : sanitizeLine(summary)));
        rendered.add("  → 用 read_document(assetId=" + sanitizeLine(assetId) + ") 读取");
        return rendered;
    }

    /**
     * 项目库文件：图片/视频本轮直接作参考媒体；文档类型必须**先转存成会话内 reference 资产**
     * （方案 §4.2）才有正文——`AgentDocumentService` 只认同会话且 `url` 前缀 `/api/files/files/`
     * 的资产，`/api/library/media/**` 一律判「文档不存在」。
     *
     * <p>转存以**会话所属用户**的身份调用（`ensureConversationAsset` 内部按 `owner_user_id` 过滤，
     * 这本身就是用户级防 IDOR）；本方法只做「尽力而为」：失败/未接线时降级成「正文不可用 + 元数据」，
     * 绝不因为一条引用打断整轮。
     */
    private List<String> renderLibraryFile(AgentReferenceRequest reference,
                                           AgentScopeRunContext runContext) {
        StringBuilder head = new StringBuilder("- token=").append(sanitizeToken(reference.token()))
                .append(" kind=library_file");
        if (!isBlank(reference.mediaId())) head.append(" mediaId=").append(sanitizeLine(reference.mediaId()));
        if (!isBlank(reference.assetId())) head.append(" assetId=").append(sanitizeLine(reference.assetId()));
        if (!isBlank(reference.mimeType())) head.append(" mimeType=").append(sanitizeLine(reference.mimeType()));
        if (!isBlank(reference.name())) head.append(" name=").append(sanitizeToken(reference.name()));
        // 先主动转存（首次引用）→ 失败再看本轮文档摘要里是否已有同名会话资产（此前轮次已转存）
        String convertedAssetId = transferLibraryAsset(reference, runContext);
        if (isBlank(convertedAssetId)) {
            convertedAssetId = recentLibraryAssetId(runContext, reference);
        }
        List<String> rendered = new ArrayList<>();
        rendered.add(head.toString());
        if (isBlank(convertedAssetId)) {
            rendered.add("  项目库素材正文不可用（转存未成功）：图片/视频仍作为本轮参考媒体参与理解；"
                    + "不要读取其签名 URL，需要正文时如实告知用户该资料暂时读不到");
        } else {
            rendered.add("  项目库素材已转存为会话资产 assetId=" + sanitizeLine(convertedAssetId)
                    + "（图片/视频同时作为本轮参考媒体）→ 需要正文/精确数值时用 read_document(assetId="
                    + sanitizeLine(convertedAssetId) + ") 读取");
        }
        return rendered;
    }

    /** 主动转存项目库文件；未接线、缺少会话上下文或转存失败时返回空串。 */
    private String transferLibraryAsset(AgentReferenceRequest reference, AgentScopeRunContext runContext) {
        if (libraryAssets == null || runContext == null || runContext.conversation() == null) return "";
        try {
            AgentAttachmentRequest attachment = libraryAssets.ensureConversationAsset(
                    runContext.conversation().getId(),
                    runContext.conversation().getUserId(),
                    runContext.conversation().getProjectId(),
                    reference);
            return attachment == null ? "" : attachment.assetId();
        } catch (RuntimeException error) {
            // 转存服务自身已吞异常；这里再兜一层，保证「引用出错不至于发不出消息」
            log.warn("[Reference] 项目库文件转存异常 token={}: {}",
                    sanitizeToken(reference.token()), error.toString());
            return "";
        }
    }

    /** 会话引用：摘要能力尚未启用（P2），必须明确告知而不是编造会话内容。 */
    private static List<String> renderConversation(AgentReferenceRequest reference) {
        String title = isBlank(reference.name()) ? reference.token() : reference.name();
        return List.of(
                "- token=" + sanitizeToken(reference.token())
                        + " kind=conversation conversationId=" + sanitizeLine(reference.id())
                        + " title=" + sanitizeToken(title),
                "  其它会话的摘要能力尚未启用；如用户需要该会话内容，请明确告知当前无法读取，不要编造");
    }

    /** 本轮引用资料是用户输入派生的不可信文本：折叠换行/制表、剔除尖括号、压缩空白并限长。 */
    static String sanitizeToken(String value) {
        String text = sanitizeLine(value);
        return text.length() <= REFERENCE_TOKEN_MAX_CHARS
                ? text : text.substring(0, REFERENCE_TOKEN_MAX_CHARS);
    }

    /** 字段值一律折叠成单行，避免伪造块内新行或注入标签。 */
    static String sanitizeLine(String value) {
        if (value == null) return "";
        String text = value
                .replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                .replace("<", "").replace(">", "");
        return text.replaceAll(" {2,}", " ").strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    /** 复用本轮文档摘要；本地文件摘要尚未生成时返回空串（注入处标注 not generated yet）。 */
    private String recentSummary(AgentScopeRunContext runContext, String assetId) {
        if (runContext == null || runContext.conversation() == null || isBlank(assetId)) return "";
        return recentSummaries(runContext).stream()
                .filter(item -> assetId.equals(item.assetId()))
                .map(DocumentSummaryView::summary)
                .filter(summary -> summary != null && !summary.isBlank())
                .findFirst()
                .orElse("");
    }

    /**
     * 项目库文档是否已经转存成会话资产：按名字（引用名或 token 去掉 {@code @}）在本轮文档摘要里找。
     * 找不到返回空串（此时不承诺可读，避免提示词承诺一个不存在的 assetId）。
     */
    private String recentLibraryAssetId(AgentScopeRunContext runContext,
                                        AgentReferenceRequest reference) {
        String name = isBlank(reference.name())
                ? sanitizeToken(reference.token()).replaceFirst("^@", "") : reference.name().strip();
        if (runContext == null || runContext.conversation() == null || isBlank(name)) return "";
        return recentSummaries(runContext).stream()
                .filter(item -> item.name() != null && name.equals(item.name().strip()))
                .map(DocumentSummaryView::assetId)
                .filter(assetId -> assetId != null && !assetId.isBlank())
                .findFirst()
                .orElse("");
    }

    private List<DocumentSummaryView> recentSummaries(AgentScopeRunContext runContext) {
        if (runContext == null || runContext.conversation() == null) return List.of();
        return documents.recentSummaries(runContext.conversation().getId(), 5);
    }

    private static boolean isDocumentMime(String mimeType) {
        if (mimeType == null) return false;
        String value = mimeType.toLowerCase(java.util.Locale.ROOT);
        return value.contains("word") || value.contains("excel") || value.contains("spreadsheet")
                || value.contains("powerpoint") || value.contains("presentation")
                || value.contains("pdf") || value.contains("markdown")
                || value.equals("application/msword")
                || value.equals("application/vnd.ms-excel")
                || value.equals("application/vnd.ms-powerpoint");
    }
}
