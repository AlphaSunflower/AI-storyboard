import type { CanvasNode } from '../types';
import { getNodeDefinition, type GenerationRecord } from './graphContracts';

/**
 * 画布分组（frame）是纯组织层：不新增节点类型、不改变端口与执行语义，
 * 只在每个成员节点的 `data` 上写同一份 `groupId / groupTitle / groupCreatedAt`。
 *
 * <p>这样做的好处：
 * - 自动随节点图落库（服务端 graph_json 原样保存），不需要新增顶层 schema；
 * - 撤销/重做、增量补丁、项目包导入导出的 node.data 路径天然覆盖；
 * - Agent 工具看到的仍然只有普通节点，不会把分组当成可执行节点。
 *
 * <p>框的位置不单独存，按成员节点的当前包围盒实时推导：拖节点、自动排版、
 * 撤销之后框都会自己贴合，不会出现“框还在原地、节点已经跑了”的脏状态。
 *
 * <p>嵌套分组：子分组的所有成员节点带同一个 `groupParentId`，指向上级分组 id。
 * 外框的包围盒 = 直接成员节点 + 子分组框的并集；拖动/选中/执行/折叠都按递归成员处理。
 */

/** 分组框相对节点包围盒的左右/底部留白（flow 坐标）。 */
export const FRAME_PADDING = 20;
/** 分组框顶部标题条高度（flow 坐标）。 */
export const FRAME_HEADER_HEIGHT = 40;
/** 节点外置标题浮在卡片上方，框顶再留一段，避免标题压住分组标题条。 */
export const FRAME_TITLE_CLEARANCE = 28;
/** 折叠后的分组条尺寸：父分组按这个尺寸给子分组让位。 */
export const COLLAPSED_FRAME_WIDTH = 340;
export const COLLAPSED_FRAME_HEIGHT = 48;

export interface CanvasGroup {
  id: string;
  title: string;
  createdAt: number;
  collapsed: boolean;
  /** 上级分组 id；顶层分组为 undefined。 */
  parentId?: string;
  /** 直接成员；嵌套子分组的节点不在这里。 */
  nodes: CanvasNode[];
}

export interface FrameBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface GroupLayoutEntry {
  group: CanvasGroup;
  bounds: FrameBounds;
  depth: number;
  /** 祖先里有折叠分组：自身和子分组都不该渲染。 */
  hiddenByCollapsedAncestor: boolean;
}

export interface GroupProgress {
  /** 组内可执行（生成类）节点总数。 */
  total: number;
  active: number;
  done: number;
  failed: number;
}

const nodeSize = (node: CanvasNode) => ({
  width: node.width ?? node.measured?.width ?? 240,
  height: node.height ?? node.measured?.height ?? 180,
});

const nodeRect = (node: CanvasNode) => ({
  x: node.position.x,
  y: node.position.y,
  width: nodeSize(node).width,
  height: nodeSize(node).height,
});

const frameFromRects = (rects: Array<{ x: number; y: number; width: number; height: number }>): FrameBounds | null => {
  if (!rects.length) return null;
  let minX = Number.POSITIVE_INFINITY;
  let minY = Number.POSITIVE_INFINITY;
  let maxX = Number.NEGATIVE_INFINITY;
  let maxY = Number.NEGATIVE_INFINITY;
  for (const rect of rects) {
    minX = Math.min(minX, rect.x);
    minY = Math.min(minY, rect.y);
    maxX = Math.max(maxX, rect.x + rect.width);
    maxY = Math.max(maxY, rect.y + rect.height);
  }
  return {
    x: minX - FRAME_PADDING,
    y: minY - FRAME_PADDING - FRAME_HEADER_HEIGHT - FRAME_TITLE_CLEARANCE,
    width: maxX - minX + FRAME_PADDING * 2,
    height: maxY - minY + FRAME_PADDING * 2 + FRAME_HEADER_HEIGHT + FRAME_TITLE_CLEARANCE,
  };
};

/** 按 groupId 聚合直接成员；父分组 id 从成员节点的 groupParentId 读取。 */
export function collectCanvasGroups(nodes: CanvasNode[]): CanvasGroup[] {
  const groups = new Map<string, CanvasGroup>();
  for (const node of nodes) {
    const id = typeof node.data.groupId === 'string' ? node.data.groupId : '';
    if (!id) continue;
    const parent = typeof node.data.groupParentId === 'string' && node.data.groupParentId
      ? node.data.groupParentId
      : '';
    const existing = groups.get(id);
    if (existing) {
      existing.nodes.push(node);
      if (node.data.groupCollapsed === true) existing.collapsed = true;
      if (!existing.parentId && parent) existing.parentId = parent;
      continue;
    }
    groups.set(id, {
      id,
      title: typeof node.data.groupTitle === 'string' && node.data.groupTitle.trim()
        ? node.data.groupTitle.trim()
        : '分组',
      createdAt: typeof node.data.groupCreatedAt === 'number' ? node.data.groupCreatedAt : 0,
      collapsed: node.data.groupCollapsed === true,
      parentId: parent || undefined,
      nodes: [node],
    });
  }
  // 父分组已经不存在 / 指回自己 → 提升为顶层；再断开任何环。
  for (const group of groups.values()) {
    if (!group.parentId) continue;
    if (group.parentId === group.id || !groups.has(group.parentId)) group.parentId = undefined;
  }
  for (const group of groups.values()) {
    const seen = new Set([group.id]);
    let cursor = group.parentId;
    while (cursor) {
      if (seen.has(cursor)) { group.parentId = undefined; break; }
      seen.add(cursor);
      cursor = groups.get(cursor)?.parentId;
    }
  }
  return [...groups.values()].sort((left, right) => left.createdAt - right.createdAt || left.id.localeCompare(right.id));
}

export function groupChildren(groups: CanvasGroup[], parentId: string): CanvasGroup[] {
  return groups.filter((group) => group.parentId === parentId);
}

export function groupDescendantIds(groups: CanvasGroup[], groupId: string): Set<string> {
  const result = new Set<string>();
  const stack = [...groupChildren(groups, groupId)];
  while (stack.length) {
    const group = stack.pop()!;
    if (result.has(group.id)) continue;
    result.add(group.id);
    stack.push(...groupChildren(groups, group.id));
  }
  return result;
}

/** 递归成员节点（含所有子分组）；用于拖动/选中/执行/折叠。 */
export function groupMemberNodes(groups: CanvasGroup[], groupId: string): CanvasNode[] {
  const group = groups.find((candidate) => candidate.id === groupId);
  if (!group) return [];
  const result = [...group.nodes];
  const stack = [...groupChildren(groups, groupId)];
  const seen = new Set<string>([groupId]);
  while (stack.length) {
    const child = stack.pop()!;
    if (seen.has(child.id)) continue;
    seen.add(child.id);
    result.push(...child.nodes);
    stack.push(...groupChildren(groups, child.id));
  }
  return result;
}

export function groupMemberNodeIds(groups: CanvasGroup[], groupId: string): string[] {
  return groupMemberNodes(groups, groupId).map((node) => node.id);
}

/**
 * 计算每个分组的框：直接成员节点 + 子分组框的并集，再补标题条/留白。
 * 结果按深度升序（父框先画、子框后画），并标出祖先已折叠的分组。
 */
export function buildGroupLayout(groups: CanvasGroup[]): GroupLayoutEntry[] {
  const byId = new Map(groups.map((group) => [group.id, group]));
  const frameCache = new Map<string, FrameBounds | null>();

  const frameFor = (group: CanvasGroup, visiting: Set<string>): FrameBounds | null => {
    if (frameCache.has(group.id)) return frameCache.get(group.id) ?? null;
    if (visiting.has(group.id)) return null;
    visiting.add(group.id);
    const rects: Array<{ x: number; y: number; width: number; height: number }> = group.nodes.map(nodeRect);
    for (const child of groupChildren(groups, group.id)) {
      const childFrame = frameFor(child, visiting);
      if (!childFrame) continue;
      // 折叠的子分组只占一条，父框跟着收，不会留下一个空的大框。
      rects.push(child.collapsed
        ? { x: childFrame.x, y: childFrame.y, width: COLLAPSED_FRAME_WIDTH, height: COLLAPSED_FRAME_HEIGHT }
        : childFrame);
    }
    visiting.delete(group.id);
    const bounds = frameFromRects(rects);
    frameCache.set(group.id, bounds);
    return bounds;
  };

  const depthOf = (group: CanvasGroup): number => {
    let depth = 0;
    let cursor = group.parentId;
    const seen = new Set([group.id]);
    while (cursor && byId.has(cursor) && !seen.has(cursor)) {
      seen.add(cursor);
      depth += 1;
      cursor = byId.get(cursor)?.parentId;
    }
    return depth;
  };

  const hiddenByAncestor = (group: CanvasGroup): boolean => {
    let cursor = group.parentId;
    const seen = new Set([group.id]);
    while (cursor && byId.has(cursor) && !seen.has(cursor)) {
      seen.add(cursor);
      if (byId.get(cursor)?.collapsed) return true;
      cursor = byId.get(cursor)?.parentId;
    }
    return false;
  };

  return groups
    .map((group) => ({ group, bounds: frameFor(group, new Set()), depth: depthOf(group), hiddenByCollapsedAncestor: hiddenByAncestor(group) }))
    .filter((entry): entry is GroupLayoutEntry => Boolean(entry.bounds))
    .sort((left, right) => left.depth - right.depth
      || left.group.createdAt - right.group.createdAt
      || left.group.id.localeCompare(right.group.id));
}

/** 折叠分组（含子分组）需要隐藏的节点 id。 */
export function collapsedGroupNodeIds(groups: CanvasGroup[]): Set<string> {
  const ids = new Set<string>();
  for (const group of groups) {
    if (!group.collapsed) continue;
    for (const node of groupMemberNodes(groups, group.id)) ids.add(node.id);
  }
  return ids;
}

/** 组内生成类节点的执行进度。 */
export function groupExecutionProgress(nodes: CanvasNode[]): GroupProgress {
  let total = 0;
  let active = 0;
  let done = 0;
  let failed = 0;
  for (const node of nodes) {
    const startable = getNodeDefinition(node.data.kind)?.actions.some((action) => action.automation.startable);
    if (!startable) continue;
    total += 1;
    const automation = node.data.automation as { phase?: string } | undefined;
    const phase = automation && typeof automation === 'object' ? automation.phase : undefined;
    const status = node.data.status;
    if (phase === 'failed' || phase === 'blocked' || phase === 'stale' || status === 'error') { failed += 1; continue; }
    if (phase === 'done' || status === 'success') { done += 1; continue; }
    if (node.data.isGenerating === true || status === 'queued' || status === 'running'
      || phase === 'queued' || phase === 'ready' || phase === 'running') { active += 1; }
  }
  return { total, active, done, failed };
}

/** 组内所有结果素材：优先取生成记录真正落地的结果，没有记录时退回成员节点上的素材。 */
export function groupResultAssetIds(nodes: CanvasNode[], records: GenerationRecord[] = []): string[] {
  const ids: string[] = [];
  const add = (assetId: string | undefined) => {
    if (assetId && !ids.includes(assetId)) ids.push(assetId);
  };
  const memberIds = new Set(nodes.map((node) => node.id));
  const assetByNodeId = new Map(nodes
    .map((node) => [node.id, typeof node.data.assetId === 'string' ? node.data.assetId : ''] as const)
    .filter(([, assetId]) => Boolean(assetId)));
  for (const record of records) {
    if (!memberIds.has(record.producerNodeId)) continue;
    record.materializedAssetIds.forEach(add);
    record.resultNodeIds.forEach((nodeId) => add(assetByNodeId.get(nodeId)));
  }
  if (ids.length) return ids;
  for (const node of nodes) add(typeof node.data.assetId === 'string' ? node.data.assetId : undefined);
  return ids;
}

/** 成员节点的包围盒 + 标题条/留白 = 分组框。空分组返回 null。 */
export function groupFrameBounds(nodes: CanvasNode[]): FrameBounds | null {
  return frameFromRects(nodes.map(nodeRect));
}

/** 默认分组名：取当前画布里第一个没被占用的「分组 N」。 */
export function nextGroupTitle(nodes: CanvasNode[]): string {
  const used = new Set(
    nodes
      .map((node) => (typeof node.data.groupTitle === 'string' ? node.data.groupTitle.trim() : ''))
      .filter(Boolean),
  );
  let index = 1;
  while (used.has(`分组 ${index}`)) index += 1;
  return `分组 ${index}`;
}
