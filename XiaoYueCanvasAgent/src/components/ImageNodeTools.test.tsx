// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CanvasNode, PersistedAsset } from '../types';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { ImageNodeTools } from './ImageNodeTools';

const { submitImageMultiAngle, useGatewayModels } = vi.hoisted(() => ({
  submitImageMultiAngle: vi.fn(),
  useGatewayModels: vi.fn(),
}));

vi.mock('@xyflow/react', () => ({
  NodeToolbar: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
  Position: { Top: 'top', Bottom: 'bottom' },
  useReactFlow: () => ({ fitView: vi.fn() }),
}));

vi.mock('../services/jobRunner', () => ({
  submitImageMultiAngle,
}));

vi.mock('../hooks/useGatewayModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../hooks/useGatewayModels')>();
  return { ...actual, useGatewayModels };
});

const sourceAsset: PersistedAsset = {
  id: 'source-asset',
  kind: 'image',
  name: 'character.png',
  mimeType: 'image/png',
  bytes: 100,
  width: 1024,
  height: 1024,
  originalBlob: new Blob(['source'], { type: 'image/png' }),
  thumbnailBlob: new Blob(['thumb'], { type: 'image/png' }),
  previewBlob: new Blob(['preview'], { type: 'image/png' }),
  libraryState: 'project',
  createdAt: 1,
};

const imageNode: CanvasNode = {
  id: 'image-node',
  type: 'media',
  position: { x: 0, y: 0 },
  width: 320,
  height: 320,
  data: {
    kind: 'image',
    label: '角色图',
    prompt: '',
    status: 'success',
    accent: '#ff775f',
    assetId: sourceAsset.id,
  },
};

describe('ImageNodeTools multi-angle', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useGatewayModels.mockReturnValue({
      models: [{ value: 'doubao-seedream-4-5-251128', label: 'Seedream 4.5' }],
      defaultModel: 'doubao-seedream-4-5-251128',
      loading: false,
      error: null,
    });
    submitImageMultiAngle.mockResolvedValue('job-angle');
    useMediaStore.setState({
      assets: { [sourceAsset.id]: sourceAsset },
      metadata: { [sourceAsset.id]: sourceAsset },
      assetErrors: {},
      quality: 'preview',
    });
    useCanvasStore.setState({
      projectId: 'project',
      canvasId: 'canvas',
      nodes: [imageNode],
      edges: [],
      generationRecords: [],
      executions: [],
      past: [],
      future: [],
      selectedNodeId: imageNode.id,
      revision: 0,
      derivedAssetIdsBySource: {},
    });
  });

  afterEach(cleanup);

  it('creates a connected multi-angle node and submits the fixed model request', async () => {
    render(<ImageNodeTools node={imageNode} />);

    fireEvent.click(screen.getByTitle('多角度'));
    expect(screen.getByRole('dialog', { name: '多角度' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '生成多角度' }));

    await waitFor(() => expect(useCanvasStore.getState().nodes).toHaveLength(2));
    const target = useCanvasStore.getState().nodes[1];
    expect(target.data).toMatchObject({
      // 多角度并入合并后的「图片生成」节点，落在图生图模式
      kind: 'imageGeneration',
      mode: 'image2image',
      label: '角色图 · 多角度',
      multiAngle: {
        multiAngleMode: 'grid',
        model: 'doubao-seedream-4-5-251128',
        size: '2K',
        n: 1,
      },
    });
    expect(useCanvasStore.getState().edges[0]).toMatchObject({
      source: imageNode.id,
      target: target.id,
    });
    expect(submitImageMultiAngle).toHaveBeenCalledWith(
      target.data.prompt,
      target.id,
      target.data.multiAngle,
      expect.any(Function),
      expect.objectContaining({ sourceAssetIds: [sourceAsset.id] }),
    );
  });
});
