// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ImageGenNode } from './ImageGenNode';
import type { CanvasNode } from '../../types';
import type { ReferenceImagePoolResult } from '../../canvas/referenceImagePool';
import { useMediaStore } from '../../store/mediaStore';
import { useCanvasStore } from '../../store/canvasStore';
import type { GenerationRecord, MediaAsset } from '../../types';

const { submitImageGeneration, submitImageRedraw, submitImageMultiAngle, submitBatchImageRefine } = vi.hoisted(() => ({
  submitImageGeneration: vi.fn(),
  submitImageRedraw: vi.fn(),
  submitImageMultiAngle: vi.fn(),
  submitBatchImageRefine: vi.fn(),
}));

vi.mock('../../services/jobRunner', () => ({
  submitImageGeneration,
  submitImageRedraw,
  submitImageMultiAngle,
  submitBatchImageRefine,
}));

vi.mock('@xyflow/react', () => ({
  NodeToolbar: ({ children, className, isVisible }: { children?: React.ReactNode; className?: string; isVisible?: boolean }) =>
    isVisible === false ? null : <div className={className}>{children}</div>,
  Position: { Top: 'top', Bottom: 'bottom' },
}));

const emptyPool: ReferenceImagePoolResult = {
  entries: [],
  count: 0,
  getByNumber: () => undefined,
  getByAssetId: () => undefined,
  assetIds: [],
  isValidNumber: () => false,
};

const poolOf = (assetIds: string[]): ReferenceImagePoolResult => ({
  entries: assetIds.map((assetId, index) => ({
    number: index + 1,
    assetId,
    sourceNodeId: `source-${index + 1}`,
    sourceNodeName: `图${index + 1}`,
    mediaType: 'image' as const,
    source: 'upstream' as const,
  })),
  count: assetIds.length,
  getByNumber: (number) => undefined,
  getByAssetId: (assetId) => undefined,
  assetIds,
  isValidNumber: () => false,
});

const data = (overrides: Partial<CanvasNode['data']> = {}): CanvasNode['data'] => ({
  kind: 'imageGeneration',
  label: '图片生成',
  prompt: '',
  status: 'idle',
  accent: '#b48cff',
  ...overrides,
});

const imageAsset = (id: string): MediaAsset => ({
  id,
  kind: 'image',
  name: `${id}.png`,
  mimeType: 'image/png',
  bytes: 4,
  width: 100,
  height: 80,
  originalBlob: new Blob(['original'], { type: 'image/png' }),
  thumbnailBlob: new Blob(['thumbnail'], { type: 'image/png' }),
  previewBlob: new Blob(['preview'], { type: 'image/png' }),
  libraryState: 'project',
  createdAt: 1,
});

const generationRecord = (producerNodeId: string, assetId: string): GenerationRecord => ({
  id: 'record-1',
  producerNodeId,
  actionId: 'generateImage',
  attempt: 1,
  jobId: 'job-1',
  status: 'completed',
  progress: 1,
  queueStatus: 'terminal',
  materializationStatus: 'completed',
  materializedAssetIds: [assetId],
  resultNodeIds: [],
  sources: [],
  prompt: '一张分镜',
  effectivePrompt: '一张分镜',
  params: {},
  model: 'seedream-4-0',
  startedAt: 1,
  completedAt: 2000,
});

const renderNode = (nodeData: CanvasNode['data'], pool = emptyPool, feedback = vi.fn(), updateNode = vi.fn()) => render(
  <ImageGenNode
    id="image-node"
    data={nodeData}
    selected
    updateNode={updateNode}
    textDraft=""
    setTextDraft={vi.fn()}
    referencePool={pool}
    deleteReference={vi.fn()}
    draftFeedback={null}
    setDraftFeedback={feedback}
  />,
);

describe('ImageGenNode merged modes', () => {
  afterEach(() => {
    cleanup();
    submitImageGeneration.mockReset();
    submitImageRedraw.mockReset();
    submitImageMultiAngle.mockReset();
    submitBatchImageRefine.mockReset();
    useCanvasStore.setState({ nodes: [], edges: [], generationRecords: [] });
    useMediaStore.setState({ assets: {} });
  });

  it('shows the four merged modes and the placeholder body', () => {
    renderNode(data());

    ['文生图', '全能参考', '图生图', '批量完善'].forEach((label) => {
      expect(screen.getByRole('button', { name: label })).toBeTruthy();
    });
    expect(screen.getByText('输入提示词生成图片')).toBeTruthy();
  });

  it('switches data.mode from the mode chips', () => {
    const updateNode = vi.fn();
    renderNode(data(), emptyPool, vi.fn(), updateNode);

    fireEvent.click(screen.getByRole('button', { name: '批量完善' }));

    expect(updateNode).toHaveBeenCalledWith('image-node', { mode: 'batchRefine' });
  });

  it('previews the source image of an image-to-image node', () => {
    useMediaStore.setState({ assets: { 'asset-1': imageAsset('asset-1') } });
    renderNode(
      data({
        kind: 'imageToImage',
        mode: 'image2image',
        redraw: { version: 2, sourceAssetId: 'asset-1', marks: [] },
      }),
      poolOf(['asset-1']),
    );

    fireEvent.click(screen.getByRole('button', { name: '预览图片：图1' }));

    const dialog = screen.getByRole('dialog', { name: '图片预览' });
    expect(within(dialog).getByRole('img', { name: '图1' })).toBeTruthy();

    fireEvent.click(within(dialog).getByRole('button', { name: '关闭预览' }));
    expect(screen.queryByRole('dialog', { name: '图片预览' })).toBeNull();
  });

  it('previews the generated result from the node body', () => {
    useMediaStore.setState({ assets: { 'result-1': imageAsset('result-1') } });
    useCanvasStore.setState({ generationRecords: [generationRecord('image-node', 'result-1')] });
    renderNode(data());

    fireEvent.click(screen.getByRole('button', { name: '预览图片：生成结果' }));

    const dialog = screen.getByRole('dialog', { name: '图片预览' });
    expect(within(dialog).getByRole('img', { name: '生成结果' })).toBeTruthy();
  });

  it('blocks multi-grid generation with fewer than two reference images', () => {
    const feedback = vi.fn();
    renderNode(data({ imagePreset: 'multi-grid' }), emptyPool, feedback);

    fireEvent.click(screen.getByRole('button', { name: '生成图片' }));

    expect(submitImageGeneration).not.toHaveBeenCalled();
    expect(feedback).toHaveBeenCalledWith('多宫格至少需要 2 张参考图');
  });

  it('keeps the auto count toggle in the settings popover', () => {
    const updateNode = vi.fn();
    renderNode(data({ n: 1 }), poolOf(['a', 'b', 'c', 'd']), vi.fn(), updateNode);

    fireEvent.click(screen.getByLabelText('参数设置'));
    const input = screen.getByRole('textbox', { name: '生成个数' });
    expect(input.getAttribute('value')).toBe('1');

    fireEvent.click(screen.getByRole('button', { name: 'Auto' }));
    expect(updateNode).toHaveBeenCalledWith('image-node', { n: 0 });
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
    renderNode(data({ kind: 'imageToImage', mode: 'image2image', prompt: '编号提示词', redraw }), poolOf(['asset-1']));

    fireEvent.click(screen.getByRole('button', { name: '执行重绘' }));

    expect(submitImageRedraw).toHaveBeenCalledWith(
      '',
      'image-node',
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
    renderNode(data({
      kind: 'imageToImage',
      mode: 'image2image',
      label: '源图 · 多角度',
      prompt: '多角度提示词',
      multiAngle,
    }), poolOf(['asset-1']));

    fireEvent.click(screen.getByRole('button', { name: '生成多角度' }));

    expect(submitImageMultiAngle).toHaveBeenCalledWith(
      '',
      'image-node',
      multiAngle,
      expect.any(Function),
      expect.objectContaining({ sourceAssetIds: ['asset-1'] }),
    );
  });

  it('submits the batch refine mode through the batch capability', () => {
    submitBatchImageRefine.mockResolvedValue({ jobIds: ['job-1'] });
    renderNode(data({ mode: 'batchRefine', prompt: '统一为白天' }), poolOf(['asset-1']));

    expect(screen.getByLabelText('批量完善公共提示词')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '批量完善图片' }));

    expect(submitBatchImageRefine).toHaveBeenCalledWith(
      'image-node',
      [expect.objectContaining({ assetId: 'asset-1', effectivePrompt: '统一为白天' })],
      expect.any(Function),
      expect.objectContaining({}),
    );
  });

  it('renders the placeholder body without the composer chrome inside the node card', () => {
    const { container } = renderNode(data());

    // 节点本体只有占位/预览；composer 挂在节点下方的 node-toolbar 里（真实运行时被 portal 到节点外）
    expect(container.querySelector('.gen-node > .gen-composer')).toBeNull();
    expect(container.querySelector('.gen-toolbar .gen-composer')).not.toBeNull();
  });
});
