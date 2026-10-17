package com.moon.moonagent.service;

import com.moon.moonagent.ai.agent.CanvasMediaRef;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.dto.request.AgentAttachmentRequest;
import com.moon.moonagent.dto.request.AgentReferenceRequest;
import com.moon.moonagent.dto.request.AgentSendMessageRequest;
import com.moon.moonagent.security.OutboundUrlGuard;
import com.storyboard.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 归一化一轮消息里用户可控的媒体地址，并拒绝不在可信范围内的地址。
 *
 * <p>浏览器给出的绝对地址（前端开发端口、网关端口、站点域名）其 origin 与服务端
 * 内部地址天然不同，因此不能直接用 origin 白名单判定；这里把「可信媒体路径但外部
 * origin」的地址归一化成相对路径，让下游（生图/改图/画布导入）始终经内部服务取文件，
 * 既不放过非法地址，也不修改公开请求契约。
 */
@Component
@Slf4j
public class TurnMediaCanonicalizer {

    private final List<String> allowedOrigins;

    public TurnMediaCanonicalizer(
            @Value("${storyboard.internal-url:}") String storyboardInternalUrl,
            @Value("${storyboard.asset-client.base-url:}") String assetServiceBaseUrl,
            @Value("${storyboard.storage.endpoint:}") String storageEndpoint,
            @Value("${storyboard.media.allowed-origins:}") String extraAllowedOrigins) {
        this.allowedOrigins = Stream.of(
                        storyboardInternalUrl, assetServiceBaseUrl, storageEndpoint, extraAllowedOrigins)
                .filter(Objects::nonNull)
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /** 重建请求，仅改写媒体地址字段；其余字段与元数据原样透传。 */
    public AgentSendMessageRequest canonicalize(AgentSendMessageRequest request) {
        if (request == null) return null;
        return new AgentSendMessageRequest(
                request.content(),
                request.clientRequestId(),
                canonicalizeUrl(request.picUrl()),
                canonicalizeUrls(request.picUrls()),
                canonicalizeUrls(request.referenceVideos()),
                canonicalizeUrls(request.referenceAudios()),
                canonicalizeAttachments(request.attachments()),
                canonicalizeReferences(request.references()),
                request.assetIds(),
                request.assetGroupIds(),
                request.assetGroupSnapshotIds(),
                request.routingHint(),
                request.cancelActiveTasks(),
                request.editMessageId(),
                canonicalizeCanvasContext(request.canvasContext()),
                request.generationPreferences());
    }

    /** HITL 续流带回的画布上下文同样要归一化。 */
    public CanvasSessionContext canonicalizeCanvasContext(CanvasSessionContext context) {
        if (context == null) return null;
        List<CanvasMediaRef> refs = context.normalizedMediaRefs().stream()
                .map(ref -> new CanvasMediaRef(
                        ref.kind(),
                        ref.assetId(),
                        canonicalizeUrl(ref.url()),
                        ref.name(),
                        ref.mimeType(),
                        ref.source()))
                .toList();
        List<String> excluded = context.normalizedExcludedMediaUrls().stream()
                .map(this::canonicalizeUrl)
                .toList();
        return new CanvasSessionContext(
                context.mode(), context.projectId(), context.canvasId(), refs, excluded);
    }

    private List<AgentAttachmentRequest> canonicalizeAttachments(List<AgentAttachmentRequest> attachments) {
        if (attachments == null) return null;
        return attachments.stream()
                .map(attachment -> attachment == null ? null : new AgentAttachmentRequest(
                        canonicalizeUrl(attachment.url()),
                        attachment.name(),
                        attachment.mimeType(),
                        attachment.sizeBytes(),
                        attachment.kind(),
                        attachment.source(),
                        attachment.assetId(),
                        attachment.mediaId()))
                .toList();
    }

    private List<String> canonicalizeUrls(List<String> urls) {
        if (urls == null) return null;
        return urls.stream().map(this::canonicalizeUrl).toList();
    }

    /**
     * 归一化本轮引用资料的地址。
     *
     * <p>引用只是附加资料，语义上「读不到这条也没关系」，因此这里<b>不</b>沿用
     * {@link #canonicalizeUrl} 的抛错策略：只有会话内本地文件（{@code kind=local_file}，
     * 形如 {@code /api/files/**} 的媒体地址）需要归一化；其它类别（项目库 media、分镜、
     * 会话）的 url 原样保留。local_file 的地址非法时只把该条 url 置空（{@code assetId}
     * 仍能定位文档），绝不因一条引用地址把整轮消息拒成 40001。
     */
    private List<AgentReferenceRequest> canonicalizeReferences(List<AgentReferenceRequest> references) {
        if (references == null) return null;
        return references.stream()
                .map(reference -> reference == null ? null : new AgentReferenceRequest(
                        reference.kind(),
                        reference.id(),
                        reference.token(),
                        reference.name(),
                        reference.assetId(),
                        reference.mediaId(),
                        canonicalizeReferenceUrl(reference),
                        reference.mimeType(),
                        reference.sizeBytes(),
                        reference.sceneNumber()))
                .toList();
    }

    /** 只处理 local_file（会话内文件）的媒体地址；非法/不可信时置空而不抛错。 */
    private String canonicalizeReferenceUrl(AgentReferenceRequest reference) {
        String url = reference.url();
        if (!"local_file".equals(reference.kind()) || url == null || url.isBlank()) return url;
        try {
            return OutboundUrlGuard.canonicalizeMediaUrl(url, allowedOrigins);
        } catch (BusinessException error) {
            log.warn("引用资料地址不在允许范围内，已置空 url: kind={}, id={}, url={}, reason={}",
                    reference.kind(), reference.id(), url, error.getMessage());
            return null;
        }
    }

    /** 空值保持空值（前端允许省略），非空值必须过可信校验。 */
    private String canonicalizeUrl(String url) {
        if (url == null || url.isBlank()) return url;
        return OutboundUrlGuard.canonicalizeMediaUrl(url, allowedOrigins);
    }
}
