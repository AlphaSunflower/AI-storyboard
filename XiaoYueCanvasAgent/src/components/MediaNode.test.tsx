// @vitest-environment jsdom

import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MediaNode } from './MediaNode';
import type { CanvasNode } from '../types';

vi.mock('@xyflow/react', () => ({
  Handle: () => null,
  NodeResizer: () => null,
  NodeToolbar: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
  Position: { Bottom: 'bottom', Left: 'left', Right: 'right', Top: 'top' },
}));

vi.mock('../canvas/CanvasUiContext', () => ({
  useCanvasUi: () => ({ openInspector: vi.fn() }),
}));

vi.mock('../hooks/useNodeTextDraft', () => ({
  useNodeTextDraft: ({ value }: { value: string }) => ({
    value,
    setValue: vi.fn(),
    onFocus: vi.fn(),
    onBlur: vi.fn(),
    onCompositionStart: vi.fn(),
    onCompositionEnd: vi.fn(),
  }),
}));

vi.mock('./nodes/TextNotePromptNode', () => ({
  TextNotePromptNode: () => <div>文字节点</div>,
}));

const selectedNodeProps = {
  id: 'text-node-1',
  selected: true,
  width: 380,
  height: 280,
  positionAbsoluteX: 0,
  positionAbsoluteY: 0,
  data: {
    kind: 'text',
    label: '文字节点',
    prompt: '',
    status: 'idle',
    accent: '#d7ff54',
  } as CanvasNode['data'],
} as unknown as React.ComponentProps<typeof MediaNode>;

describe('MediaNode selection actions', () => {
  afterEach(cleanup);

  it('does not render copy or delete controls for a single selected node', () => {
    render(<MediaNode {...selectedNodeProps} />);

    expect(screen.queryByLabelText('复制节点')).toBeNull();
    expect(screen.queryByLabelText('删除节点')).toBeNull();
  });
});
