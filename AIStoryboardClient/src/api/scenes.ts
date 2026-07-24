import client from './client';
import type { ApiResponse, SceneResponse } from './projects';

export const sceneApi = {
  add: (projectId: string, data: Record<string, unknown>) =>
    client.post<ApiResponse<SceneResponse>>(`/projects/${projectId}/scenes`, data),
  update: (id: string, data: Record<string, unknown>) =>
    client.put<ApiResponse<SceneResponse>>(`/scenes/${id}`, data),
  delete: (id: string) => client.delete(`/scenes/${id}`),
};
