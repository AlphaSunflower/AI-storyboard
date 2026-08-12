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
    return client.post<ApiResponse<SceneReferenceAsset>>(`/scenes/${sceneId}/references`, fd);
  },
  deleteReference: (id: string) => client.delete(`/scenes/references/${id}`),
};
