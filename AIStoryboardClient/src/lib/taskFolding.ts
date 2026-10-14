import type { AgentTaskView } from '../api/agent';

/**
 * 复合旅程步骤的「工具卡折叠」纯逻辑。
 *
 * 服务端在任务视图上新增 `parentToolCallId`：旅程父任务是自己的 `toolCallId`，
 * 旅程子任务是**父任务**的 `toolCallId`，非旅程任务为空。前端据此把步骤进度折到
 * 命中的那张工具卡（`data-tool-call-id`）下，而不是渲染成互不相关的独立卡片。
 *
 * 容忍规则（缺字段必须与今天完全一致）：
 * - 字段缺失、非字符串、空串、纯空白 → 不折叠，任务仍按既有独立卡片渲染；
 * - `parentToolCallId` 未命中任何已渲染工具卡 → 不折叠（否则任务会凭空消失）；
 * - 同一个任务只会出现在一个桶里，因此不会重复出现两张卡片。
 */

/** 归一化工具调用 id：非字符串与纯空白一律视为「无归属」。 */
export function normalizeToolCallId(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

/** 任务的工具卡归属 id；未声明/空白时返回空串。 */
export function taskParentToolCallId(task: AgentTaskView | undefined | null): string {
  return normalizeToolCallId(task?.parentToolCallId);
}

/** 任务是否声明了工具卡归属（仅表示「可能被折叠」，能否折叠取决于是否命中工具卡）。 */
export function hasToolCardParent(task: AgentTaskView | undefined | null): boolean {
  return taskParentToolCallId(task) !== '';
}

/** 稳定顺序：先 `createdAt`，再 `taskId`，保证直播/刷新/重放三条路径的步骤顺序一致。 */
function compareTasks(left: AgentTaskView, right: AgentTaskView): number {
  const leftCreated = typeof left.createdAt === 'string' ? left.createdAt : '';
  const rightCreated = typeof right.createdAt === 'string' ? right.createdAt : '';
  if (leftCreated !== rightCreated) return leftCreated < rightCreated ? -1 : 1;
  return left.taskId < right.taskId ? -1 : left.taskId > right.taskId ? 1 : 0;
}

/**
 * 按工具卡 id 折叠任务：只折叠 `parentToolCallId` 命中 `toolCallIds` 的任务。
 *
 * @param tasks 候选任务（通常是 store 里的全部任务）
 * @param toolCallIds 当前真正渲染了工具卡的工具调用 id 集合
 * @returns 工具卡 id → 归属该卡的任务列表（按稳定顺序）
 */
export function foldTasksByToolCallId(
  tasks: Iterable<AgentTaskView>,
  toolCallIds: Iterable<string>,
): Map<string, AgentTaskView[]> {
  const known = new Set<string>();
  for (const id of toolCallIds) {
    const key = normalizeToolCallId(id);
    if (key) known.add(key);
  }
  const folded = new Map<string, AgentTaskView[]>();
  if (known.size === 0) return folded;
  const candidates: AgentTaskView[] = [];
  for (const task of tasks) {
    const parent = taskParentToolCallId(task);
    if (parent && known.has(parent)) candidates.push(task);
  }
  candidates.sort(compareTasks);
  for (const task of candidates) {
    const key = taskParentToolCallId(task);
    const bucket = folded.get(key);
    if (bucket) bucket.push(task);
    else folded.set(key, [task]);
  }
  return folded;
}

/** 已被折叠到某张工具卡下的任务 id：这些任务不再作为独立卡片渲染，避免重复卡片。 */
export function foldedTaskIds(folded: Map<string, AgentTaskView[]>): Set<string> {
  const ids = new Set<string>();
  for (const bucket of folded.values()) {
    for (const task of bucket) ids.add(task.taskId);
  }
  return ids;
}

/** 单张工具卡下的任务（`ToolDraftRow` 用；无归属/未命中时返回空数组）。 */
export function nestedTasksForToolCall(
  tasks: Iterable<AgentTaskView>,
  toolCallId: string,
): AgentTaskView[] {
  const key = normalizeToolCallId(toolCallId);
  if (!key) return [];
  return foldTasksByToolCallId(tasks, [key]).get(key) ?? [];
}
