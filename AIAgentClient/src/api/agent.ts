import client from './client';
import { BACKEND_URL } from '../config';

/** 网关下发的模型选项（本地定义，不依赖 ai.ts） */
export interface GatewayModelOption {
  value: string;
  label: string;
  params?: string | null;
}

export interface AgentConversation {
  id: string;
  userId: string;
  projectId: string;
  title: string;
  difyConversationId: string | null;
  status: 'active' | 'archived';
  createdAt: string;
  updatedAt: string;
}

export interface AgentMessage {
  id: string;
  conversationId: string;
  role: 'user' | 'assistant';
  content: string;
  difyMessageId: string | null;
  createdAt: string;
}

export interface AgentAsset {
  id: string;
  conversationId: string | null;
  type: 'image' | 'video' | 'reference';
  url: string;
  prompt: string | null;
  model: string | null;
  status: string;
  taskId: string | null;
  error: string | null;
  createdAt: string;
}

export interface AgentPage<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

export interface AssetOption {
  id: string;
  name: string;
  type: string;
  image?: string;
}

export interface SseEvent {
  type: 'message' | 'workflow' | 'human_input' | 'message_end' | 'confirm_result' | 'video_plan' | 'task_accepted' | 'error';
  content?: string;
  title?: string;
  status?: string;
  formToken?: string;
  taskId?: string;
  formContent?: string;
  actions?: { id: string; title: string }[];
  expirationTime?: number;
  models?: GatewayModelOption[];
  imageModels?: GatewayModelOption[];
  videoModels?: GatewayModelOption[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
  messageId?: string;
  sceneCount?: number;
  kind?: 'script' | 'image' | 'video';
  url?: string;
  assetId?: string;
  urls?: string[];
  assetIds?: string[];
  planToken?: string;
  duration?: number;
  picUrl?: string;
  assets?: AssetOption[];
  code?: string;
  message?: string;
}

export interface VideoTaskStatus {
  taskId: string;
  assetId: string;
  status: 'queued' | 'running' | 'completed' | 'failed' | 'unknown';
  url: string;
  error: string;
}

export const agentApi = {
  listConversations: (projectId: string) =>
    client.get<{ data: AgentConversation[] }>('/agent/conversations', { params: { projectId } }),

  createConversation: (projectId: string, title?: string) =>
    client.post<{ data: AgentConversation }>('/agent/conversations', { projectId, title }),

  updateConversation: (id: string, data: { title?: string; status?: string }) =>
    client.patch<{ data: AgentConversation }>(`/agent/conversations/${id}`, data),

  deleteConversation: (id: string) =>
    client.delete(`/agent/conversations/${id}`),

  clearMessages: (id: string) =>
    client.delete(`/agent/conversations/${id}/messages`),

  listMessages: (id: string) =>
    client.get<{ data: AgentMessage[] }>(`/agent/conversations/${id}/messages`),

  listAssets: (id: string, page = 1, size = 20) =>
    client.get<{ data: AgentPage<AgentAsset> }>(`/agent/conversations/${id}/assets`, { params: { page, size } }),

  deleteAsset: (assetId: string) =>
    client.delete(`/agent/assets/${assetId}`),

  uploadImage: (file: File, conversationId?: string) => {
    const form = new FormData();
    form.append('file', file);
    if (conversationId) form.append('conversationId', conversationId);
    return client.post<{ data: { url: string } }>('/agent/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },

  confirmDone: (conversationId: string) =>
    client.post<{ data: boolean }>(`/agent/conversations/${conversationId}/confirm-done`),

  getVideoTaskStatus: (taskId: string) =>
    client.get<{ data: VideoTaskStatus }>(`/agent/tasks/${taskId}`),
};

/** 流式发送消息 */
export async function streamChat(
  conversationId: string,
  content: string,
  picUrl: string | undefined,
  onEvent: (e: SseEvent) => void,
): Promise<void> {
  const token = localStorage.getItem('accessToken') ?? '';
  const res = await fetch(`${BACKEND_URL}/api/agent/conversations/${conversationId}/messages/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ content, picUrl: picUrl ?? '' }),
  });
  await consumeSse(res, onEvent);
}

/** HITL 表单提交并续流 */
export async function submitForm(
  conversationId: string,
  formToken: string,
  taskId: string,
  action: string,
  onEvent: (e: SseEvent) => void,
  customText?: string,
  params?: Record<string, string>,
  assetIds?: string[],
): Promise<void> {
  const token = localStorage.getItem('accessToken') ?? '';
  const res = await fetch(`${BACKEND_URL}/api/agent/conversations/${conversationId}/form/submit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ formToken, taskId, action, content: customText ?? '', params: params ?? {}, assetIds: assetIds ?? [] }),
  });
  await consumeSse(res, onEvent);
}

async function consumeSse(res: Response, onEvent: (e: SseEvent) => void): Promise<void> {
  if (!res.ok) {
    let detail = '';
    try { detail = (await res.json()).message ?? ''; } catch { /* ignore */ }
    throw new Error(detail || `请求失败 (${res.status})`);
  }
  if (!res.body) throw new Error('响应无数据流');
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split('\n\n');
      buffer = parts.pop() ?? '';
      for (const part of parts) {
        const lines = part.split('\n');
        const eventLine = lines.find((l) => l.startsWith('event:'));
        const dataLine = lines.find((l) => l.startsWith('data:'));
        if (!dataLine) continue;
        const eventName = eventLine ? eventLine.slice(6).trim() : '';
        try {
          const data = JSON.parse(dataLine.slice(5).trim()) as SseEvent;
          onEvent({ ...data, type: eventName } as SseEvent);
        } catch { /* 忽略坏帧 */ }
      }
    }
  } finally {
    reader.releaseLock();
  }
}
