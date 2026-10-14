// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MediaNode } from './MediaNode';
import type { CanvasNode } from '../types';

vi.mock('@xyflow/react', () => ({
  Handle: ({ id, type, className, children, isConnectable, position, ...rest }: {
    id?: string;
    type?: string;
    className?: string;
    children?: React.ReactNode;
    isConnectable?: boolean;
    position?: string;
  } & React.HTMLAttributes<HTMLDivElement>) => (
    <div data-testid="node-handle" data-handleid={id} data-handletype={type} data-connectable={String(isConnectable)} className={className} {...rest}>{children}</div>
  ),
  NodeResizer: () => null,
  NodeToolbar: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
  // NodeFloatPanel 用 useStore 复刻「单选本节点才显示」的原生规则；测试里直接当作单选态，
  // 免得每个用例都要先造一份带 selected 的节点表。
  useStore: (selector: (state: { nodes: unknown }) => unknown) =>
    selector({ nodes: { find: () => ({ id: 'node', selected: true }), filter: () => [{}] } }),
  Position: { Bottom: 'bottom', Left: 'left', Right: 'right', Top: 'top' },
  useReactFlow: () => ({ screenToFlowPosition: ({ x, y }: { x: number; y: number }) => ({ x, y }) }),
}));

vi.mock('./nodes/ImageGenNode', () => ({
  ImageGenNode: () => <div>图生图内容</div>,
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
  TextNotePromptNode: () => <div>文字节点内容</div>,
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

  it('renders only the node label when compact display is collapsed', () => {
    render(<MediaNode {...selectedNodeProps} displayCollapsed />);

    expect(screen.getByText('文字节点')).toBeTruthy();
    expect(screen.queryByText('文字节点内容')).toBeNull();
    expect(screen.queryByLabelText('编辑详情')).toBeNull();
  });

  it('offers a minimize action for an individually expanded compact node', () => {
    const onMinimize = vi.fn();
    render(<MediaNode {...selectedNodeProps} showMinimize onMinimize={onMinimize} />);

    screen.getByRole('button', { name: '最小化节点' }).click();

    expect(onMinimize).toHaveBeenCalledWith('text-node-1');
  });

  it('renders the video merge editor inside a video merge node', () => {
    render(<MediaNode
      {...selectedNodeProps}
      id="merge-node"
      data={{
        kind: 'videoMerge',
        label: '视频合并',
        prompt: '',
        status: 'idle',
        accent: '#8f9cff',
      } as CanvasNode['data']}
    />);

    expect(screen.getByText('片段顺序')).toBeTruthy();
    expect(screen.getByRole('button', { name: '合并视频' })).toBeTruthy();
  });
});

describe('MediaNode connection handles', () => {
  afterEach(cleanup);

  // 图生图：2 个输入口（source / reference）+ 1 个输出口（image），用来验证「多端口、单圆钮」。
  const imageToImageProps = {
    ...selectedNodeProps,
    id: 'i2i-node',
    data: {
      kind: 'imageToImage',
      label: '图生图',
      prompt: '',
      status: 'idle',
      accent: '#b48cff',
    } as CanvasNode['data'],
  } as unknown as React.ComponentProps<typeof MediaNode>;

  const listenConnectionEnd = () => {
    const handler = vi.fn();
    window.addEventListener('connection-end', handler);
    return {
      handler,
      stop: () => window.removeEventListener('connection-end', handler),
    };
  };

  it('keeps one Handle per port so edges still anchor, but paints a single + per side', () => {
    const { container } = render(<MediaNode {...imageToImageProps} />);

    const handles = screen.getAllByTestId('node-handle');
    const onSide = (side: string) => handles.filter((node) => node.getAttribute('data-handletype') === side);
    const primaries = (side: string) => onSide(side).filter((node) => !node.className.includes('node-handle--passive'));

    // 端口 Handle 一个都不能少（连线靠它锚定，删了边会断）。
    expect(onSide('target').length).toBeGreaterThan(1);
    expect(onSide('source')).toHaveLength(1);

    // 但每侧只有一个可见圆钮、且只有它是可点击/可连的。
    expect(container.querySelectorAll('.node-handle__plus')).toHaveLength(2);
    expect(primaries('target')).toHaveLength(1);
    expect(primaries('source')).toHaveLength(1);
    expect(onSide('target')[1].className).toContain('node-handle--passive');
    expect(onSide('target')[1].getAttribute('data-connectable')).toBe('false');
  });

  it('dispatches connection-end when a port is clicked without dragging', () => {
    const { handler, stop } = listenConnectionEnd();
    render(<MediaNode {...imageToImageProps} />);

    const primary = screen.getAllByTestId('node-handle')[0];
    fireEvent.pointerDown(primary, { clientX: 100, clientY: 200 });
    fireEvent.pointerUp(primary, { clientX: 101, clientY: 201 });

    expect(handler).toHaveBeenCalledTimes(1);
    const event = handler.mock.calls[0][0] as CustomEvent;
    expect(event.detail).toMatchObject({
      origin: 'click',
      sourceNodeId: 'i2i-node',
      sourceNodeType: 'imageToImage',
      handleType: 'target',
      handleId: primary.getAttribute('data-handleid'),
    });
    stop();
  });

  it('stays silent when the port was dragged instead of clicked', () => {
    const { handler, stop } = listenConnectionEnd();
    render(<MediaNode {...imageToImageProps} />);

    const primary = screen.getAllByTestId('node-handle')[0];
    fireEvent.pointerDown(primary, { clientX: 100, clientY: 200 });
    fireEvent.pointerUp(primary, { clientX: 260, clientY: 200 });

    expect(handler).not.toHaveBeenCalled();
    stop();
  });
});
