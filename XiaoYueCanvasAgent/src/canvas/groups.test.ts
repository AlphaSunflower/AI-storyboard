import { describe, expect, it } from 'vitest';
import {
  buildGroupLayout,
  collapsedGroupNodeIds,
  collectCanvasGroups,
  groupExecutionProgress,
  groupFrameBounds,
  groupMemberNodes,
  groupResultAssetIds,
  nextGroupTitle,
  FRAME_HEADER_HEIGHT,
  FRAME_PADDING,
  FRAME_TITLE_CLEARANCE,
} from './groups';
import type { CanvasNode } from '../types';
import type { GenerationRecord } from './graphContracts';

const node = (id: string, x: number, y: number, group?: { id: string; title?: string; createdAt?: number; collapsed?: boolean; parentId?: string }): CanvasNode => ({
  id,
  type: 'image',
  position: { x, y },
  width: 200,
  height: 100,
  data: {
    kind: 'image',
    label: id,
    prompt: '',
    status: 'idle',
    accent: '#fff',
    ...(group ? {
      groupId: group.id,
      groupTitle: group.title ?? '分组 1',
      groupCreatedAt: group.createdAt ?? 1,
      groupCollapsed: group.collapsed ?? false,
      groupParentId: group.parentId,
    } : {}),
  },
});

describe('canvas groups', () => {
  it('aggregates nodes by groupId and ignores ungrouped nodes', () => {
    const groups = collectCanvasGroups([
      node('a', 0, 0, { id: 'g1', title: '镜头一', createdAt: 2 }),
      node('b', 300, 0, { id: 'g1', title: '镜头一', createdAt: 2 }),
      node('c', 0, 200, { id: 'g2', title: '镜头二', createdAt: 1 }),
      node('d', 500, 0),
    ]);

    expect(groups.map((group) => group.id)).toEqual(['g2', 'g1']);
    expect(groups[1]).toMatchObject({ title: '镜头一' });
    expect(groups[1].nodes.map((item) => item.id)).toEqual(['a', 'b']);
  });

  it('derives frame bounds from member node boxes plus header and padding', () => {
    const bounds = groupFrameBounds([
      node('a', 100, 100),
      node('b', 400, 300),
    ]);

    expect(bounds).toEqual({
      x: 100 - FRAME_PADDING,
      y: 100 - FRAME_PADDING - FRAME_HEADER_HEIGHT - FRAME_TITLE_CLEARANCE,
      width: 500 + FRAME_PADDING * 2,
      height: 300 + FRAME_PADDING * 2 + FRAME_HEADER_HEIGHT + FRAME_TITLE_CLEARANCE,
    });
  });

  it('marks a group collapsed when any member is collapsed', () => {
    const groups = collectCanvasGroups([
      node('a', 0, 0, { id: 'g1' }),
      node('b', 0, 0, { id: 'g1', collapsed: true }),
    ]);

    expect(groups[0].collapsed).toBe(true);
  });

  it('nests a child group inside its parent and expands the parent bounds', () => {
    const groups = collectCanvasGroups([
      node('a', 0, 0, { id: 'parent', title: '父分组' }),
      node('b', 400, 0, { id: 'child', title: '子分组', parentId: 'parent' }),
    ]);
    const byId = new Map(groups.map((group) => [group.id, group]));
    expect(byId.get('child')?.parentId).toBe('parent');
    expect(groupMemberNodes(groups, 'parent').map((item) => item.id)).toEqual(['a', 'b']);

    const layout = buildGroupLayout(groups);
    expect(layout.map((entry) => [entry.group.id, entry.depth])).toEqual([['parent', 0], ['child', 1]]);
    const parent = layout.find((entry) => entry.group.id === 'parent')!;
    const child = layout.find((entry) => entry.group.id === 'child')!;
    expect(parent.bounds.x).toBeLessThan(child.bounds.x);
    expect(parent.bounds.width).toBeGreaterThan(child.bounds.width);
  });

  it('hides child groups and descendant nodes when an ancestor is collapsed', () => {
    const groups = collectCanvasGroups([
      node('a', 0, 0, { id: 'parent', collapsed: true }),
      node('b', 400, 0, { id: 'child', parentId: 'parent' }),
    ]);

    const layout = buildGroupLayout(groups);
    expect(layout.find((entry) => entry.group.id === 'child')?.hiddenByCollapsedAncestor).toBe(true);
    expect([...collapsedGroupNodeIds(groups)].sort()).toEqual(['a', 'b']);
  });

  it('prefers materialized generation results over raw member assets', () => {
    const producer = {
      ...node('producer', 0, 0),
      data: { ...node('producer', 0, 0).data, kind: 'imageGeneration', assetId: 'asset-producer' },
    };
    const resultNode = {
      ...node('result-node', 300, 0),
      data: { ...node('result-node', 300, 0).data, assetId: 'asset-result' },
    };
    const record: GenerationRecord = {
      id: 'record-1',
      producerNodeId: 'producer',
      actionId: 'generate',
      attempt: 1,
      status: 'completed',
      materializationStatus: 'completed',
      materializedAssetIds: ['record-result'],
      resultNodeIds: ['result-node'],
      sources: [],
      prompt: '',
      effectivePrompt: '',
      params: {},
      startedAt: 1,
    };

    expect(groupResultAssetIds([producer, resultNode], [record])).toEqual(['record-result', 'asset-result']);
  });

  it('summarizes execution progress and result assets for the group', () => {
    const producer = (id: string, patch: Record<string, unknown>): CanvasNode => ({
      ...node(id, 0, 0),
      data: { ...node(id, 0, 0).data, kind: 'imageGeneration', assetId: `asset-${id}`, ...patch },
    });
    const nodes = [
      producer('run', { status: 'running' }),
      producer('fail', { status: 'error' }),
      producer('done', { status: 'success' }),
      producer('idle', { status: 'idle' }),
    ];

    expect(groupExecutionProgress(nodes)).toEqual({ total: 4, active: 1, done: 1, failed: 1 });
    expect(groupResultAssetIds(nodes)).toEqual(['asset-run', 'asset-fail', 'asset-done', 'asset-idle']);
  });

  it('有产出的节点算完成，即使阶段停在上一次失败', () => {
    const producer = (id: string, patch: Record<string, unknown>): CanvasNode => ({
      ...node(id, 0, 0),
      data: { ...node(id, 0, 0).data, kind: 'imageGeneration', ...patch },
    });
    const nodes = [
      producer('stale-failed', { automation: { phase: 'blocked' } }),
      producer('running', { automation: { phase: 'running' } }),
    ];

    // 不给回调时维持原判定
    expect(groupExecutionProgress(nodes)).toEqual({ total: 2, active: 1, done: 0, failed: 1 });
    // 结果已经落地 → 算完成，标题条不再显示「失败 1/2」
    expect(groupExecutionProgress(nodes, {
      hasProducedResult: (candidate) => candidate.id === 'stale-failed',
    })).toEqual({ total: 2, active: 1, done: 1, failed: 0 });
  });

  it('returns null for an empty group and picks the next free default title', () => {
    expect(groupFrameBounds([])).toBeNull();
    expect(nextGroupTitle([node('a', 0, 0, { id: 'g1', title: '分组 1' }), node('b', 0, 0, { id: 'g2', title: '分组 3' })]))
      .toBe('分组 2');
  });
});
