package com.moon.moonagent.ai.agent.document;

import com.moon.moonagent.dto.request.AgentAttachmentRequest;
import com.moon.moonagent.dto.request.AgentReferenceRequest;

/**
 * 项目库文件 → 会话内 reference 资产的转存服务。
 *
 * <p>背景：{@code @某个项目库文档} 这类引用由前端以
 * {@code kind=library_file}（携带 {@code assetId}/{@code mediaId}，{@code url} 形如
 * {@code /api/library/media/**}）下发。既有文档读取链路（
 * {@link AgentDocumentService}）只认会话内
 * {@code agent_assets.type=reference} 且 {@code url} 前缀为 {@code /api/files/files/} 的资产，
 * 因此项目库文档在修复前「模型读不到正文」。
 *
 * <p>本服务承接「转存」这一步：先向资产服务确认该素材确实属于
 * {@code projectId}（防 IDOR），再经内部对象存储取字节、复用既有
 * {@code FileStorageService} 落到 MinIO {@code files/}、写入一条新的
 * {@code agent_assets}（{@code type=reference}、{@code kind=file}），
 * 返回一条可在会话内被 {@code read_document} 读到的附件形态。
 * 此后完全复用既有文档链路（摘要 + {@code read_document}），
 * 不改动 {@link AgentDocumentService} 的任何读取逻辑。
 *
 * <p>失败语义：任何失败（归属校验不通过、下载失败、超大文件、MIME 不支持、
 * 落库异常）都只记录可诊断日志并返回 {@code null}，<b>不向调用方抛异常</b>，
 * 由调用方把该引用降级渲染成「正文不可用」。
 */
public interface LibraryReferenceAssetService {

    /**
     * 把一条 {@code kind=library_file} 的引用转存成会话内可读的 reference 资产。
     *
     * <p>幂等：同一会话 + 同一 {@code mediaId} 重复引用不会重复创建资产，
     * 第二次调用直接复用既有资产行。
     *
     * <p><b>{@code userId} 必须传真实操作人</b>（会话所属用户），不能传 {@code null}：
     * asset-client 只在 {@code actingUserId} 非空时才带 {@code X-User-Id}，而 asset-service
     * 在缺该头时会把调用者鉴权成 {@code "internal"}，随后
     * {@code GET /api/library/assets/{id}} 按 {@code owner_user_id = 'internal'} 查库必然查不到，
     * 结果是每次引用都静默降级成「正文不可用」。传入本人才使这一步真正完成用户级归属校验。
     *
     * @param conversationId 目标会话 ID（必须非空；资产按此维度归属与复用）
     * @param userId         会话所属用户 ID，用于以本人身份向 asset-service 读取该资产
     *                       （asset-service 侧按 {@code owner_user_id} 过滤，实现防 IDOR）
     * @param projectId      会话所属项目 ID，用于校验该素材确实属于该项目（或为本人全局素材）
     * @param reference      前端下发的引用；{@code kind} 不是 {@code library_file}、或
     *                       缺少 {@code mediaId} 时直接返回 {@code null}
     * @return 会话内附件形态（{@code assetId}=新建或复用的 {@code agent_assets.id}，
     *         {@code url} 形如 {@code /api/files/files/xxx}，{@code kind=file}）；
     *         转存失败时返回 {@code null}
     */
    AgentAttachmentRequest ensureConversationAsset(String conversationId,
                                                   String userId,
                                                   String projectId,
                                                   AgentReferenceRequest reference);
}
