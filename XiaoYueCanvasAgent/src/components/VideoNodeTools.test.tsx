// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CanvasNode, PersistedAsset } from '../types';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { VideoNodeTools } from './VideoNodeTools';
import { processVideoRequest } from '../services/videoProcessingService';
import { persistExistingCanvasAsset } from '../services/canvasMedia';
import { createImageAsset, createVideoAsset } from '../utils/mediaPipeline';
import { getAsset } from '../services/projectStorage';

vi.mock('@xyflow/react', () => ({
  NodeToolbar: ({ children, ...props }: React.HTMLAttributes<HTMLDivElement>) => <div {...props}>{children}</div>,
  Position: { Top: 'top', Bottom: 'bottom' },
}));

vi.mock('../services/videoProcessingService', () => ({
  processVideoRequest: vi.fn(),
}));

vi.mock('../services/canvasMedia', () => ({
  persistExistingCanvasAsset: vi.fn(),
}));

vi.mock('../services/projectStorage', () => ({
  getAsset: vi.fn(),
  saveAssetToLibrary: vi.fn(async () => undefined),
}));

vi.mock('../utils/mediaPipeline', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../utils/mediaPipeline')>();
  return {
    ...actual,
    createImageAsset: vi.fn(),
    createVideoAsset: vi.fn(),
  };
});

const sourceAsset: PersistedAsset = {
  id: 'video-source',
  kind: 'video',
  name: 'source.mp4',
  mimeType: 'video/mp4',
  bytes: 100,
  width: 1280,
  height: 720,
  durationSeconds: 10,
  originalBlob: new Blob(['source'], { type: 'video/mp4' }),
  thumbnailBlob: new Blob(['thumb'], { type: 'image/webp' }),
  previewBlob: new Blob(['preview'], { type: 'image/webp' }),
  libraryState: 'project',
  createdAt: 1,
};

const videoNode: CanvasNode = {
  id: 'video-node',
  type: 'video',
  position: { x: 0, y: 0 },
  width: 420,
  height: 236,
  data: {
    kind: 'video',
    label: '测试视频',
    prompt: '',
    status: 'success',
    accent: '#8f9cff',
    assetId: sourceAsset.id,
  },
};

const persistedResult = (kind: 'image' | 'video', id: string): PersistedAsset => ({
  ...sourceAsset,
  id,
  kind,
  name: kind === 'image' ? 'frame.png' : 'flipped.mp4',
  mimeType: kind === 'image' ? 'image/png' : 'video/mp4',
  originalBlob: new Blob([kind], { type: kind === 'image' ? 'image/png' : 'video/mp4' }),
});

describe('VideoNodeTools', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMediaStore.setState({
      assets: { [sourceAsset.id]: sourceAsset },
      metadata: { [sourceAsset.id]: sourceAsset },
      assetErrors: {},
      quality: 'preview',
    });
    useCanvasStore.setState({
      projectId: 'project-1',
      canvasId: 'canvas-1',
      nodes: [videoNode],
      edges: [],
      generationRecords: [],
      executions: [],
      past: [],
      future: [],
      selectedNodeId: videoNode.id,
      revision: 0,
    });
  });

  afterEach(cleanup);

  it('overwrites the video node after a successful horizontal flip', async () => {
    const local = persistedResult('video', 'local-flip');
    const persisted = persistedResult('video', 'server-flip');
    vi.mocked(processVideoRequest).mockResolvedValue({ blob: local.originalBlob, fileName: 'flipped.mp4' });
    vi.mocked(createVideoAsset).mockResolvedValue(local);
    vi.mocked(persistExistingCanvasAsset).mockResolvedValue(persisted);

    render(<VideoNodeTools node={videoNode} />);
    fireEvent.click(screen.getByTitle('水平翻转'));

    await waitFor(() => expect(useCanvasStore.getState().nodes[0].data.assetId).toBe('server-flip'));
    expect(processVideoRequest).toHaveBeenCalledWith(expect.objectContaining({ type: 'flip' }), expect.any(Object));
    expect(createVideoAsset).toHaveBeenCalledWith(expect.any(File), expect.any(String), expect.objectContaining({
      derivedFrom: sourceAsset.id,
      operation: expect.objectContaining({ type: 'videoFlip' }),
    }));
  });

  it('creates a connected image node for an extracted first frame', async () => {
    const local = persistedResult('image', 'local-frame');
    const persisted = persistedResult('image', 'server-frame');
    vi.mocked(processVideoRequest).mockResolvedValue({ blob: local.originalBlob, fileName: 'frame.png' });
    vi.mocked(createImageAsset).mockResolvedValue(local);
    vi.mocked(persistExistingCanvasAsset).mockResolvedValue(persisted);

    render(<VideoNodeTools node={videoNode} />);
    fireEvent.click(screen.getByTitle('提取帧'));
    fireEvent.click(screen.getByRole('button', { name: '首帧' }));

    await waitFor(() => expect(useCanvasStore.getState().nodes).toHaveLength(2));
    const resultNode = useCanvasStore.getState().nodes[1];
    const edge = useCanvasStore.getState().edges[0];
    expect(resultNode.data).toMatchObject({ assetId: 'server-frame', label: '测试视频 · 首帧' });
    expect(edge).toMatchObject({
      source: videoNode.id,
      sourceHandle: 'video',
      targetHandle: 'origin',
      data: { operation: 'videoFrame', operationDetail: '首帧' },
    });
  });

  it('keeps the original video when processing fails', async () => {
    vi.mocked(processVideoRequest).mockRejectedValue(new Error('FFmpeg 内存不足'));

    render(<VideoNodeTools node={videoNode} />);
    fireEvent.click(screen.getByTitle('水平翻转'));

    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('FFmpeg 内存不足'));
    expect(useCanvasStore.getState().nodes[0].data.assetId).toBe(sourceAsset.id);
    expect(persistExistingCanvasAsset).not.toHaveBeenCalled();
  });

  it('allows changing an already speed-adjusted video back to 1.0x', async () => {
    const originalAsset = {
      ...sourceAsset,
      id: 'speed-source',
      name: 'original.mp4',
      originalBlob: new Blob(['original'], { type: 'video/mp4' }),
    };
    const speedAsset = {
      ...sourceAsset,
      id: 'speed-result',
      derivedFrom: originalAsset.id,
      operation: {
        type: 'videoSpeed' as const,
        params: { factor: 1.68, sourceAssetId: originalAsset.id, sourceDurationSeconds: 10 },
      },
    };
    const speedNode = {
      ...videoNode,
      data: { ...videoNode.data, assetId: speedAsset.id, operation: speedAsset.operation },
    };
    useMediaStore.setState({
      assets: { [speedAsset.id]: speedAsset, [originalAsset.id]: originalAsset },
      metadata: { [speedAsset.id]: speedAsset, [originalAsset.id]: originalAsset },
      assetErrors: {},
      quality: 'preview',
    });
    useCanvasStore.setState({ nodes: [speedNode] });
    const local = persistedResult('video', 'local-normal-speed');
    const persisted = persistedResult('video', 'server-normal-speed');
    vi.mocked(processVideoRequest).mockResolvedValue({ blob: local.originalBlob, fileName: 'normal.mp4' });
    vi.mocked(createVideoAsset).mockResolvedValue(local);
    vi.mocked(persistExistingCanvasAsset).mockResolvedValue(persisted);
    vi.mocked(getAsset).mockResolvedValue(originalAsset);

    render(<VideoNodeTools node={speedNode} />);
    fireEvent.click(screen.getByTitle('变速'));
    fireEvent.click(screen.getByRole('button', { name: /1\.0×/ }));
    const overwrite = screen.getByRole('button', { name: '覆盖原视频' });
    expect(overwrite.hasAttribute('disabled')).toBe(false);
    fireEvent.click(overwrite);

    await waitFor(() => expect(processVideoRequest).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'speed', factor: 1, source: originalAsset.originalBlob }),
      expect.any(Object),
    ));
    expect(createVideoAsset).toHaveBeenCalledWith(expect.any(File), expect.any(String), expect.objectContaining({
      operation: expect.objectContaining({
        type: 'videoSpeed',
        params: expect.objectContaining({
          factor: 1,
          sourceAssetId: originalAsset.id,
          sourceDurationSeconds: 10,
        }),
      }),
    }));
  });

  it('scrubs the node video and extracts the selected arbitrary frame', async () => {
    const host = document.createElement('div');
    host.className = 'react-flow__node';
    host.dataset.id = videoNode.id;
    const video = document.createElement('video');
    host.appendChild(video);
    document.body.appendChild(host);
    const local = persistedResult('image', 'local-custom-frame');
    const persisted = persistedResult('image', 'server-custom-frame');
    vi.mocked(processVideoRequest).mockResolvedValue({ blob: local.originalBlob, fileName: 'frame.png' });
    vi.mocked(createImageAsset).mockResolvedValue(local);
    vi.mocked(persistExistingCanvasAsset).mockResolvedValue(persisted);

    render(<VideoNodeTools node={videoNode} />);
    fireEvent.click(screen.getByTitle('提取帧'));
    const scrubber = screen.getByLabelText('任意帧') as HTMLInputElement;
    expect(scrubber.step).toBe('0.01');
    fireEvent.change(scrubber, { target: { value: '5.25' } });

    expect(video.currentTime).toBe(5.25);
    fireEvent.click(screen.getByRole('button', { name: '提取任意帧' }));
    await waitFor(() => expect(processVideoRequest).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'extractFrame',
        position: { kind: 'custom', timestampSeconds: 5.25 },
      }),
      expect.any(Object),
    ));
    host.remove();
  });

  it('resets pending color adjustments without changing the video', () => {
    render(<VideoNodeTools node={videoNode} />);
    fireEvent.click(screen.getByTitle('调色'));
    fireEvent.change(screen.getByLabelText('亮度'), { target: { value: '24' } });
    fireEvent.change(screen.getByLabelText('色温'), { target: { value: '-30' } });
    fireEvent.click(screen.getByRole('button', { name: /恢复默认值/ }));

    expect((screen.getByLabelText('亮度') as HTMLInputElement).value).toBe('0');
    expect((screen.getByLabelText('色温') as HTMLInputElement).value).toBe('0');
    expect(useCanvasStore.getState().nodes[0].data.assetId).toBe(sourceAsset.id);
  });
});
