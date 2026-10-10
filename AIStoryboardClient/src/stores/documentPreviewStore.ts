import { create } from 'zustand';
import type { AgentAttachment } from '../api/agent';

interface DocumentPreviewState {
  attachment: AgentAttachment | null;
  openDocument: (attachment: AgentAttachment) => void;
  closeDocument: () => void;
}

export const useDocumentPreviewStore = create<DocumentPreviewState>((set) => ({
  attachment: null,
  openDocument: (attachment) => set({ attachment }),
  closeDocument: () => set({ attachment: null }),
}));
