import type { AgentAsset, AgentPage } from '../../api/agent';
import { parseResultUrls } from '../../stores/generationRecordsStore';

export type AssetsTab = 'image' | 'video' | 'storyboard';

export function assetUrls(asset: AgentAsset): string[] {
  const parsed = parseResultUrls(asset.resultUrls ?? null);
  return parsed.length ? parsed : (asset.url ? [asset.url] : []);
}

export function getAgentAssetCounts(assets: AgentPage<AgentAsset> | null) {
  const records = assets?.records ?? [];
  const imageRecords = records.filter((asset) => asset.type === 'image');
  const videoRecords = records.filter((asset) => asset.type === 'video');
  return {
    image: assets?.imageTotal ?? imageRecords.reduce((total, asset) => total + assetUrls(asset).length, 0),
    video: assets?.videoTotal ?? videoRecords.reduce((total, asset) => total + assetUrls(asset).length, 0),
  };
}
