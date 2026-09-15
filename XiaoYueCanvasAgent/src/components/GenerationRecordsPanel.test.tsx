// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { GenerationRecordsPanel } from './GenerationRecordsPanel';
import type { CanvasNode, GenerationRecord, MediaAsset } from '../types';

const node = (id: string, label: string, assetId?: string): CanvasNode => ({
  id,
  type: 'image',
  position: { x: 0, y: 0 },
  data: {
    kind: 'image',
    label,
    prompt: '',
    status: 'idle',
    accent: '#d7ff54',
    ...(assetId ? { assetId } : {}),
  },
});

const record: GenerationRecord = {
  id: 'record-1',
  producerNodeId: 'producer',
  actionId: 'generateImage',
  attempt: 1,
  jobId: 'job-1',
  status: 'completed',
  progress: 1,
  queueStatus: 'terminal',
  materializationStatus: 'completed',
  materializedAssetIds: ['result-asset'],
  resultNodeIds: ['result-node'],
  sources: [{ nodeId: 'source-node', assetId: 'source-asset', role: 'REFERENCE', order: 0 }],
  prompt: '把猫变成蓝色',
  effectivePrompt: '把猫变成蓝色',
  params: { capabilityId: 'image.redraw', model: 'gpt-image-2', size: '1024x1024' },
  model: 'gpt-image-2',
  startedAt: 1,
  completedAt: 2000,
};

const asset = (id: string): MediaAsset => ({
  id,
  kind: 'image',
  name: `${id}.png`,
  mimeType: 'image/png',
  bytes: 4,
  width: 1024,
  height: 768,
  originalBlob: new Blob(['original'], { type: 'image/png' }),
  thumbnailBlob: new Blob(['thumbnail'], { type: 'image/webp' }),
  previewBlob: new Blob(['preview'], { type: 'image/webp' }),
  libraryState: 'project',
  createdAt: 1,
});

describe('GenerationRecordsPanel', () => {
  beforeEach(() => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn((blob: Blob) => `blob:${blob.size}`),
      revokeObjectURL: vi.fn(),
    });
    useMediaStore.getState().clearAssets();
    useMediaStore.getState().upsertAssets([asset('source-asset'), asset('result-asset')]);
    useCanvasStore.setState({
      nodes: [
        node('source-node', '参考图片', 'source-asset'),
        node('producer', '图生图节点'),
        node('result-node', '生成结果', 'result-asset'),
      ],
      generationRecords: [record],
    });
  });

  afterEach(() => {
    cleanup();
    useMediaStore.getState().clearAssets();
    vi.unstubAllGlobals();
  });

  it('opens a provenance detail with source, node, and result', () => {
    render(<GenerationRecordsPanel onClose={() => undefined} />);
    fireEvent.click(screen.getByRole('button', { name: /生成图片/ }));

    expect(screen.getByText('生成链路')).toBeTruthy();
    expect(screen.getByText('来源素材 1')).toBeTruthy();
    expect(screen.getByText('结果素材 1')).toBeTruthy();
    expect(screen.getByText('由「图生图节点」提交')).toBeTruthy();
    expect(screen.getAllByText('把猫变成蓝色').length).toBeGreaterThanOrEqual(1);
  });

  it('opens and closes a full image preview from a result thumbnail', () => {
    render(<GenerationRecordsPanel onClose={() => undefined} />);
    fireEvent.click(screen.getByRole('button', { name: /生成图片/ }));

    expect(screen.getByRole('button', { name: '预览图片：参考图片 · 参考' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '预览图片：result-asset.png' }));

    const dialog = screen.getByRole('dialog', { name: '图片预览' });
    expect(within(dialog).getByRole('img', { name: 'result-asset.png' })).toBeTruthy();

    fireEvent.click(within(dialog).getByRole('button', { name: '关闭预览' }));
    expect(screen.queryByRole('dialog', { name: '图片预览' })).toBeNull();
  });

  it('shows a live progress bar for an active generation record', () => {
    useCanvasStore.setState({
      generationRecords: [{
        ...record,
        status: 'running',
        progress: .42,
        materializationStatus: 'pending',
      }],
    });

    render(<GenerationRecordsPanel onClose={() => undefined} />);

    const progress = screen.getByRole('progressbar', { name: '生成进度' });
    expect(progress.getAttribute('aria-valuenow')).toBe('42');
  });
});
