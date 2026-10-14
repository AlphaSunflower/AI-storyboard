import type { AgentAttachment } from '../api/agent';
/**
 * 附件（产物卡）的幂等化。
 *
 * 服务端按 `assetId` 去重，但客户端此前用 `url-index` 当 React key，且忽略 `assetId`，
 * 于是重放 / 刷新时同一资产会重复渲染、顺序也可能变化。
 * 这里统一：以 `assetId`（缺失时退化到 `url`）为去重键与 React key，保持首次出现顺序。
 */

/** 稳定去重键：优先 assetId，其次 mediaId，最后 url。 */
export function attachmentDedupeKey(attachment: AgentAttachment): string {
  const assetId = attachment.assetId?.trim();
  if (assetId) return `asset:${assetId}`;
  const mediaId = attachment.mediaId?.trim();
  if (mediaId) return `media:${mediaId}`;
  return `url:${attachment.url ?? ''}`;
}

/** 稳定 React key：assetId 优先，无 assetId 时退化到 url。 */
export function attachmentKey(attachment: AgentAttachment, index: number): string {
  const assetId = attachment.assetId?.trim();
  if (assetId) return `asset-${assetId}`;
  return `url-${attachment.url ?? index}`;
}

/** 按 assetId（退化 url）去重，保持首次出现顺序；重放不会制造重复卡片。 */
export function dedupeAttachments(attachments: AgentAttachment[]): AgentAttachment[] {
  const seen = new Set<string>();
  const next: AgentAttachment[] = [];
  for (const attachment of attachments) {
    const key = attachmentDedupeKey(attachment);
    if (seen.has(key)) continue;
    seen.add(key);
    next.push(attachment);
  }
  return next;
}
