import client from './client';
import type { ApiResponse } from './auth';
import type { SceneReferenceAsset, SceneResponse } from './projects';

export const sceneApi = {
  add: (projectId: string, data: Record<string, unknown>) =>
    client.post<ApiResponse<SceneResponse>>(`/projects/${projectId}/scenes`, data),
  update: (id: string, data: Record<string, unknown>) =>
    client.put<ApiResponse<SceneResponse>>(`/scenes/${id}`, data),
  delete: (id: string) => client.delete(`/scenes/${id}`),
  // 参考素材（图/视频/音频）
  listReferences: (sceneId: string) =>
    client.get<ApiResponse<SceneReferenceAsset[]>>(`/scenes/${sceneId}/references`),
  uploadReference: (sceneId: string, type: string, file: File) => {
    const fd = new FormData();
    fd.append('type', type);
    fd.append('file', file);
    // multipart：显式声明 Content-Type，避免 client 全局 application/json 覆盖导致后端收不到 type（同 agent uploadImage）
    return client.post<ApiResponse<SceneReferenceAsset>>(`/scenes/${sceneId}/references`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  deleteReference: (id: string) => client.delete(`/scenes/references/${id}`),
};
