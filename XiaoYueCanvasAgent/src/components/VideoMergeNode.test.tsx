// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CanvasNode, PersistedAsset } from '../types';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { VideoMergeNode } from './VideoMergeNode';
import { persistExistingCanvasAsset } from '../services/canvasMedia';
import { processVideoRequest, probeVideoRequest } from '../services/videoProcessingService';
import { createVideoAsset } from '../utils/mediaPipeline';

// 面板走 NodeToolbar（需要 ReactFlowProvider），单测里就地渲染子节点
vi.mock('@xyflow/react', () => ({
  NodeToolbar: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
  Position: { Bottom: 'bottom', Left: 'left', Right: 'right', Top: 'top' },
}));

vi.mock('../services/canvasMedia', () => ({
  persistExistingCanvasAsset: vi.fn(),
}));

vi.mock('../services/videoProcessingService', () => ({
  processVideoRequest: vi.fn(),
  probeVideoRequest: vi.fn(),
}));

vi.mock('../utils/mediaPipeline', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../utils/mediaPipeline')>();
  return {
    ...actual,
    createVideoAsset: vi.fn(),
  };
});

const videoAsset = (id: string, name: string): PersistedAsset => ({
  id,
  kind: 'video',
  name,
  mimeType: 'video/mp4',
  bytes: 100,
  width: 1280,
  height: 720,
  durationSeconds: 4,
  originalBlob: new Blob([id], { type: 'video/mp4' }),
  thumbnailBlob: new Blob(['thumb'], { type: 'image/webp' }),
  previewBlob: new Blob(['preview'], { type: 'image/webp' }),
  libraryState: 'project',
  createdAt: 1,
});

const firstAsset = videoAsset('asset-1', 'one.mp4');
const secondAsset = videoAsset('asset-2', 'two.mp4');
const mergedAsset = videoAsset('merged-server', 'merged.mp4');

const mergeNode: CanvasNode = {
  id: 'merge-node',
  type: 'videoMerge',
  position: { x: 500, y: 0 },
  width: 560,
  height: 440,
  data: {
    kind: 'videoMerge',
    label: '视频合并',
    prompt: '',
    status: 'idle',
    accent: '#8f9cff',
  },
};

describe('VideoMergeNode', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMediaStore.setState({
      assets: { [firstAsset.id]: firstAsset, [secondAsset.id]: secondAsset },
      metadata: { [firstAsset.id]: firstAsset, [secondAsset.id]: secondAsset },
      assetErrors: {},
      quality: 'preview',
    });
    useCanvasStore.setState({
      projectId: 'project-1',
      canvasId: 'canvas-1',
      nodes: [
        { id: 'video-1', type: 'video', position: { x: 0, y: 0 }, data: { ...videoAsset('asset-1', 'one.mp4'), kind: 'video', label: '片段 1', prompt: '', status: 'success', accent: '#8f9cff', assetId: 'asset-1' } },
        { id: 'video-2', type: 'video', position: { x: 0, y: 300 }, data: { ...videoAsset('asset-2', 'two.mp4'), kind: 'video', label: '片段 2', prompt: '', status: 'success', accent: '#8f9cff', assetId: 'asset-2' } },
        mergeNode,
      ],
      edges: [
        { id: 'edge-1', source: 'video-1', sourceHandle: 'video', target: 'merge-node', targetHandle: 'clips', type: 'canvas', data: { portOrder: 0 } },
        { id: 'edge-2', source: 'video-2', sourceHandle: 'video', target: 'merge-node', targetHandle: 'clips', type: 'canvas', data: { portOrder: 1 } },
      ],
      generationRecords: [],
      executions: [],
      past: [],
      future: [],
      selectedNodeId: mergeNode.id,
      revision: 0,
    });
    vi.mocked(probeVideoRequest).mockResolvedValue({
      width: 1280,
      height: 720,
      fps: 30,
      durationSeconds: 4,
      hasAudio: true,
    });
  });

  afterEach(cleanup);

  it('lists clips in port order and moves an input up atomically', () => {
    render(<VideoMergeNode node={mergeNode} />);

    expect(screen.getByText('片段 1')).toBeTruthy();
    expect(screen.getByText('片段 2')).toBeTruthy();
    fireEvent.click(screen.getByLabelText('上移 片段 2'));

    const ordered = useCanvasStore.getState().edges
      .filter((edge) => edge.target === mergeNode.id)
      .sort((left, right) => (left.data?.portOrder ?? 0) - (right.data?.portOrder ?? 0))
      .map((edge) => edge.source);
    expect(ordered).toEqual(['video-2', 'video-1']);
  });

  it('merges ordered clips and creates a connected video output node', async () => {
    vi.mocked(processVideoRequest).mockResolvedValue({
      blob: new Blob(['merged'], { type: 'video/mp4' }),
      fileName: 'merged.mp4',
    });
    vi.mocked(createVideoAsset).mockResolvedValue({ ...mergedAsset, operation: { type: 'videoMerge', params: {} } });
    vi.mocked(persistExistingCanvasAsset).mockResolvedValue({ ...mergedAsset, operation: { type: 'videoMerge', params: {} } });

    render(<VideoMergeNode node={mergeNode} />);
    fireEvent.click(screen.getByRole('button', { name: '合并视频' }));

    await waitFor(() => expect(useCanvasStore.getState().nodes).toHaveLength(4));
    expect(processVideoRequest).toHaveBeenCalledWith(expect.objectContaining({
      type: 'merge',
      clips: [
        expect.objectContaining({ fileName: 'one.mp4', width: 1280, height: 720, fps: 30, hasAudio: true }),
        expect.objectContaining({ fileName: 'two.mp4', width: 1280, height: 720, fps: 30, hasAudio: true }),
      ],
    }), expect.any(Object));
    const output = useCanvasStore.getState().nodes[3];
    expect(output.data).toMatchObject({ kind: 'video', assetId: 'merged-server', label: '视频合并 · 合并结果' });
    expect(useCanvasStore.getState().edges.at(-1)).toMatchObject({
      source: mergeNode.id,
      sourceHandle: 'video',
      target: output.id,
      data: { operation: 'videoMerge', operationDetail: '合并' },
    });
  });

  it('does not create an output when probing or merging fails', async () => {
    vi.mocked(probeVideoRequest).mockRejectedValue(new Error('无法读取片段'));

    render(<VideoMergeNode node={mergeNode} />);
    fireEvent.click(screen.getByRole('button', { name: '合并视频' }));

    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('无法读取片段'));
    expect(useCanvasStore.getState().nodes).toHaveLength(3);
    expect(processVideoRequest).not.toHaveBeenCalled();
  });
});
