// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ImageGenNode } from './ImageGenNode';
import type { CanvasNode } from '../../types';
import { useMediaStore } from '../../store/mediaStore';

const { submitImageGeneration, submitImageRedraw, submitImageMultiAngle } = vi.hoisted(() => ({
  submitImageGeneration: vi.fn(),
  submitImageRedraw: vi.fn(),
  submitImageMultiAngle: vi.fn(),
}));

vi.mock('../../services/jobRunner', () => ({
  submitImageGeneration,
  submitImageRedraw,
  submitImageMultiAngle,
}));

vi.mock('../../hooks/useGatewayModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../hooks/useGatewayModels')>();
  return {
    ...actual,
    useGatewayModels: () => ({ models: [], defaultModel: '', loading: false, error: null }),
  };
});

const data = (overrides: Partial<CanvasNode['data']> = {}): CanvasNode['data'] => ({
  kind: 'imageGen',
  label: 'AI 图片',
  prompt: '',
  status: 'idle',
  accent: '#b48cff',
  ...overrides,
});

const renderNode = (nodeData: CanvasNode['data'], feedback = vi.fn()) => render(
  <ImageGenNode
    id="image-node"
    data={nodeData}
    updateNode={vi.fn()}
    textDraft=""
    setTextDraft={vi.fn()}
    referencePool={{
      count: 0,
      entries: [],
      assetIds: [],
      getByAssetId: () => undefined,
    }}
    deleteReference={vi.fn()}
    draftFeedback={null}
    setDraftFeedback={feedback}
  />,
);

describe('ImageGenNode presets', () => {
  afterEach(() => {
    cleanup();
    submitImageGeneration.mockReset();
    submitImageRedraw.mockReset();
    submitImageMultiAngle.mockReset();
  });

  it('keeps style tabs for normal image generation', () => {
    renderNode(data());

    expect(screen.getByRole('button', { name: '普通' }).className).toContain('is-active');
  });

  it('blocks multi-grid generation with fewer than two reference images', () => {
    const feedback = vi.fn();
    renderNode(data({ imagePreset: 'multi-grid' }), feedback);

    fireEvent.click(screen.getByRole('button', { name: '生成图片' }));

    expect(submitImageGeneration).not.toHaveBeenCalled();
    expect(feedback).toHaveBeenCalledWith('多宫格至少需要 2 张参考图');
  });

  it('offers an auto mode and a bounded manual generation count', () => {
    const updateNode = vi.fn();
    render(
      <ImageGenNode
        id="image-node"
        data={data({ n: 1 })}
        updateNode={updateNode}
        textDraft=""
        setTextDraft={vi.fn()}
        referencePool={{
          count: 4,
          entries: [],
          assetIds: [],
          getByAssetId: () => undefined,
        }}
        deleteReference={vi.fn()}
        draftFeedback={null}
        setDraftFeedback={vi.fn()}
      />,
    );

    const input = screen.getByRole('textbox', { name: '生成个数' });
    expect(input.getAttribute('max')).toBe('11');
    expect(input.getAttribute('value')).toBe('1');
    expect(screen.getByRole('button', { name: '减少生成个数' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '增加生成个数' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Auto' }));
    expect(updateNode).toHaveBeenCalledWith('image-node', { n: 0 });
  });

  it('can turn auto off and restore the last manual count', () => {
    function ControlledNode() {
      const [nodeData, setNodeData] = React.useState(data({ n: 5 }));
      return (
        <ImageGenNode
          id="image-node"
          data={nodeData}
          updateNode={(_id, patch) => setNodeData((current) => ({ ...current, ...patch }))}
          textDraft=""
          setTextDraft={vi.fn()}
          referencePool={{
            count: 4,
            entries: [],
            assetIds: [],
            getByAssetId: () => undefined,
          }}
          deleteReference={vi.fn()}
          draftFeedback={null}
          setDraftFeedback={vi.fn()}
        />
      );
    }

    render(<ControlledNode />);
    const auto = screen.getByRole('button', { name: 'Auto' });
    const input = screen.getByRole('textbox', { name: '生成个数' });

    fireEvent.click(auto);
    expect(input.getAttribute('value')).toBe('AI');
    fireEvent.click(auto);
    expect(input.getAttribute('value')).toBe('5');
    expect(auto.getAttribute('aria-pressed')).toBe('false');
  });

  it('submits image-to-image retries through the redraw capability', () => {
    submitImageRedraw.mockResolvedValue('job-redraw');
    useMediaStore.setState({
      assets: {
        'asset-1': {
          id: 'asset-1',
          kind: 'image',
          name: 'source.png',
          mimeType: 'image/png',
          bytes: 1,
          width: 100,
          height: 80,
          originalBlob: new Blob(['source'], { type: 'image/png' }),
          thumbnailBlob: new Blob(['thumb'], { type: 'image/png' }),
          previewBlob: new Blob(['preview'], { type: 'image/png' }),
          libraryState: 'project',
          createdAt: 1,
        },
      },
    });
    const redraw: NonNullable<CanvasNode['data']['redraw']> = {
      version: 2,
      sourceAssetId: 'asset-1',
      marks: [{
        id: 'paint',
        type: 'rectangle',
        operation: 'paint',
        start: { x: .1, y: .1 },
        end: { x: .8, y: .8 },
        intent: '改成蓝色织物',
        color: '#59d8ff',
        createdAt: 1,
      }],
    };
    render(
      <ImageGenNode
        id="image-to-image"
        data={data({ kind: 'imageToImage', label: '图生图', prompt: '编号提示词', redraw })}
        updateNode={vi.fn()}
        textDraft="编号提示词"
        setTextDraft={vi.fn()}
        referencePool={{
          count: 1,
          entries: [{ number: 1, assetId: 'asset-1', sourceNodeId: 'source-node' }],
          assetIds: ['asset-1'],
          getByAssetId: () => undefined,
        }}
        deleteReference={vi.fn()}
        draftFeedback={null}
        setDraftFeedback={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '执行重绘' }));

    expect(submitImageRedraw).toHaveBeenCalledWith(
      '编号提示词',
      'image-to-image',
      redraw,
      { width: 100, height: 80 },
      expect.any(Function),
      expect.objectContaining({ sourceAssetIds: ['asset-1'] }),
    );
  });

  it('submits a multi-angle node with its fixed Seedream request', () => {
    submitImageMultiAngle.mockResolvedValue('job-angle');
    useMediaStore.setState({
      assets: {
        'asset-1': {
          id: 'asset-1',
          kind: 'image',
          name: 'source.png',
          mimeType: 'image/png',
          bytes: 1,
          width: 100,
          height: 80,
          originalBlob: new Blob(['source'], { type: 'image/png' }),
          thumbnailBlob: new Blob(['thumb'], { type: 'image/png' }),
          previewBlob: new Blob(['preview'], { type: 'image/png' }),
          libraryState: 'project',
          createdAt: 1,
        },
      },
    });
    const multiAngle: NonNullable<CanvasNode['data']['multiAngle']> = {
      multiAngleMode: 'multi',
      model: 'doubao-seedream-4-5-251128',
      size: '2K',
      n: 4,
    };
    render(
      <ImageGenNode
        id="multi-angle-node"
        data={data({
          kind: 'imageToImage',
          label: '源图 · 多角度',
          prompt: '多角度提示词',
          multiAngle,
        })}
        updateNode={vi.fn()}
        textDraft="多角度提示词"
        setTextDraft={vi.fn()}
        referencePool={{
          count: 1,
          entries: [{ number: 1, assetId: 'asset-1', sourceNodeId: 'source-node' }],
          assetIds: ['asset-1'],
          getByAssetId: () => undefined,
        }}
        deleteReference={vi.fn()}
        draftFeedback={null}
        setDraftFeedback={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '生成多角度' }));

    expect(submitImageMultiAngle).toHaveBeenCalledWith(
      '多角度提示词',
      'multi-angle-node',
      multiAngle,
      expect.any(Function),
      expect.objectContaining({ sourceAssetIds: ['asset-1'] }),
    );
  });
});
