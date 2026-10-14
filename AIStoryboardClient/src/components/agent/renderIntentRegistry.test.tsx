import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { RenderBlockCard } from './RenderBlockCard';
import {
  GenericRenderBlock,
  isKnownRenderKind,
  registerRenderIntentRenderer,
  resolveRenderIntentRenderer,
} from './renderIntentRegistry';

afterEach(cleanup);

describe('render 意图 kind 分派', () => {
  it('已知 kind 各自渲染对应主体', () => {
    render(<RenderBlockCard intent={{ kind: 'table', id: 't', items: [{ 名称: 'A', 数量: 1 }] }} />);
    const card = document.querySelector('[data-render-kind="table"]');
    expect(card).not.toBeNull();
    // 表格渲染出单元格值，而不是退化成 JSON 兜底
    expect(card!.querySelector('table')).not.toBeNull();
    expect(screen.getByText('A')).toBeDefined();
    expect(screen.getByText('1')).toBeDefined();
    expect(screen.queryByText('通用展示')).toBeNull();
  });

  it('未知 kind 落到通用兜底而不报错', () => {
    render(<RenderBlockCard intent={{ kind: 'timeline-future', id: 'x', items: ['a', 'b'] }} fallback />);

    expect(document.querySelector('[data-render-kind="timeline-future"]')).not.toBeNull();
    expect(screen.getByText('通用展示')).toBeDefined();
    expect(screen.getByText('自定义结果（timeline-future）')).toBeDefined();
    // 兜底仍然把 items 展示出来，不吞服务端数据
    expect(screen.getByText('a')).toBeDefined();
    expect(screen.getByText('b')).toBeDefined();
  });

  it('注册表按 kind 取渲染器，未知 kind 一律返回兜底', () => {
    expect(isKnownRenderKind('preview')).toBe(true);
    expect(isKnownRenderKind('timeline-future')).toBe(false);

    for (const kind of ['table', 'list', 'gallery', 'progress', 'diff', 'form', 'preview']) {
      expect(resolveRenderIntentRenderer(kind)).not.toBe(GenericRenderBlock);
    }
    expect(resolveRenderIntentRenderer('timeline-future')).toBe(GenericRenderBlock);
  });

  it('可以按 kind 覆盖注册渲染器（新增 kind 不改调用方）', () => {
    registerRenderIntentRenderer('timeline-future', ({ intent }) => (
      <div data-custom-kind={intent.kind}>自定义时间线</div>
    ));

    const Renderer = resolveRenderIntentRenderer('timeline-future');
    render(<Renderer intent={{ kind: 'timeline-future', id: 'x' }} />);

    expect(screen.getByText('自定义时间线')).toBeDefined();
    expect(document.querySelector('[data-custom-kind="timeline-future"]')).not.toBeNull();
  });

  it('items 缺失时展示空态，不抛错', () => {
    render(<RenderBlockCard intent={{ kind: 'progress', id: 'p' }} />);
    expect(screen.getByText('暂无可展示内容')).toBeDefined();
  });
});
