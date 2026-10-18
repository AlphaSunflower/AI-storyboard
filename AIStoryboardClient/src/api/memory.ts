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

export const memoryApi = {
  list: () => client.get<{ data: AgentMemory[] }>('/agent/memories'),
  update: (id: string, data: { kind: string; content: string; confidence: number; expiresAt?: string | null }) =>
    client.put<{ data: AgentMemory }>(`/agent/memories/${id}`, data),
  remove: (id: string) => client.delete<{ data: boolean }>(`/agent/memories/${id}`),
};
