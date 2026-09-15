// @vitest-environment jsdom

import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { VideoNode } from './VideoNode';
import { useMediaStore } from '../store/mediaStore';
import type { CanvasNode, PersistedAsset } from '../types';

vi.mock('@xyflow/react', () => ({
  Handle: () => null,
  NodeResizer: () => null,
  NodeToolbar: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
  Position: { Left: 'left', Right: 'right' },
  useStore: (selector: (state: { transform: [number, number, number]; width: number; height: number }) => unknown) =>
    selector({ transform: [0, 0, 1], width: 900, height: 700 }),
}));

const videoAsset: PersistedAsset = {
  id: 'video-asset-1',
  kind: 'video',
  name: 'result.mp4',
  mimeType: 'video/mp4',
  bytes: 3,
  width: 640,
  height: 360,
  durationSeconds: 4,
  originalBlob: new Blob(['mp4'], { type: 'video/mp4' }),
  thumbnailBlob: new Blob(['jpg'], { type: 'image/jpeg' }),
  previewBlob: new Blob(['jpg'], { type: 'image/jpeg' }),
  operation: { type: 'remote', params: {} },
  libraryState: 'project',
  createdAt: 1,
};

const nodeProps = {
  id: 'video-node-1',
  selected: true,
  height: 236,
  positionAbsoluteY: 0,
  data: {
    kind: 'video',
    label: '视频结果',
    prompt: '',
    status: 'success',
    accent: '#8f9cff',
    assetId: videoAsset.id,
  } as CanvasNode['data'],
} as unknown as React.ComponentProps<typeof VideoNode>;

describe('VideoNode', () => {
  afterEach(cleanup);

  it('keeps playback controls but removes node toolbars and editor panels', () => {
    useMediaStore.setState({
      assets: { [videoAsset.id]: videoAsset },
      metadata: { [videoAsset.id]: videoAsset },
    });

    render(<VideoNode {...nodeProps} />);

    expect(screen.getAllByLabelText('播放').length).toBeGreaterThan(0);
    expect(screen.queryByTitle('提取当前帧')).toBeNull();
    expect(screen.queryByTitle('下载视频')).toBeNull();
    expect(screen.queryByText('分镜提示词')).toBeNull();
    expect(screen.queryByText('生成参数')).toBeNull();
    expect(screen.queryByText('Playlist')).toBeNull();
  });
});
