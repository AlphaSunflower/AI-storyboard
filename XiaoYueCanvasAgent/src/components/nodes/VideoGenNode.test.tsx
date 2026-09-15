// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { VideoGenNode } from './VideoGenNode';
import type { CanvasNode, PersistedAsset } from '../../types';
import type { ReferenceImagePoolResult } from '../../canvas/referenceImagePool';
import { useMediaStore } from '../../store/mediaStore';
import { useCanvasStore } from '../../store/canvasStore';

const mocks = vi.hoisted(() => ({ getAsset: vi.fn() }));

vi.mock('../../services/projectStorage', async (importOriginal) => ({
  ...await importOriginal<typeof import('../../services/projectStorage')>(),
  getAsset: mocks.getAsset,
}));

vi.mock('../RichPromptInput', () => ({
  RichPromptInput: ({ pool }: { pool: ReferenceImagePoolResult }) =>
    <div data-testid="prompt-pool-count">{pool.count}</div>,
}));

vi.mock('./ImageGenNode', () => ({
  GenFooter: () => <button type="button">生成视频</button>,
}));

const referencePool = (): ReferenceImagePoolResult => {
  const entries = [
    { number: 2, assetId: 'last-image', sourceNodeId: 'node-2', sourceNodeName: '尾帧图', mediaType: 'image' as const, source: 'upstream' as const },
    { number: 1, assetId: 'first-image', sourceNodeId: 'node-1', sourceNodeName: '首帧图', mediaType: 'image' as const, source: 'upstream' as const },
  ];
  return {
    entries,
    count: entries.length,
    getByNumber: (number) => entries.find((entry) => entry.number === number),
    getByAssetId: (assetId) => entries.find((entry) => entry.assetId === assetId),
    assetIds: entries.map((entry) => entry.assetId),
    isValidNumber: (number) => number >= 1 && number <= entries.length,
  };
};

const emptyReferencePool: ReferenceImagePoolResult = {
  entries: [],
  count: 0,
  getByNumber: () => undefined,
  getByAssetId: () => undefined,
  assetIds: [],
  isValidNumber: () => false,
};

const frameEdges = () => [
  { id: 'edge-first', source: 'node-1', sourceHandle: 'image', target: 'video-node', targetHandle: 'first', type: 'canvas' },
  { id: 'edge-last', source: 'node-2', sourceHandle: 'image', target: 'video-node', targetHandle: 'last', type: 'canvas' },
];

const baseData: CanvasNode['data'] = {
  kind: 'firstLastFrameToVideo',
  label: '首尾帧生视频',
  prompt: '',
  status: 'idle',
  accent: '#8f9cff',
  videoMode: 'firstLastFrame',
};

const frameAsset = (id: string): PersistedAsset => ({
  id,
  kind: 'image',
  name: `${id}.png`,
  mimeType: 'image/png',
  bytes: 1,
  width: 100,
  height: 100,
  originalBlob: new Blob(['x'], { type: 'image/png' }),
  thumbnailBlob: new Blob(['x'], { type: 'image/png' }),
  previewBlob: new Blob(['x'], { type: 'image/png' }),
  operation: { type: 'import', params: {} },
  libraryState: 'project',
  createdAt: 1,
});

describe('VideoGenNode first-last-frame binding', () => {
  afterEach(cleanup);

  it('assigns the first two upstream images to first and last frame slots', async () => {
    useMediaStore.setState({
      assets: {
        'first-image': frameAsset('first-image'),
        'last-image': frameAsset('last-image'),
      },
      metadata: {},
    });
    useCanvasStore.setState({
      nodes: [
        { id: 'node-1', type: 'image', position: { x: 0, y: 0 }, data: { kind: 'image', label: '首帧图', prompt: '', status: 'idle', accent: '#fff' } },
        { id: 'node-2', type: 'image', position: { x: 0, y: 400 }, data: { kind: 'image', label: '尾帧图', prompt: '', status: 'idle', accent: '#fff' } },
      ],
      edges: frameEdges(),
    });
    function ControlledNode() {
      const [data, setData] = React.useState(baseData);
      return (
        <VideoGenNode
          id="video-node"
          data={data}
          updateNode={(_id, patch) => setData((current) => ({ ...current, ...patch }))}
          textDraft=""
          setTextDraft={vi.fn()}
          referencePool={referencePool()}
          deleteReference={vi.fn()}
          draftFeedback={null}
          setDraftFeedback={vi.fn()}
        />
      );
    }

    render(<ControlledNode />);

    await waitFor(() => expect(screen.getByAltText('首帧')).toBeTruthy());
    expect(screen.getByAltText('尾帧')).toBeTruthy();
    expect(screen.getByAltText('首帧').closest('.video-ref-slot')?.getAttribute('data-asset-id')).toBe('first-image');
    expect(screen.getByAltText('尾帧').closest('.video-ref-slot')?.getAttribute('data-asset-id')).toBe('last-image');
    expect(screen.getByAltText('首帧').className).toBe('video-ref-image');
    expect(screen.getByAltText('尾帧').className).toBe('video-ref-image');
    expect(screen.getByTestId('prompt-pool-count').textContent).toBe('0');
  });

  it('removes an assigned frame and disconnects its source image', async () => {
    const deleteReference = vi.fn((entry: { sourceNodeId: string }) => {
      useCanvasStore.setState((state) => ({
        edges: state.edges.filter((edge) => !(edge.target === 'video-node' && edge.source === entry.sourceNodeId)),
      }));
    });
    useMediaStore.setState({
      assets: {
        'first-image': frameAsset('first-image'),
        'last-image': frameAsset('last-image'),
      },
      metadata: {},
    });
    useCanvasStore.setState({
      nodes: [
        { id: 'node-1', type: 'image', position: { x: 0, y: 0 }, data: { kind: 'image', label: '首帧图', prompt: '', status: 'idle', accent: '#fff' } },
        { id: 'node-2', type: 'image', position: { x: 0, y: 400 }, data: { kind: 'image', label: '尾帧图', prompt: '', status: 'idle', accent: '#fff' } },
      ],
      edges: frameEdges(),
    });

    function ControlledNode() {
      const [data, setData] = React.useState(baseData);
      return (
        <VideoGenNode
          id="video-node"
          data={data}
          updateNode={(_id, patch) => setData((current) => ({ ...current, ...patch }))}
          textDraft=""
          setTextDraft={vi.fn()}
          referencePool={referencePool()}
          deleteReference={deleteReference}
          draftFeedback={null}
          setDraftFeedback={vi.fn()}
        />
      );
    }

    render(<ControlledNode />);
    await waitFor(() => expect(screen.getByAltText('首帧')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: '移除首帧' }));

    expect(deleteReference).toHaveBeenCalledWith(expect.objectContaining({
      number: 1,
      sourceNodeId: 'node-1',
    }));
    await waitFor(() => expect(screen.queryByAltText('首帧')).toBeNull());
  });

  it('clears frame slots when the upstream connection disappears', async () => {
    useMediaStore.setState({
      assets: {
        'first-image': frameAsset('first-image'),
        'last-image': frameAsset('last-image'),
      },
      metadata: {},
    });
    useCanvasStore.setState({
      nodes: [
        { id: 'node-1', type: 'image', position: { x: 0, y: 0 }, data: { kind: 'image', label: '首帧图', prompt: '', status: 'idle', accent: '#fff' } },
        { id: 'node-2', type: 'image', position: { x: 0, y: 400 }, data: { kind: 'image', label: '尾帧图', prompt: '', status: 'idle', accent: '#fff' } },
      ],
      edges: frameEdges(),
    });

    function ControlledNode({ pool }: { pool: ReferenceImagePoolResult }) {
      const [data, setData] = React.useState(baseData);
      return (
        <VideoGenNode
          id="video-node"
          data={data}
          updateNode={(_id, patch) => setData((current) => ({ ...current, ...patch }))}
          textDraft=""
          setTextDraft={vi.fn()}
          referencePool={pool}
          deleteReference={vi.fn()}
          draftFeedback={null}
          setDraftFeedback={vi.fn()}
        />
      );
    }

    const { rerender } = render(<ControlledNode pool={referencePool()} />);
    await waitFor(() => expect(screen.getByAltText('首帧')).toBeTruthy());

    useCanvasStore.setState({ edges: [] });
    rerender(<ControlledNode pool={emptyReferencePool} />);

    await waitFor(() => expect(screen.queryByAltText('首帧')).toBeNull());
    expect(screen.queryByAltText('尾帧')).toBeNull();
  });

  it('loads assigned frame assets from IndexedDB when media store is empty', async () => {
    mocks.getAsset.mockImplementation(async (id: string) => frameAsset(id));
    useMediaStore.setState({ assets: {}, metadata: {} });
    useCanvasStore.setState({
      nodes: [
        { id: 'node-1', type: 'image', position: { x: 0, y: 0 }, data: { kind: 'image', label: '首帧图', prompt: '', status: 'idle', accent: '#fff' } },
        { id: 'node-2', type: 'image', position: { x: 0, y: 400 }, data: { kind: 'image', label: '尾帧图', prompt: '', status: 'idle', accent: '#fff' } },
      ],
      edges: frameEdges(),
    });

    function ControlledNode() {
      const [data, setData] = React.useState(baseData);
      return (
        <VideoGenNode
          id="video-node"
          data={data}
          updateNode={(_id, patch) => setData((current) => ({ ...current, ...patch }))}
          textDraft=""
          setTextDraft={vi.fn()}
          referencePool={referencePool()}
          deleteReference={vi.fn()}
          draftFeedback={null}
          setDraftFeedback={vi.fn()}
        />
      );
    }

    render(<ControlledNode />);

    await waitFor(() => expect(screen.getByAltText('首帧')).toBeTruthy());
    expect(screen.getByAltText('尾帧')).toBeTruthy();
  });

  it('grows a first-last-frame node so following controls flow below the previews', async () => {
    useCanvasStore.setState({
      nodes: [{
        id: 'video-node',
        type: 'firstLastFrameToVideo',
        position: { x: 0, y: 0 },
        width: 560,
        height: 420,
        data: baseData,
      }],
      edges: [],
    });

    render(
      <VideoGenNode
        id="video-node"
        data={baseData}
        updateNode={vi.fn()}
        textDraft=""
        setTextDraft={vi.fn()}
        referencePool={emptyReferencePool}
        deleteReference={vi.fn()}
        draftFeedback={null}
        setDraftFeedback={vi.fn()}
      />,
    );

    await waitFor(() => {
      expect(useCanvasStore.getState().nodes[0].height).toBe(560);
    });
  });
});
