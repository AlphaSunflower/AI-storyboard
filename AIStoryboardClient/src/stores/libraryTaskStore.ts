import { create } from 'zustand';
import type { MetadataState } from '../api/library';

export type LibraryTaskKind = 'upload' | 'import' | 'analysis';
export type LibraryTaskStatus = 'queued' | 'uploading' | 'processing' | 'completed' | 'failed';

export interface LibraryTask {
  id: string;
  kind: LibraryTaskKind;
  label: string;
  detail?: string;
  status: LibraryTaskStatus;
  progress: number;
  assetId?: string;
  jobId?: string;
  error?: string;
}

interface LibraryTaskState {
  tasks: LibraryTask[];
  panelOpen: boolean;
  setPanelOpen: (open: boolean) => void;
  upsertTask: (task: LibraryTask) => void;
  updateTask: (id: string, patch: Partial<Omit<LibraryTask, 'id'>>) => void;
  addAnalysisTask: (assetId: string, label: string, state: MetadataState) => void;
  clearFinished: () => void;
}

export const useLibraryTaskStore = create<LibraryTaskState>((set) => ({
  tasks: [],
  panelOpen: false,
  setPanelOpen: (panelOpen) => set({ panelOpen }),
  upsertTask: (task) => set((state) => {
    const index = state.tasks.findIndex((item) => item.id === task.id);
    if (index < 0) return { tasks: [task, ...state.tasks], panelOpen: true };
    const tasks = [...state.tasks];
    tasks[index] = { ...tasks[index], ...task };
    return { tasks };
  }),
  updateTask: (id, patch) => set((state) => ({
    tasks: state.tasks.map((task) => task.id === id ? { ...task, ...patch } : task),
  })),
  addAnalysisTask: (assetId, label, metadataState) => set((state) => {
    const status: LibraryTaskStatus = metadataState === 'AI_GENERATING'
      ? 'processing'
      : metadataState === 'AI_FAILED' ? 'failed' : 'completed';
    const id = `analysis:${assetId}`;
    const task: LibraryTask = {
      id,
      kind: 'analysis',
      label,
      detail: 'AI 自动生成描述并匹配已有标签',
      status,
      progress: status === 'completed' ? 100 : 20,
      assetId,
      error: status === 'failed' ? 'AI 分析失败，可稍后手动补充' : undefined,
    };
    const index = state.tasks.findIndex((item) => item.id === id);
    if (index < 0) return { tasks: [task, ...state.tasks], panelOpen: true };
    const tasks = [...state.tasks];
    tasks[index] = { ...tasks[index], ...task };
    return { tasks };
  }),
  clearFinished: () => set((state) => ({
    tasks: state.tasks.filter((task) => task.status !== 'completed'),
  })),
}));
