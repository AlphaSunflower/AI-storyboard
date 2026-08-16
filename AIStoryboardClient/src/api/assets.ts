import client from './client';
import type { ApiResponse } from './auth';

export type AssetType = 'character' | 'prop' | 'scene';

export interface AssetImage {
  id: string;
  url: string;
  sortOrder: number;
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

  /** 覆盖式设置分镜关联的资产。 */
  setSceneAssets: (sceneId: string, assetIds: string[]) =>
    client.put<ApiResponse<void>>(`/scenes/${sceneId}/assets`, { assetIds }),

  listSceneAssets: (sceneId: string) =>
    client.get<ApiResponse<Asset[]>>(`/scenes/${sceneId}/assets`),
};
