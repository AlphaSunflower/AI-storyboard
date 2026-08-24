import { create } from 'zustand';
import { assetApi, type Asset, type AssetType } from '../api/assets';
import { useProjectStore } from './projectStore';

interface AssetState {
  assets: Asset[];
  loading: boolean;
  loadAssets: () => Promise<void>;
  createAsset: (data: { type: AssetType; name: string; description?: string }) => Promise<string | null>;
  updateAsset: (id: string, data: { name?: string; description?: string }) => Promise<void>;
  deleteAsset: (id: string) => Promise<void>;
  uploadImage: (assetId: string, file: { uri: string; name: string; type: string }) => Promise<void>;
  deleteImage: (assetId: string, imageId: string) => Promise<void>;
}

export const useAssetStore = create<AssetState>((set, get) => ({
  assets: [],
  loading: false,

  loadAssets: async () => {
    const projectId = useProjectStore.getState().currentProject?.id;
    if (!projectId) { set({ assets: [] }); return; }
    set({ loading: true });
    try {
      const res = await assetApi.list(projectId);
      set({ assets: res.data.data ?? [] });
    } catch { /* 静默 */ }
    set({ loading: false });
  },

  createAsset: async (data) => {
    const projectId = useProjectStore.getState().currentProject?.id;
    try {
      const res = await assetApi.create({ ...data, projectId: projectId ?? null });
      await get().loadAssets();
      return res.data.data?.id ?? null;
    } catch { return null; }
  },

  updateAsset: async (id, data) => {
    try {
      await assetApi.update(id, data);
      await get().loadAssets();
    } catch { /* 静默 */ }
  },

  deleteAsset: async (id) => {
    try {
      await assetApi.delete(id);
      await get().loadAssets();
    } catch { /* 静默 */ }
  },

  uploadImage: async (assetId, file) => {
    try {
      await assetApi.uploadImage(assetId, file);
      await get().loadAssets();
    } catch { /* 静默 */ }
  },

  deleteImage: async (assetId, imageId) => {
    try {
      await assetApi.deleteImage(assetId, imageId);
      await get().loadAssets();
    } catch { /* 静默 */ }
  },
}));
