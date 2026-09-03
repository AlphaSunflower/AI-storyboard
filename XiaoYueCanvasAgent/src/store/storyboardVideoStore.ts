import { create } from 'zustand';
import type { StoryboardVideoConfig, StoryboardVideoStatus } from '../types';

/** 一个待生成的分镜视频任务（对应分镜节点里的某一帧）。 */
export interface StoryboardVideoTask {
  /** 客户端唯一键，同时作为 clientRequestId（幂等键）。 */
  queueId: string;
  nodeId: string;
  frameIndex: number;
  jobId?: string;
  status: StoryboardVideoStatus;
  progress: number;
  prompt: string;
  config?: StoryboardVideoConfig;
  sourceAssetIds: string[];
  createdAt: number;
  error?: string;
}

interface StoryboardVideoStoreState {
  tasks: Record<string, StoryboardVideoTask>;
  enqueue: (task: StoryboardVideoTask) => void;
  update: (queueId: string, patch: Partial<StoryboardVideoTask>) => void;
  remove: (queueId: string) => void;
  clearFinished: () => void;
}

export const useStoryboardVideoStore = create<StoryboardVideoStoreState>((set) => ({
  tasks: {},
  enqueue: (task) => set((state) => ({ tasks: { ...state.tasks, [task.queueId]: task } })),
  update: (queueId, patch) => set((state) => {
    const task = state.tasks[queueId];
    if (!task) return state;
    return { tasks: { ...state.tasks, [queueId]: { ...task, ...patch } } };
  }),
  remove: (queueId) => set((state) => {
    const tasks = { ...state.tasks };
    delete tasks[queueId];
    return { tasks };
  }),
  clearFinished: () => set((state) => {
    const keep: Record<string, StoryboardVideoTask> = {};
    for (const [id, task] of Object.entries(state.tasks)) {
      if (task.status !== 'completed' && task.status !== 'failed' && task.status !== 'cancelled') {
        keep[id] = task;
      }
    }
    return { tasks: keep };
  }),
}));

/** 按入队顺序返回全部任务。 */
export const selectVideoTasks = (tasks: Record<string, StoryboardVideoTask>) =>
  Object.values(tasks).sort((a, b) => a.createdAt - b.createdAt);

/** 仍在活动中的任务（排队/生成/取消中），用于右上角角标与取消按钮判断。 */
export const selectActiveVideoTasks = (tasks: Record<string, StoryboardVideoTask>) =>
  selectVideoTasks(tasks).filter((task) =>
    task.status === 'queued' || task.status === 'running' || task.status === 'cancelling');
