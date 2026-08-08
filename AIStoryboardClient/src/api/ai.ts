import client from './client';
import type { ApiResponse } from './auth';
import type { ModelOption } from '../config';

/** 网关下发的模型选项：params 为后端透传的 JSON 字符串（store 层解析为参数对象） */
export interface GatewayModelOption extends Omit<ModelOption, 'params'> {
  params?: string | null;
}

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
    quality?: string;
    aspectRatio?: string;
    referenceImages?: string[];
    mode?: string;
    generatedImageUrl?: string;
  }) => client.post<ApiResponse<{ imageUrl: string }>>('/ai/generate-image', data),

  generateVideo: (data: {
    sceneId: string;
    prompt: string;
    model?: string;
    resolution?: string;
    size?: string;
    aspectRatio?: string;
    duration?: number;
    negativePrompt?: string;
    seed?: number;
    referenceImages?: string[];
    generatedImageUrl?: string;
  }) => client.post<ApiResponse<{ taskId: string; sceneId: string }>>('/ai/generate-video', data),

  pollTask: (taskId: string) =>
    client.get<ApiResponse<TaskStatusResponse>>(`/ai/task/${taskId}`),

  getTaskStatus: (taskId: string) =>
    client.get<ApiResponse<TaskStatusResponse>>(`/ai/task/${taskId}`),

  // 网关模型列表（生图/生视频，来自 LLM 网关路由 type 过滤；网关不可用时为空数组；params 为 JSON 字符串）
  aiModels: () =>
    client.get<ApiResponse<{ imageModels: GatewayModelOption[]; videoModels: GatewayModelOption[] }>>('/ai/models'),
};
