import client from './client';

export interface AgentMemory {
  id: string;
  projectId: string;
  conversationId?: string;
  kind: 'goal' | 'style' | 'constraint' | 'decision' | 'feedback' | string;
  content: string;
  source: string;
  confidence: number;
  expiresAt?: string;
  updatedAt?: string;
}

/** 后端硬上限：前端据此展示容量与字数。 */
export interface AgentMemoryLimits {
  project: number;
  user: number;
  contentChars: number;
}

/** 账号级全局记忆（每账号一条，用户维护、跨项目生效）。 */
export interface AgentGlobalMemory {
  content: string;
  updatedAt?: string;
}

export interface AgentMemorySettings {
  enabled: boolean;
  global: AgentGlobalMemory | null;
  limits: AgentMemoryLimits;
}

export const memoryApi = {
  list: () => client.get<{ data: AgentMemory[] }>('/agent/memories'),
  settings: () => client.get<{ data: AgentMemorySettings }>('/agent/memories/settings'),
  updateSettings: (enabled: boolean) =>
    client.put<{ data: AgentMemorySettings }>('/agent/memories/settings', { enabled }),
  saveGlobal: (content: string) =>
    client.put<{ data: AgentGlobalMemory }>('/agent/memories/global', { content }),
  clearGlobal: () => client.delete<{ data: boolean }>('/agent/memories/global'),
  remove: (id: string) => client.delete<{ data: boolean }>(`/agent/memories/${id}`),
};
