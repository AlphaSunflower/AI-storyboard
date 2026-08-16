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

export interface SceneReferenceAsset {
  id: string;
  type: 'image' | 'video' | 'audio';
  purpose: 'image' | 'video';
  url: string;
  fileName: string;
  fileSize: number;
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
  videoTaskId: string;
  /** 多图结果：逗号分隔 URL（imageUrl 为首图） */
  imageUrls: string;
  /** 分镜生成参数覆盖（null/undefined = 跟随全局默认） */
  imageModel: string;
  imageSize: string;
  imageQuality: string;
  imageN: number;
  videoModel: string;
  videoAspectRatio: string;
  /** 视频覆盖参数（复用既有列） */
  videoResolution: string;
  duration: number;
}

export const projectApi = {
  list: () => client.get<ApiResponse<ProjectResponse[]>>('/projects'),
  create: (data: { name?: string; creationType?: string; aspectRatio?: string; status?: string }) =>
    client.post<ApiResponse<ProjectResponse>>('/projects', data),
  get: (id: string) => client.get<ApiResponse<ProjectResponse>>(`/projects/${id}`),
  update: (id: string, data: Record<string, unknown>) =>
    client.put<ApiResponse<ProjectResponse>>(`/projects/${id}`, data),
  delete: (id: string) => client.delete(`/projects/${id}`),
  getDraft: () => client.get<ApiResponse<ProjectResponse | null>>('/projects/draft'),
};
