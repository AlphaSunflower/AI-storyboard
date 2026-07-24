import client from './client';
import type { ApiResponse } from './auth';

export interface ProjectResponse {
  id: string;
  name: string;
  description: string;
  creationType: string;
  aspectRatio: string;
  scriptText: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  scenes: SceneResponse[];
}

export interface SceneResponse {
  id: string;
  projectId: string;
  sceneNumber: number;
  scriptContent: string;
  imagePrompt: string;
  videoPrompt: string;
  negativePrompt: string;
  cameraMovement: string;
  shotType: string;
  soundDesign: string;
  imageUrl: string;
  videoUrl: string;
  imageStatus: string;
  videoStatus: string;
}

export const projectApi = {
  list: () => client.get<ApiResponse<ProjectResponse[]>>('/projects'),
  create: (data: { name?: string; creationType?: string; aspectRatio?: string }) =>
    client.post<ApiResponse<ProjectResponse>>('/projects', data),
  get: (id: string) => client.get<ApiResponse<ProjectResponse>>(`/projects/${id}`),
  update: (id: string, data: Record<string, unknown>) =>
    client.put<ApiResponse<ProjectResponse>>(`/projects/${id}`, data),
  delete: (id: string) => client.delete(`/projects/${id}`),
  getDraft: () => client.get<ApiResponse<ProjectResponse | null>>('/projects/draft'),
};
