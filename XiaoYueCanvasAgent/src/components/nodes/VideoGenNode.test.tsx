// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { VideoGenNode } from './VideoGenNode';
import type { CanvasNode } from '../../types';
import type { ReferenceImagePoolResult } from '../../canvas/referenceImagePool';
import { useCanvasStore } from '../../store/canvasStore';
import { useMediaStore } from '../../store/mediaStore';

const { submitVideoGeneration } = vi.hoisted(() => ({ submitVideoGeneration: vi.fn() }));

vi.mock('../../services/jobRunner', () => ({ submitVideoGeneration }));

vi.mock('@xyflow/react', () => ({
  NodeToolbar: ({ children, className, isVisible }: { children?: React.ReactNode; className?: string; isVisible?: boolean }) =>
    isVisible === false ? null : <div className={className}>{children}</div>,
  Position: { Top: 'top', Bottom: 'bottom' },
  useStore: (selector: (state: { nodes: unknown }) => unknown) =>
    selector({ nodes: { find: () => ({ id: 'node', selected: true }), filter: () => [{}] } }),
}));

vi.mock('../RichPromptInput', () => ({
  RichPromptInput: ({ pool }: { pool: ReferenceImagePoolResult }) =>
    <div data-testid="prompt-pool-count">{pool.count}</div>,
}));

const emptyPool: ReferenceImagePoolResult = {
  entries: [],
  count: 0,
  getByNumber: () => undefined,
  getByAssetId: () => undefined,
  assetIds: [],
  isValidNumber: () => false,
};

const poolWith = (entries: Array<{ number: number; assetId: string; sourceNodeId: string; sourceNodeName: string }>): ReferenceImagePoolResult => ({
  entries: entries.map((entry) => ({ ...entry, mediaType: 'image' as const, source: 'upstream' as const })),
  count: entries.length,
  getByNumber: (number) => entries.find((entry) => entry.number === number) as never,
  getByAssetId: (assetId) => entries.find((entry) => entry.assetId === assetId) as never,
  assetIds: entries.map((entry) => entry.assetId),
  isValidNumber: (number) => number >= 1 && number <= entries.length,
});

const data = (overrides: Partial<CanvasNode['data']> = {}): CanvasNode['data'] => ({
  kind: 'videoGeneration',
  label: '视频生成',
  prompt: '',
  status: 'idle',
  accent: '#8f9cff',
  ...overrides,
});

const renderNode = (nodeData: CanvasNode['data'], pool = emptyPool, updateNode = vi.fn()) => render(
  <VideoGenNode
    id="video-node"
    data={nodeData}
    updateNode={updateNode}
    textDraft=""
    setTextDraft={vi.fn()}
    referencePool={pool}
    deleteReference={vi.fn()}
    draftFeedback={null}
    setDraftFeedback={vi.fn()}
  />,
);

describe('VideoGenNode modes', () => {
  afterEach(() => {
    cleanup();
    submitVideoGeneration.mockReset();
    useCanvasStore.setState({ nodes: [], edges: [], generationRecords: [] });
    useMediaStore.setState({ assets: {} });
  });

  it('shows the four merged modes and switches data.mode', () => {
    const updateNode = vi.fn();
    renderNode(data(), emptyPool, updateNode);

    ['文生视频', '全能参考', '首尾帧', '视频参考'].forEach((label) => {
      expect(screen.getByRole('button', { name: label })).toBeTruthy();
    });
    fireEvent.click(screen.getByRole('button', { name: '首尾帧' }));
    expect(updateNode).toHaveBeenCalledWith('video-node', { mode: 'firstLastFrame' });
  });

  it('renders the two fixed slots for the first-last-frame mode', () => {
    renderNode(data({ mode: 'firstLastFrame' }));

    expect(screen.getByText('首帧')).toBeTruthy();
    expect(screen.getByText('尾帧')).toBeTruthy();
    // 首尾帧只有两个槽，没有「＋」追加更多参考
    expect(screen.queryByLabelText('添加参考素材')).toBeNull();
  });

  it('offers the add-reference control in the full reference mode', () => {
    renderNode(data({ mode: 'fullReference' }));

    expect(screen.getByLabelText('添加参考素材')).toBeTruthy();
  });

  it('maps ordered upstream images to first and last frame roles', () => {
    useCanvasStore.setState({
      nodes: [
        { id: 'video-node', type: 'videoGeneration', position: { x: 500, y: 200 }, data: data({ mode: 'firstLastFrame' }) },
        { id: 'first-image', type: 'image', position: { x: 100, y: 100 }, data: { kind: 'image', label: '首帧', prompt: '', status: 'success', accent: '#f00', assetId: 'a1' } },
        { id: 'last-image', type: 'image', position: { x: 100, y: 300 }, data: { kind: 'image', label: '尾帧', prompt: '', status: 'success', accent: '#f00', assetId: 'a2' } },
      ],
      edges: [
        { id: 'e1', source: 'first-image', sourceHandle: 'image', target: 'video-node', targetHandle: 'reference', type: 'canvas', data: { portOrder: 0 } },
        { id: 'e2', source: 'last-image', sourceHandle: 'image', target: 'video-node', targetHandle: 'reference', type: 'canvas', data: { portOrder: 1 } },
      ],
    });
    const pool = poolWith([
      { number: 1, assetId: 'a1', sourceNodeId: 'first-image', sourceNodeName: '首帧图' },
      { number: 2, assetId: 'a2', sourceNodeId: 'last-image', sourceNodeName: '尾帧图' },
    ]);
    submitVideoGeneration.mockResolvedValue('job-1');

    renderNode(data({ mode: 'firstLastFrame' }), pool);
    fireEvent.click(screen.getByRole('button', { name: '生成视频' }));

    expect(submitVideoGeneration).toHaveBeenCalledWith(
      '',
      'video-node',
      expect.any(Function),
      expect.objectContaining({
        sourceAssetIds: ['a1', 'a2'],
        params: expect.objectContaining({
          videoMode: 'firstLastFrame',
          referenceRoles: [
            { assetId: 'a1', role: 'FIRST_FRAME' },
            { assetId: 'a2', role: 'LAST_FRAME' },
          ],
        }),
      }),
    );
  });

  it('hands reference images over as plain references in the full reference mode', () => {
    useCanvasStore.setState({
      nodes: [
        { id: 'video-node', type: 'videoGeneration', position: { x: 500, y: 200 }, data: data({ mode: 'fullReference' }) },
        { id: 'ref-image', type: 'image', position: { x: 100, y: 100 }, data: { kind: 'image', label: '参考图', prompt: '', status: 'success', accent: '#f00', assetId: 'a1' } },
      ],
      edges: [
        { id: 'e1', source: 'ref-image', sourceHandle: 'image', target: 'video-node', targetHandle: 'reference', type: 'canvas', data: { portOrder: 0 } },
      ],
    });
    const pool = poolWith([{ number: 1, assetId: 'a1', sourceNodeId: 'ref-image', sourceNodeName: '参考图' }]);
    submitVideoGeneration.mockResolvedValue('job-2');

    renderNode(data({ mode: 'fullReference' }), pool);
    fireEvent.click(screen.getByRole('button', { name: '生成视频' }));

    expect(submitVideoGeneration).toHaveBeenCalledWith(
      '',
      'video-node',
      expect.any(Function),
      expect.objectContaining({
        params: expect.objectContaining({
          videoMode: 'fullReference',
          referenceRoles: [{ assetId: 'a1', role: 'REFERENCE' }],
        }),
      }),
    );
  });

  it('exposes the duration / resolution / ratio controls without output format or bitrate', () => {
    renderNode(data({ mode: 'text2video' }));

    fireEvent.click(screen.getByLabelText('参数设置'));

    expect(screen.getByLabelText('时长')).toBeTruthy();
    expect(screen.getByRole('group', { name: '分辨率' })).toBeTruthy();
    expect(screen.getByRole('group', { name: '宽高比' })).toBeTruthy();
    fireEvent.click(screen.getByLabelText('参数设置'));
    expect(screen.queryByLabelText('输出格式')).toBeNull();
    expect(screen.queryByLabelText('码率档位')).toBeNull();
  });
});
