import type { AgentAsset, AgentAttachment, AgentPage } from '../../api/agent';
import type { AgentAssetDragKind } from '../../lib/agentAssetDrag';
import { documentMimeType } from '../../lib/documentPreview';
import { parseResultUrls } from '../../stores/generationRecordsStore';

export type AssetsTab = 'all' | 'image' | 'video' | 'document' | 'storyboard' | 'other';

/**
 * 网格里点开一张媒体时交给宿主的上下文。
 *
 * 和扁平版 `AssetDetailTarget` 的区别：这里一定带完整记录（网格本来就有），
 * 另外多带一份拖拽用的种类判定，右键菜单和详情共用同一套「这是图还是视频」的判断。
 */
export interface AgentAssetDetailTarget {
  asset: AgentAsset;
  url: string;
  index: number;
  kind: AgentAssetDragKind;
}

/** 会话资产的类型分桶；other 兜住 image/video/document 之外的资产（上传参考图等）。 */
export interface AgentAssetBuckets {
  all: AgentAsset[];
  image: AgentAsset[];
  video: AgentAsset[];
  document: AgentAsset[];
  other: AgentAsset[];
}

const MEDIA_TYPES = new Set<AgentAsset['type']>(['image', 'video', 'document']);

export function splitAgentAssets(assets: AgentPage<AgentAsset> | null): AgentAssetBuckets {
  const records = assets?.records ?? [];
  return {
    all: records,
    image: records.filter((asset) => asset.type === 'image'),
    video: records.filter((asset) => asset.type === 'video'),
    document: records.filter((asset) => asset.type === 'document'),
    other: records.filter((asset) => !MEDIA_TYPES.has(asset.type)),
  };
}

export function assetUrls(asset: AgentAsset): string[] {
  const parsed = parseResultUrls(asset.resultUrls ?? null);
  return parsed.length ? parsed : (asset.url ? [asset.url] : []);
}

export function fileNameFromAssetUrl(value: string): string {
  let decoded = value;
  try {
    decoded = decodeURIComponent(value);
  } catch {
    // 保留含非法转义字符的原始 URL。
  }
  const path = decoded.split(/[?#]/, 1)[0];
  return path.split(/[\\/]/).pop()?.trim() || '文档';
}

export function documentAttachmentForAsset(asset: AgentAsset, url: string): AgentAttachment {
  const name = fileNameFromAssetUrl(url);
  return {
    url,
    name,
    mimeType: documentMimeType({ name, url, mimeType: asset.mimeType ?? undefined }),
    kind: 'file',
    assetId: asset.id,
  };
}

export function getAgentAssetCounts(assets: AgentPage<AgentAsset> | null) {
  const buckets = splitAgentAssets(assets);
  const countUrls = (list: AgentAsset[]) =>
    list.reduce((total, asset) => total + assetUrls(asset).length, 0);

  const image = assets?.imageTotal ?? countUrls(buckets.image);
  const video = assets?.videoTotal ?? countUrls(buckets.video);
  const document = assets?.documentTotal ?? countUrls(buckets.document);
  const other = assets?.otherTotal ?? countUrls(buckets.other);

  // 「全部」按各分类求和，而不是分页 total：total 按记录行算，分类计数按 URL 展开算，
  // 两者单位不同，混用会让筛选栏的角标对不上。
  return { all: image + video + document + other, image, video, document, other };
}
