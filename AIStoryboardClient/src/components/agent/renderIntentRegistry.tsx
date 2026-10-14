import type { ReactElement } from 'react';
import type { RenderIntentKind, ToolRenderIntent } from '../../api/agent';
import { isKnownRenderKind, renderIntentTitle } from '../../lib/renderIntent';
import { RenderBlockCard } from './RenderBlockCard';

/**
 * 渲染意图注册表：`kind → 渲染器`。
 *
 * 设计约束（与 agentStore 的既有卡片分支共存，不做视觉回归）：
 * - `render` 缺省时，调用方继续走原来的 `canvas-run` / `video-plan` / `hitl` / `taskCards` 分支；
 * - `render` 存在时按 `kind` 分派；未知 kind 与缺渲染器的 kind 一律落到通用兜底；
 * - 新增 kind 只需在这里注册一条，调用方零改动。
 */
export type RenderIntentRenderer = (props: { intent: ToolRenderIntent }) => ReactElement;

/** 通用兜底：未知 kind、或该 kind 暂时没有专用渲染器时使用。 */
export function GenericRenderBlock({ intent }: { intent: ToolRenderIntent }): ReactElement {
  return <RenderBlockCard intent={intent} fallback />;
}

const RENDERERS = new Map<string, RenderIntentRenderer>();

/**
 * 每个已知 kind 都显式注册一个渲染器。
 * 当前 7 个 kind 共用同一张结构化卡片（按 kind 选择主体渲染方式），
 * 单独注册的意义是：以后某个 kind 需要完全定制的组件时，只替换这一行。
 */
for (const kind of ['table', 'list', 'gallery', 'progress', 'diff', 'form', 'preview'] as const) {
  RENDERERS.set(kind, ({ intent }) => <RenderBlockCard intent={intent} />);
}

export function registerRenderIntentRenderer(kind: string, renderer: RenderIntentRenderer) {
  if (!kind) return;
  RENDERERS.set(kind, renderer);
}

/** 按 `kind` 取渲染器；未知 kind 返回通用兜底，永不返回 undefined。 */
export function resolveRenderIntentRenderer(kind: string): RenderIntentRenderer {
  return RENDERERS.get(kind) ?? GenericRenderBlock;
}

export function renderIntentLabel(intent: ToolRenderIntent): string {
  return renderIntentTitle(intent);
}

export { isKnownRenderKind };
export type { RenderIntentKind };
