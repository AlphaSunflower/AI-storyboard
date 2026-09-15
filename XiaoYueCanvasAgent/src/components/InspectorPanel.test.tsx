// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { InspectorPanel } from './InspectorPanel';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import type { CanvasNode, MediaAsset } from '../types';

const imageNode: CanvasNode = {
  id: 'image-1',
  type: 'image',
  position: { x: 0, y: 0 },
  data: {
    kind: 'image',
    label: '主视觉',
    prompt: '',
    status: 'success',
    accent: '#ff775f',
    assetId: 'image-asset',
  },
};

const asset: MediaAsset = {
  id: 'image-asset',
  kind: 'image',
  name: '主视觉.png',
  mimeType: 'image/png',
  bytes: 4,
  width: 1200,
  height: 800,
  originalBlob: new Blob(['original'], { type: 'image/png' }),
  thumbnailBlob: new Blob(['thumbnail'], { type: 'image/webp' }),
  previewBlob: new Blob(['preview'], { type: 'image/webp' }),
  libraryState: 'project',
  createdAt: 1,
};

describe('InspectorPanel image preview', () => {
  beforeEach(() => {
    useCanvasStore.setState({
      nodes: [imageNode],
      selectedNodeId: imageNode.id,
    });
    useMediaStore.getState().clearAssets();
    useMediaStore.getState().upsertAsset(asset);
  });

  afterEach(() => {
    cleanup();
    useCanvasStore.setState({ nodes: [], selectedNodeId: null });
    useMediaStore.getState().clearAssets();
  });

  it('opens the selected image node in a full preview from node properties', () => {
    render(<InspectorPanel />);

    fireEvent.click(screen.getByRole('button', { name: '预览图片：主视觉' }));

    const dialog = screen.getByRole('dialog', { name: '图片预览' });
    expect(within(dialog).getByRole('img', { name: '主视觉' })).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: '关闭预览' }));
    expect(screen.queryByRole('dialog', { name: '图片预览' })).toBeNull();
  });

  it('shows first and last frame image properties for first-last video nodes', () => {
    const firstFrame = { ...asset, id: 'first-frame', name: '首帧.png' };
    const lastFrame = { ...asset, id: 'last-frame', name: '尾帧.png' };
    const videoNode: CanvasNode = {
      id: 'video-1',
      type: 'firstLastFrameToVideo',
      position: { x: 0, y: 0 },
      data: {
        kind: 'firstLastFrameToVideo',
        label: '首尾帧生视频',
        prompt: '',
        status: 'idle',
        accent: '#8f9cff',
        videoMode: 'firstLastFrame',
        firstFrameAssetId: firstFrame.id,
        lastFrameAssetId: lastFrame.id,
      },
    };
    useCanvasStore.setState({ nodes: [videoNode], selectedNodeId: videoNode.id });
    useMediaStore.getState().upsertAssets([firstFrame, lastFrame]);

    render(<InspectorPanel />);

    expect(screen.getByText('首帧图片')).toBeTruthy();
    expect(screen.getByText('尾帧图片')).toBeTruthy();
    expect(screen.getByRole('img', { name: '首帧图片' })).toBeTruthy();
    expect(screen.getByRole('img', { name: '尾帧图片' })).toBeTruthy();
  });
});
