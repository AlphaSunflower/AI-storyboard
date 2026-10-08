// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCanvasStore } from '../store/canvasStore';
import { TaskQueuePanel } from './TaskQueuePanel';
import type { CanvasNode, GenerationRecord } from '../types';

const cancelCanvasGeneration = vi.fn();
vi.mock('../services/jobRunner', () => ({
  cancelCanvasGeneration: (...args: unknown[]) => cancelCanvasGeneration(...args),
}));

const node = (id: string, label: string): CanvasNode => ({
  id,
  type: 'textToImage',
  position: { x: 0, y: 0 },
  data: { kind: 'textToImage', label, prompt: '', status: 'idle', accent: '#d7ff54' },
});

const record = (id: string, producerNodeId: string, status: GenerationRecord['status']): GenerationRecord => ({
  id,
  producerNodeId,
  actionId: 'generate',
  attempt: 1,
  status,
  progress: status === 'running' ? 0.4 : 1,
  queueStatus: status === 'running' ? 'running' : 'terminal',
  materializationStatus: status === 'completed' ? 'completed' : 'pending',
  materializedAssetIds: [],
  resultNodeIds: [],
  sources: [],
  prompt: `${id} prompt`,
  effectivePrompt: `${id} prompt`,
  params: { capabilityId: 'image.generate' },
  startedAt: 1,
});

describe('TaskQueuePanel', () => {
  beforeEach(() => {
    cancelCanvasGeneration.mockReset();
    useCanvasStore.setState({
      nodes: [node('running-node', '运行节点'), node('completed-node', '完成节点')],
      generationRecords: [
        record('running', 'running-node', 'running'),
        record('completed', 'completed-node', 'completed'),
      ],
    });
  });

  afterEach(cleanup);

  it('shows only active work and lets the user cancel it', () => {
    render(<TaskQueuePanel />);
    fireEvent.click(screen.getByRole('button', { name: '任务队列' }));

    expect(screen.getByText(/运行节点/)).toBeTruthy();
    expect(screen.queryByText(/完成节点/)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /取消运行节点的生成任务/ }));
    expect(cancelCanvasGeneration).toHaveBeenCalledWith('running');
  });

  it('closes when the user points outside the panel', async () => {
    render(<TaskQueuePanel />);
    fireEvent.click(screen.getByRole('button', { name: '任务队列' }));
    expect(screen.getByRole('dialog', { name: '任务队列' })).toBeTruthy();

    fireEvent.pointerDown(document.body);

    await waitFor(() => expect(screen.queryByRole('dialog', { name: '任务队列' })).toBeNull());
  });
});
