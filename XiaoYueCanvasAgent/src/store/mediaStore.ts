import { create } from 'zustand';
import { hydrateAsset } from '../services/projectStorage';
import type { AssetMetadata, MediaAsset, MediaQuality, PersistedAsset } from '../types';

interface MediaState {
  assets: Record<string, MediaAsset>;
  metadata: Record<string, AssetMetadata>;
  assetErrors: Record<string, string>;
  quality: MediaQuality;
  upsertAsset: (asset: MediaAsset) => void;
  upsertAssets: (assets: MediaAsset[]) => void;
  hydrateAssets: (assets: PersistedAsset[]) => void;
  hydrateMetadata: (assets: AssetMetadata[]) => void;
  upsertMetadata: (asset: AssetMetadata) => void;
  markAssetError: (assetId: string, message: string) => void;
  clearAssetError: (assetId: string) => void;
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
  assets: {}, metadata: {}, assetErrors: {}, quality: 'thumbnail',
  upsertAsset: (asset) => set((state) => {
    const assetErrors = { ...state.assetErrors };
    delete assetErrors[asset.id];
    return { assets: withAssetCacheLimit(state.assets, [asset]), metadata: { ...state.metadata, [asset.id]: asset }, assetErrors };
  }),
  upsertAssets: (assets) => {
    if (!assets.length) return;
    set((state) => {
      const next = withAssetCacheLimit(state.assets, assets);
      const metadata = { ...state.metadata };
      const assetErrors = { ...state.assetErrors };
      assets.forEach((asset) => { metadata[asset.id] = asset; delete assetErrors[asset.id]; });
      return { assets: next, metadata, assetErrors };
    });
  },
  hydrateAssets: (records) => {
    set({ assets: Object.fromEntries(records.map((record) => { const asset = hydrateAsset(record); return [asset.id, asset]; })), metadata: Object.fromEntries(records.map((record) => [record.id, record])), assetErrors: {} });
  },
  hydrateMetadata: (records) => set({ metadata: Object.fromEntries(records.map((record) => [record.id, record])), assetErrors: {} }),
  upsertMetadata: (asset) => set((state) => ({ metadata: { ...state.metadata, [asset.id]: asset } })),
  markAssetError: (assetId, message) => set((state) => ({ assetErrors: { ...state.assetErrors, [assetId]: message } })),
  clearAssetError: (assetId) => set((state) => {
    const assetErrors = { ...state.assetErrors };
    delete assetErrors[assetId];
    return { assetErrors };
  }),
  setQuality: (quality) => { if (get().quality !== quality) set({ quality }); },
  clearAssets: () => { assetRecency.clear(); set({ assets: {}, metadata: {}, assetErrors: {} }); },
}));
