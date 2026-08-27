import { create } from 'zustand';
import { hydrateAsset } from '../services/projectStorage';
import type { AssetMetadata, MediaAsset, MediaQuality, PersistedAsset } from '../types';

interface MediaState {
  assets: Record<string, MediaAsset>;
  metadata: Record<string, AssetMetadata>;
  quality: MediaQuality;
  upsertAsset: (asset: MediaAsset) => void;
  upsertAssets: (assets: MediaAsset[]) => void;
  hydrateAssets: (assets: PersistedAsset[]) => void;
  hydrateMetadata: (assets: AssetMetadata[]) => void;
  setQuality: (quality: MediaQuality) => void;
  clearAssets: () => void;
}

const FULL_ASSET_CACHE_LIMIT = 48;
const assetRecency = new Map<string, number>();
let recencyClock = 0;

const withAssetCacheLimit = (current: Record<string, MediaAsset>, incoming: MediaAsset[]) => {
  const next = { ...current };
  incoming.forEach((asset) => { next[asset.id] = asset; assetRecency.set(asset.id, ++recencyClock); });
  const overflow = Object.keys(next).length - FULL_ASSET_CACHE_LIMIT;
  if (overflow > 0) [...assetRecency.entries()].sort((left, right) => left[1] - right[1]).slice(0, overflow).forEach(([id]) => {
    delete next[id];
    assetRecency.delete(id);
  });
  return next;
};

export const useMediaStore = create<MediaState>((set, get) => ({
  assets: {}, metadata: {}, quality: 'thumbnail',
  upsertAsset: (asset) => set((state) => {
    return { assets: withAssetCacheLimit(state.assets, [asset]), metadata: { ...state.metadata, [asset.id]: asset } };
  }),
  upsertAssets: (assets) => {
    if (!assets.length) return;
    set((state) => {
      const next = withAssetCacheLimit(state.assets, assets);
      const metadata = { ...state.metadata };
      assets.forEach((asset) => { metadata[asset.id] = asset; });
      return { assets: next, metadata };
    });
  },
  hydrateAssets: (records) => {
    set({ assets: Object.fromEntries(records.map((record) => { const asset = hydrateAsset(record); return [asset.id, asset]; })), metadata: Object.fromEntries(records.map((record) => [record.id, record])) });
  },
  hydrateMetadata: (records) => set({ metadata: Object.fromEntries(records.map((record) => [record.id, record])) }),
  setQuality: (quality) => { if (get().quality !== quality) set({ quality }); },
  clearAssets: () => { assetRecency.clear(); set({ assets: {}, metadata: {} }); },
}));
