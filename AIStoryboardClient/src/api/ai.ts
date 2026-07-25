import client from './client';
import type { ApiResponse } from './auth';

export interface TaskStatusResponse {
  taskId: string;
  status: string;
  videoUrl?: string;
  progress?: string;
  error?: string;
}

export const aiApi = {
  generateScript: (data: {
    projectId: string;
    scriptText: string;
    creationType: string;
    customTypeDesc?: string;
    aspectRatio: string;
    model?: string;
    referenceImageUrl?: string;
  }) => client.post('/ai/generate-script', data),

  generateImage: (data: {
    sceneId: string;
    prompt: string;
    model?: string;
    size?: string;
    aspectRatio?: string;
    referenceImages?: string[];
  }) => client.post<ApiResponse<{ imageUrl: string }>>('/ai/generate-image', data),

  generateVideo: (data: {
    sceneId: string;
    prompt: string;
    model?: string;
    resolution?: string;
    duration?: number;
    referenceImages?: string[];
    generatedImageUrl?: string;
  }) => client.post<ApiResponse<{ taskId: string; sceneId: string }>>('/ai/generate-video', data),

  pollTask: (taskId: string) =>
    client.get<ApiResponse<TaskStatusResponse>>(`/ai/task/${taskId}`),

  getTaskStatus: (taskId: string) =>
    client.get<ApiResponse<TaskStatusResponse>>(`/ai/task/${taskId}`),
};
