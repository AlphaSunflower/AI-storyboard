// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TextNotePromptNode } from './TextNotePromptNode';
import type { CanvasNode } from '../../types';

const baseData = {
  kind: 'text',
  label: '文字',
  prompt: '已有正文',
  status: 'idle',
  accent: '#d7ff54',
} as CanvasNode['data'];

function renderNode(patch: Record<string, unknown> = {}, updateNode = vi.fn()) {
  const data = { ...baseData, ...patch } as CanvasNode['data'];
  render(<TextNotePromptNode id="node-1" data={data} updateNode={updateNode} />);
  return updateNode;
}

const preview = () => screen.getByLabelText('文字内容');
const editor = () => screen.getByLabelText('文字内容');

describe('TextNotePromptNode 静态预览面', () => {
  afterEach(cleanup);

  it('默认渲染可拖动的预览面而不是常驻输入框', () => {
    renderNode();

    const surface = preview();
    expect(surface.tagName).toBe('DIV');
    expect(surface.textContent).toBe('已有正文');
  });

  it('预览面不带 nodrag，节点正文才能按下拖动', () => {
    renderNode();

    expect(preview().className).not.toContain('nodrag');
  });

  it('内容为空时显示占位提示', () => {
    renderNode({ prompt: '' });

    expect(preview().textContent).toBe('在此输入文字…');
    expect(preview().className).toContain('is-empty');
  });

  it('按 kind 落到各自的字段与提示词', () => {
    renderNode({ kind: 'prompt', promptText: '一段提示词' });

    expect(screen.getByLabelText('提示词内容').textContent).toBe('一段提示词');
  });
});

describe('TextNotePromptNode 双击进入编辑', () => {
  afterEach(cleanup);

  it('双击预览面切换成 textarea 并聚焦', () => {
    renderNode();

    fireEvent.doubleClick(preview());

    expect(editor().tagName).toBe('TEXTAREA');
    expect(document.activeElement).toBe(editor());
  });

  it('编辑态 textarea 带 nodrag，输入时不会拖动节点', () => {
    renderNode();

    fireEvent.doubleClick(preview());

    expect(editor().className).toContain('nodrag');
  });

  it('确认双击不冒泡，避免同时触发画布的双击行为', () => {
    const onAncestorDoubleClick = vi.fn();
    render(
      <div onDoubleClick={onAncestorDoubleClick}>
        <TextNotePromptNode id="node-1" data={baseData} updateNode={vi.fn()} />
      </div>,
    );

    fireEvent.doubleClick(preview());

    expect(onAncestorDoubleClick).not.toHaveBeenCalled();
  });

  it('失焦退出编辑态并把新正文写回节点', () => {
    const updateNode = renderNode();

    fireEvent.doubleClick(preview());
    fireEvent.change(editor(), { target: { value: '改过的正文' } });
    fireEvent.blur(editor());

    expect(preview().tagName).toBe('DIV');
    expect(preview().textContent).toBe('改过的正文');
    expect(updateNode).toHaveBeenCalledWith('node-1', { prompt: '改过的正文' });
  });

  it('按 Esc 退出编辑态并保留已写内容', () => {
    const updateNode = renderNode();

    fireEvent.doubleClick(preview());
    fireEvent.change(editor(), { target: { value: 'Esc 前输入' } });
    fireEvent.keyDown(editor(), { key: 'Escape' });

    expect(preview().tagName).toBe('DIV');
    expect(updateNode).toHaveBeenCalledWith('node-1', { prompt: 'Esc 前输入' });
  });

  it('外部改写节点正文时会同步回预览面', () => {
    const { rerender } = render(
      <TextNotePromptNode id="node-1" data={baseData} updateNode={vi.fn()} />,
    );

    fireEvent.doubleClick(preview());
    fireEvent.blur(editor());

    rerender(
      <TextNotePromptNode id="node-1" data={{ ...baseData, prompt: '外部改写' } as CanvasNode['data']} updateNode={vi.fn()} />,
    );

    expect(preview().textContent).toBe('外部改写');
  });

  // Esc 和失焦是两条独立出口，各自都要放掉草稿的 focusedRef。
  // 只测失焦的话，将来有人把 flushDraft() 从 Esc 分支删掉不会红。
  it('按 Esc 退出后，外部改写同样能同步回预览面', () => {
    const { rerender } = render(
      <TextNotePromptNode id="node-1" data={baseData} updateNode={vi.fn()} />,
    );

    fireEvent.doubleClick(preview());
    fireEvent.keyDown(editor(), { key: 'Escape' });

    rerender(
      <TextNotePromptNode id="node-1" data={{ ...baseData, prompt: 'Esc 后外部改写' } as CanvasNode['data']} updateNode={vi.fn()} />,
    );

    expect(preview().textContent).toBe('Esc 后外部改写');
  });

  it('按 Esc 把焦点交还给预览面，不落到 body', () => {
    renderNode();

    fireEvent.doubleClick(preview());
    fireEvent.keyDown(editor(), { key: 'Escape' });

    expect(document.activeElement).toBe(preview());
  });

  it('失焦退出时不抢回焦点（用户已经点到别处）', () => {
    renderNode();
    const outside = document.createElement('button');
    document.body.appendChild(outside);

    fireEvent.doubleClick(preview());
    outside.focus();
    fireEvent.blur(editor());

    expect(document.activeElement).toBe(outside);
    outside.remove();
  });
});
