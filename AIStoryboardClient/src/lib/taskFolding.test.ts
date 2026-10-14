import { describe, expect, it } from 'vitest';
import type { AgentTaskView } from '../api/agent';
import {
  foldTasksByToolCallId,
  foldedTaskIds,
  hasToolCardParent,
  nestedTasksForToolCall,
  normalizeToolCallId,
  taskParentToolCallId,
} from './taskFolding';

function taskView(overrides: Partial<AgentTaskView> & { taskId: string }): AgentTaskView {
  return {
    conversationId: 'conversation-1',
    projectId: 'project-1',
    title: '任务',
    toolName: 'produce_video_journey',
    kind: 'journey',
    status: 'running',
    stage: 'journey:image',
    progress: 20,
    resultSummary: '',
    error: '',
    parentTaskId: '',
    anchorMessageId: 'message-1',
    version: 1,
    createdAt: '2026-10-10T00:00:00Z',
    updatedAt: '2026-10-10T00:00:00Z',
    completedAt: null,
    assetIds: [],
    resultUrls: [],
    slots: [],
    result: '',
    liveText: '',
    liveThinking: '',
    liveStage: '',
    liveUpdatedAt: null,
    canCancel: true,
    canRetry: false,
    canFollowUp: false,
    ...overrides,
  };
}

describe('taskFolding 工具卡折叠', () => {
  it('归一化：非字符串、空串、纯空白一律视为无归属', () => {
    expect(normalizeToolCallId(' call-1 ')).toBe('call-1');
    expect(normalizeToolCallId('   ')).toBe('');
    expect(normalizeToolCallId(undefined)).toBe('');
    expect(normalizeToolCallId(null)).toBe('');
    expect(normalizeToolCallId(42)).toBe('');
    expect(taskParentToolCallId(taskView({ taskId: 'a' }))).toBe('');
    expect(hasToolCardParent(taskView({ taskId: 'a', parentToolCallId: 'call-1' }))).toBe(true);
  });

  it('只折叠 parentToolCallId 命中已渲染工具卡的任务', () => {
    const parent = taskView({ taskId: 'journey-1', parentToolCallId: 'call-journey' });
    const child = taskView({
      taskId: 'child-1',
      kind: 'image-batch',
      toolName: 'generate_image_batch',
      parentTaskId: 'journey-1',
      parentToolCallId: 'call-journey',
      createdAt: '2026-10-10T00:00:01Z',
    });
    const unknown = taskView({ taskId: 'other-1', parentToolCallId: 'call-missing' });
    const plain = taskView({ taskId: 'plain-1' });

    const folded = foldTasksByToolCallId([parent, child, unknown, plain], ['call-journey']);

    expect([...folded.keys()]).toEqual(['call-journey']);
    expect(folded.get('call-journey')?.map((task) => task.taskId)).toEqual(['journey-1', 'child-1']);
    expect([...foldedTaskIds(folded)]).toEqual(['journey-1', 'child-1']);
    // 未命中工具卡的任务既不折叠也不丢失。
    expect(nestedTasksForToolCall([parent, unknown], 'call-journey').map((task) => task.taskId))
      .toEqual(['journey-1']);
    expect(nestedTasksForToolCall([parent, child], '')).toEqual([]);
  });

  it('没有任何工具卡时一律不折叠（零回归）', () => {
    const task = taskView({ taskId: 'journey-1', parentToolCallId: 'call-journey' });
    expect(foldTasksByToolCallId([task], []).size).toBe(0);
    expect(foldTasksByToolCallId([task], ['', '   ']).size).toBe(0);
    expect(foldedTaskIds(new Map()).size).toBe(0);
  });

  it('稳定顺序：先 createdAt 再 taskId，直播与刷新顺序一致', () => {
    const later = taskView({
      taskId: 'child-b',
      parentToolCallId: 'call-journey',
      createdAt: '2026-10-10T00:00:02Z',
    });
    const earlier = taskView({
      taskId: 'child-a',
      parentToolCallId: 'call-journey',
      createdAt: '2026-10-10T00:00:01Z',
    });
    const sameStampB = taskView({ taskId: 'z', parentToolCallId: 'call-journey' });
    const sameStampA = taskView({ taskId: 'a', parentToolCallId: 'call-journey' });

    expect(foldTasksByToolCallId([later, earlier, sameStampB, sameStampA], ['call-journey'])
      .get('call-journey')?.map((task) => task.taskId)).toEqual(['a', 'z', 'child-a', 'child-b']);
  });
});
