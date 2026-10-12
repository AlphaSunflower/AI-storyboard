import { create } from 'zustand';
import type { MetadataState } from '../api/library';
import { notify } from './toastStore';

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

/** 上传/导入终态聚合窗口：窗口内的结果合成一条通知，避免逐个文件刷屏 */
const UPLOAD_NOTICE_WINDOW_MS = 700;

interface UploadNoticeSummary {
  completed: number;
  failed: number;
  firstError?: string;
}

let pendingUploadNotice: UploadNoticeSummary | null = null;
let pendingUploadTimer: ReturnType<typeof setTimeout> | null = null;

function flushUploadNotice() {
  pendingUploadTimer = null;
  const summary = pendingUploadNotice;
  pendingUploadNotice = null;
  if (!summary || summary.completed + summary.failed === 0) return;
  if (summary.failed > 0) {
    notify({
      title: `上传完成，${summary.failed} 项失败`,
      description: summary.firstError || `${summary.completed} 个文件已入库`,
      variant: 'error',
    });
    return;
  }
  notify({ title: '上传完成', description: `${summary.completed} 个文件已入库` });
}

function trackUploadResult(status: LibraryTaskStatus, error?: string) {
  if (status !== 'completed' && status !== 'failed') return;
  const summary: UploadNoticeSummary = pendingUploadNotice ?? { completed: 0, failed: 0 };
  if (status === 'completed') {
    summary.completed += 1;
  } else {
    summary.failed += 1;
    if (!summary.firstError && error) summary.firstError = error;
  }
  pendingUploadNotice = summary;
  if (pendingUploadTimer) clearTimeout(pendingUploadTimer);
  pendingUploadTimer = setTimeout(flushUploadNotice, UPLOAD_NOTICE_WINDOW_MS);
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
  updateTask: (id, patch) => {
    const target = useLibraryTaskStore.getState().tasks.find((task) => task.id === id);
    // 仅在上传/导入任务首次进入终态时计数，避免同一任务被重复统计
    if (target
      && (target.kind === 'upload' || target.kind === 'import')
      && patch.status
      && patch.status !== target.status
      && (patch.status === 'completed' || patch.status === 'failed')) {
      trackUploadResult(patch.status, patch.error);
    }
    set((state) => ({
      tasks: state.tasks.map((task) => task.id === id ? { ...task, ...patch } : task),
    }));
  },
  addAnalysisTask: (assetId, label, metadataState) => set((state) => {
    const status: LibraryTaskStatus = metadataState === 'AI_GENERATING'
      ? 'processing'
      : metadataState === 'AI_FAILED' ? 'failed' : 'completed';
    const id = `analysis:${assetId}`;
    const task: LibraryTask = {
      id,
      kind: 'analysis',
      label,
      detail: 'AI 正在完善描述与标签',
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
