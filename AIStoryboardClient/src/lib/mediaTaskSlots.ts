import { useMemo } from 'react';
import type { AgentTaskCardRef, AgentTaskView } from '../api/agent';
import { useAgentStore } from '../stores/agentStore';

/** 会产出媒体的后台任务：批量生图（带槽位）与视频任务 */
export function isMediaTask(task: AgentTaskView | undefined): boolean {
  if (!task) return false;
  return task.kind === 'image-batch' || task.kind === 'video' || (task.slots?.length ?? 0) > 0;
}

/**
 * 消息上挂着的任务卡里，哪些是需要铺预置位条的任务。
 * 返回拼接串而不是数组：作为 zustand 选择器的返回值必须引用稳定，
 * 否则每次任务进度更新都会重渲染整个会话的所有消息。
 */
export function selectMediaTaskKey(
  tasks: Record<string, AgentTaskView>,
  cards: AgentTaskCardRef[] | undefined,
): string {
  if (!cards?.length) return '';
  return cards.filter((card) => isMediaTask(tasks[card.taskId])).map((card) => card.taskId).join('|');
}

export function useMediaTaskIds(cards: AgentTaskCardRef[] | undefined): string[] {
  const key = useAgentStore((state) => selectMediaTaskKey(state.tasks, cards));
  return useMemo(() => (key ? key.split('|') : []), [key]);
}
