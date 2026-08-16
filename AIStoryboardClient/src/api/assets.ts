import client from './client';
import type { ApiResponse } from './auth';

export type AssetType = 'character' | 'prop' | 'scene';

export interface AssetImage {
  id: string;
  url: string;
  sortOrder: number;
  /** 上传原始文件名（DepthCarousel 展示当前图片名用） */
  fileName: string;
}

/** AI 资产库资产：人物/道具/场景。projectId 为 null = 用户全局资产库。 */
export interface Asset {
  id: string;
  type: AssetType;
  name: string;
  description: string;
  projectId: string | null;
  images: AssetImage[];
  createdAt: string;
  updatedAt: string;
}

/** 分镜关联资产（图片/视频用途分开）。 */
export interface SceneAssets {
  imageAssets: Asset[];
  videoAssets: Asset[];
}

export const assetApi = {
  /** 列表：项目资产 + 用户全局资产；type 可选过滤。 */
  list: (projectId?: string, type?: string) =>
    client.get<ApiResponse<Asset[]>>('/assets', { params: { projectId, type } }),

  create: (data: { type: AssetType; name: string; description?: string; projectId?: string | null }) =>
    client.post<ApiResponse<Asset>>('/assets', data),

  update: (id: string, data: { name?: string; description?: string }) =>
    client.put<ApiResponse<Asset>>(`/assets/${id}`, data),

  delete: (id: string) => client.delete<ApiResponse<void>>(`/assets/${id}`),

  uploadImage: (assetId: string, file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return client.post<ApiResponse<AssetImage>>(`/assets/${assetId}/images`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },

  deleteImage: (assetId: string, imageId: string) =>
    client.delete<ApiResponse<void>>(`/assets/${assetId}/images/${imageId}`),

  /** 覆盖式设置分镜关联的资产（图片/视频用途分开）。 */
  setSceneAssets: (sceneId: string, imageAssetIds: string[], videoAssetIds: string[]) =>
    client.put<ApiResponse<void>>(`/scenes/${sceneId}/assets`, { imageAssetIds, videoAssetIds }),

  listSceneAssets: (sceneId: string) =>
    client.get<ApiResponse<SceneAssets>>(`/scenes/${sceneId}/assets`),
};
