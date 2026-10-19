// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CanvasContextMenu } from './CanvasContextMenu';

function renderPaneMenu(overrides: Record<string, unknown> = {}) {
  const props = {
    target: { type: 'pane' as const, clientX: 10, clientY: 10, flowPosition: { x: 0, y: 0 } },
    canPaste: false,
    canUndo: false,
    canRedo: false,
    onClose: vi.fn(),
    onUpload: vi.fn(),
    onOpenAssets: vi.fn(),
    onAddText: vi.fn(),
    onAddImage: vi.fn(),
    onAddNode: vi.fn(),
    onUndo: vi.fn(),
    onRedo: vi.fn(),
    onCopy: vi.fn(),
    onCut: vi.fn(),
    onLayout: vi.fn(),
    onPaste: vi.fn(),
    onDuplicate: vi.fn(),
    onDelete: vi.fn(),
    onSaveToLibrary: vi.fn(),
    onFlattenHistory: vi.fn(),
    onDownload: vi.fn(),
    onCopyImage: vi.fn(),
    canFlattenHistory: false,
    hasImageAsset: false,
    hasMediaAsset: false,
    ...overrides,
  };
  render(<CanvasContextMenu {...props} />);
  return props;
}

describe('CanvasContextMenu pane actions', () => {
  afterEach(() => cleanup());

  it('routes the local-file upload entry to the file picker', () => {
    const props = renderPaneMenu();

    fireEvent.click(screen.getByRole('menuitem', { name: '上传本地文件' }));

    expect(props.onUpload).toHaveBeenCalledTimes(1);
    expect(props.onClose).toHaveBeenCalledTimes(1);
  });

  it('routes the asset upload entry to the project library picker', () => {
    const props = renderPaneMenu();

    fireEvent.click(screen.getByRole('menuitem', { name: '上传资产' }));

    expect(props.onOpenAssets).toHaveBeenCalledTimes(1);
    expect(props.onClose).toHaveBeenCalledTimes(1);
  });

  it('lists the 输入 group without the retired script node', () => {
    renderPaneMenu();

    fireEvent.pointerEnter(screen.getByRole('menuitem', { name: '添加节点' }).closest('.canvas-menu-item-wrap')!);

    const group = screen.getByText('输入').closest('section')!;
    expect(Array.from(group.querySelectorAll('.group-item')).map((item) => item.textContent))
      .toEqual(['文字', '图片', '视频']);
  });

  it('offers grouping for a multi-node selection', () => {
    const onCreateGroup = vi.fn();
    renderPaneMenu({
      target: { type: 'selection' as const, count: 2, clientX: 10, clientY: 10, flowPosition: { x: 0, y: 0 } },
      onCreateGroup,
    });

    fireEvent.click(screen.getByRole('menuitem', { name: /创建分组/ }));

    expect(onCreateGroup).toHaveBeenCalledTimes(1);
  });
});
