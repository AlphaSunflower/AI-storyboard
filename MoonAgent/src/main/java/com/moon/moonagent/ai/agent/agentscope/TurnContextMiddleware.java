package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.document.AgentDocumentService;
import com.moon.moonagent.ai.agent.document.DocumentSummaryView;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.dto.request.AgentAttachmentRequest;
import com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowService;
import com.moon.moonagent.ai.agent.creative.ConstraintExtractionService;
import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.CreativeMode;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
final class TurnContextMiddleware implements MiddlewareBase {

    private final AgentWorkflowService workflows;
    private final ProjectCreativeContextService creativeContext;
    private final ConstraintExtractionService constraints;
    private final AgentDocumentService documents;

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        if (context == null) return Mono.just(prompt);
        AgentScopeRunContext runContext = context.get(AgentScopeRunContext.class);
        if (runContext == null || runContext.request() == null) return Mono.just(prompt);
        OrchestrationRequest request = runContext.request();
        List<String> lines = new ArrayList<>();
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
                    lines.add("Import every attachment in ONE canvas_graph_import_media_batch call. "
                            + "For source=library items, use mediaId; do not fetch the signed URL.");
                } else {
                    lines.add("For a source=library attachment, call canvas_graph_import_media with media "
                            + "containing kind/mediaId/name/mimeType/label. Do not fetch the signed URL.");
                }
            } else {
                lines.add("Current turn attached image URLs for canvas_graph_import_media: "
                        + request.getPicUrls());
            }
        }
        if (request.getAssetIds() != null && !request.getAssetIds().isEmpty()) {
            lines.add("Current turn selected asset IDs: " + request.getAssetIds());
        }
        if (request.getReferenceVideos() != null && !request.getReferenceVideos().isEmpty()) {
            lines.add("Current turn reference video URLs: " + request.getReferenceVideos());
        }
        appendDocumentContext(lines, request, runContext);
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
        if (ledger.mode() == CreativeMode.STRICT) {
            lines.add("Strict mode: preserve all HARD constraints. Do not silently weaken, drop "
                    + "or reinterpret them. If a hard constraint conflicts or is unverifiable, "
                    + "ask the user before generating.");
        }
        if (lines.isEmpty()) return Mono.just(prompt);
        return Mono.just((prompt == null ? "" : prompt)
                + "\n\n<turn-context>\n" + String.join("\n", lines) + "\n</turn-context>");
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
