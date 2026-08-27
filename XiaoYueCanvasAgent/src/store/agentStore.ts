import { create } from 'zustand';
import { createId } from '../utils/id';

export interface ConversationSummary {
  id: string;
  title: string;
  status: string;
  updatedAt: string;
}

export type AgentMessageRole = 'user' | 'agent' | 'error' | 'status';

export interface AgentToolCallState {
  id: string;
  name: string;
  arguments: Record<string, unknown>;
  status: 'preview' | 'waiting_for_approval' | 'executing' | 'completed' | 'failed' | 'cancelled';
  description?: string;
  result?: unknown;
  error?: string;
  transactionId?: string;
}

export interface AgentMessage {
  id: string;
  role: AgentMessageRole;
  text: string;
  detail?: string;
  createdAt: number;
  jobId?: string;
  serverMessageId?: string;
  toolCall?: AgentToolCallState;
}

export interface AgentJobState {
  id: string;
  prompt: string;
  sourceNodeId?: string;
  capabilityId: string;
  status: 'submitting' | 'queued' | 'running' | 'receiving' | 'cancelling' | 'completed' | 'failed' | 'cancelled';
  progress: number;
  createdAt: number;
}

interface AgentSession {
  conversationId: string;
  draft: string;
  messages: AgentMessage[];
  jobs: Record<string, AgentJobState>;
}

interface AgentStoreState {
  conversationLists: Record<string, ConversationSummary[]>;
  loadConversations: (projectId: string, adapter: { listConversations?: (projectId: string) => Promise<ConversationSummary[]> }) => Promise<void>;
  selectConversation: (projectId: string, conversationId: string, messages: AgentMessage[]) => void;

  sessions: Record<string, AgentSession>;
  ensureSession: (projectId: string) => AgentSession;
  setConversation: (projectId: string, conversationId: string, messages: AgentMessage[]) => void;
  setDraft: (projectId: string, draft: string) => void;
  appendMessage: (projectId: string, message: Omit<AgentMessage, 'id' | 'createdAt'> & Partial<Pick<AgentMessage, 'id' | 'createdAt'>>) => void;
  appendAgentDelta: (projectId: string, jobId: string, text: string, serverMessageId?: string) => void;
  putJob: (projectId: string, job: AgentJobState) => void;
  updateJob: (projectId: string, jobId: string, patch: Partial<AgentJobState>) => void;
  removeJob: (projectId: string, jobId: string) => void;
  clearSession: (projectId: string) => void;
  updateToolCall: (projectId: string, messageId: string, patch: Partial<AgentToolCallState>) => void;
}

const newSession = (): AgentSession => ({ conversationId: createId(), draft: '', messages: [], jobs: {} });

export const useAgentStore = create<AgentStoreState>((set, get) => ({
  conversationLists: {},
  loadConversations: async (projectId, adapter) => {
    if (!adapter.listConversations) return;
    try {
      const list = await adapter.listConversations(projectId);
      set((state) => ({ conversationLists: { ...state.conversationLists, [projectId]: list } }));
    } catch { /* 静默 */ }
  },
  selectConversation: (projectId, conversationId, messages) => set((state) => {
    const session = state.sessions[projectId] ?? newSession();
    return { sessions: { ...state.sessions, [projectId]: { ...session, conversationId, messages, jobs: {} } } };
  }),
  sessions: {},
  ensureSession: (projectId) => {
    const current = get().sessions[projectId];
    if (current) return current;
    const session = newSession();
    set((state) => ({ sessions: { ...state.sessions, [projectId]: session } }));
    return session;
  },
  setConversation: (projectId, conversationId, messages) => set((state) => {
    const session = state.sessions[projectId] ?? newSession();
    return { sessions: { ...state.sessions, [projectId]: { ...session, conversationId, messages } } };
  }),
  setDraft: (projectId, draft) => set((state) => {
    const session = state.sessions[projectId] ?? newSession();
    return { sessions: { ...state.sessions, [projectId]: { ...session, draft } } };
  }),
  appendMessage: (projectId, message) => set((state) => {
    const session = state.sessions[projectId] ?? newSession();
    const next: AgentMessage = { id: message.id ?? createId(), createdAt: message.createdAt ?? Date.now(), ...message };
    return { sessions: { ...state.sessions, [projectId]: { ...session, messages: [...session.messages, next] } } };
  }),
  appendAgentDelta: (projectId, jobId, text, serverMessageId) => set((state) => {
    const session = state.sessions[projectId] ?? newSession();
    const index = session.messages.findIndex((message) => message.role === 'agent' && message.jobId === jobId && message.serverMessageId === serverMessageId);
    const messages = index < 0
      ? [...session.messages, { id: createId(), role: 'agent' as const, text, createdAt: Date.now(), jobId, serverMessageId }]
      : session.messages.map((message, messageIndex) => messageIndex === index ? { ...message, text: message.text + text } : message);
    return { sessions: { ...state.sessions, [projectId]: { ...session, messages } } };
  }),
  putJob: (projectId, job) => set((state) => {
    const session = state.sessions[projectId] ?? newSession();
    return { sessions: { ...state.sessions, [projectId]: { ...session, jobs: { ...session.jobs, [job.id]: job } } } };
  }),
  updateJob: (projectId, jobId, patch) => set((state) => {
    const session = state.sessions[projectId];
    const job = session?.jobs[jobId];
    if (!session || !job) return state;
    return { sessions: { ...state.sessions, [projectId]: { ...session, jobs: { ...session.jobs, [jobId]: { ...job, ...patch } } } } };
  }),
  removeJob: (projectId, jobId) => set((state) => {
    const session = state.sessions[projectId];
    if (!session?.jobs[jobId]) return state;
    const jobs = { ...session.jobs };
    delete jobs[jobId];
    return { sessions: { ...state.sessions, [projectId]: { ...session, jobs } } };
  }),
  clearSession: (projectId) => set((state) => ({ sessions: { ...state.sessions, [projectId]: newSession() } })),
  updateToolCall: (projectId, messageId, patch) => set((state) => {
    const session = state.sessions[projectId];
    if (!session) return state;
    return { sessions: { ...state.sessions, [projectId]: { ...session, messages: session.messages.map((message) => message.id === messageId && message.toolCall ? { ...message, toolCall: { ...message.toolCall, ...patch } } : message) } } };
  }),
}));
