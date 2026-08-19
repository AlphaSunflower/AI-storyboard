import client from './client';

/** 主后端统一响应包装(与 agent.ts 各接口返回结构一致) */
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

/** 与主前端 AIStoryboardClient/src/api/projects.ts 对齐的共享数据结构(只读使用) */
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
  /** 多图结果:逗号分隔 URL(imageUrl 为首图) */
  imageUrls: string;
  imageModel: string;
  imageSize: string;
  imageQuality: string;
  imageN: number;
  videoModel: string;
  videoAspectRatio: string;
  videoResolution: string;
  duration: number;
}

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

/** 项目 API:与 AI 分镜系统共享主后端 projects/scenes 数据(按 JWT userId 归属) */
export const projectApi = {
  list: () => client.get<ApiResponse<ProjectResponse[]>>('/projects'),
  get: (id: string) => client.get<ApiResponse<ProjectResponse>>(`/projects/${id}`),
};
