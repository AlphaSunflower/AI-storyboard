import { beforeEach, describe, expect, it } from 'vitest';
import { executeToolCall } from './toolRegistry';
import { canvasCommandService } from './canvasCommandService';
import { markPendingGroupRun, usePendingGroupRunStore } from './pendingGroupRun';
import { useCanvasStore } from '../store/canvasStore';
import type { CanvasNode } from '../types';

const node = (id: string, x: number, group?: { id: string; title?: string }): CanvasNode => ({
  id,
  type: 'imageGeneration',
  position: { x, y: 0 },
  width: 200,
  height: 100,
  data: {
    kind: 'imageGeneration',
    label: id,
    prompt: '',
    status: 'idle',
    accent: '#fff',
    ...(group ? { groupId: group.id, groupTitle: group.title ?? '分组 1', groupCreatedAt: 1 } : {}),
  },
});

const reset = (nodes: CanvasNode[]) => {
  usePendingGroupRunStore.getState().reset();
  useCanvasStore.setState({
    projectId: 'project-1',
    canvasId: 'canvas-1',
    nodes,
    edges: [],
    generationRecords: [],
    executions: [],
    past: [],
    future: [],
    selectedNodeId: null,
    dragStartSnapshot: null,
    revision: 0,
  });
};

describe('执行入口只存在于分组', () => {
  beforeEach(() => reset([node('a', 0), node('b', 320), node('c', 640)]));

  it('未分组的节点提交执行时返回可纠正的 GROUP_REQUIRED，不给出"预览成功"的假象', async () => {
    const result = await executeToolCall('project-1', {
      id: 'call-1',
      name: 'canvas.graph.execute',
      arguments: { nodeIds: ['a', 'b'] },
    }, { source: 'mcp', dryRun: true });

    // 不能返回 ok:false：服务端会把画布任务标记成失败（AgentCanvasRunService.fail）。
    expect(result.ok).toBe(true);
    expect(result.status).toBe('preview');
    const payload = result.result as { code: string; requiresGroup: boolean; awaitingConfirmation: boolean; message: string };
    expect(payload.code).toBe('GROUP_REQUIRED');
    expect(payload.requiresGroup).toBe(true);
    expect(payload.awaitingConfirmation).toBe(false);
    expect(payload.message).toContain('尚未开始运行');
    expect(useCanvasStore.getState().executions).toEqual([]);
  });

  it('分组后提交执行返回预览 + groupId，并把待确认标记落到分组上', async () => {
    const grouped = await executeToolCall('project-1', {
      id: 'call-2',
      name: 'canvas.group.create',
      arguments: { nodeIds: ['a', 'b', 'c'], title: '赛亚人转场' },
    }, { source: 'mcp' });

    expect(grouped.ok).toBe(true);
    const groupId = (grouped.result as { groupId: string }).groupId;
    expect(groupId).toBeTruthy();

    const preview = await executeToolCall('project-1', {
      id: 'call-3',
      name: 'canvas.graph.execute',
      arguments: { nodeIds: ['a', 'b', 'c'] },
    }, { source: 'mcp', dryRun: true });

    expect(preview.ok).toBe(true);
    expect(preview.status).toBe('preview');
    const payload = preview.result as { groupId: string; groupTitle: string; awaitingConfirmation: boolean };
    expect(payload.groupId).toBe(groupId);
    expect(payload.groupTitle).toBe('赛亚人转场');
    expect(payload.awaitingConfirmation).toBe(true);
    // 预览不产生任何执行批次
    expect(useCanvasStore.getState().executions).toEqual([]);

    expect(markPendingGroupRun(preview.result)).toBe(true);
    expect(usePendingGroupRunStore.getState().byGroup[groupId]?.nodeIds).toEqual(['a', 'b', 'c']);
  });

  it('跨分组的执行请求同样只返回 GROUP_REQUIRED，不冒充预览成功', async () => {
    reset([node('a', 0, { id: 'g1' }), node('b', 320, { id: 'g2' })]);

    const result = await executeToolCall('project-1', {
      id: 'call-4',
      name: 'canvas.graph.execute',
      arguments: { nodeIds: ['a', 'b'] },
    }, { source: 'mcp', dryRun: true });

    expect(result.ok).toBe(true);
    const payload = result.result as { code: string; requiresGroup: boolean };
    expect(payload.code).toBe('GROUP_REQUIRED');
    expect(payload.requiresGroup).toBe(true);
  });

  it('分组命令本身不做强制预览式拒绝，只校验节点存在与数量', () => {
    const tooFew = canvasCommandService.createGroup(
      { projectId: 'project-1', source: 'mcp' }, { nodeIds: ['a'] });
    expect(tooFew).toMatchObject({ ok: false, error: { code: 'INVALID_ARGUMENTS' } });

    const missing = canvasCommandService.createGroup(
      { projectId: 'project-1', source: 'mcp' }, { nodeIds: ['a', 'nope'] });
    expect(missing).toMatchObject({ ok: false, error: { code: 'NODE_NOT_FOUND' } });
  });
});
