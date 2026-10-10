import type { AgentAsset, AgentAttachment, AgentPage } from '../../api/agent';
import { documentMimeType } from '../../lib/documentPreview';
import { parseResultUrls } from '../../stores/generationRecordsStore';

export type AssetsTab = 'image' | 'video' | 'document' | 'storyboard';

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
    mimeType: documentMimeType({ name, url }),
    kind: 'file',
    assetId: asset.id,
  };
}

export function getAgentAssetCounts(assets: AgentPage<AgentAsset> | null) {
  const records = assets?.records ?? [];
  const imageRecords = records.filter((asset) => asset.type === 'image');
  const videoRecords = records.filter((asset) => asset.type === 'video');
  const documentRecords = records.filter((asset) => asset.type === 'document');
  return {
    image: assets?.imageTotal ?? imageRecords.reduce((total, asset) => total + assetUrls(asset).length, 0),
    video: assets?.videoTotal ?? videoRecords.reduce((total, asset) => total + assetUrls(asset).length, 0),
    document: assets?.documentTotal
      ?? documentRecords.reduce((total, asset) => total + assetUrls(asset).length, 0),
  };
}
