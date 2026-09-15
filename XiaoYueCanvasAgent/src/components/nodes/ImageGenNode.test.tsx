// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ImageGenNode } from './ImageGenNode';
import type { CanvasNode } from '../../types';

const { submitImageGeneration } = vi.hoisted(() => ({ submitImageGeneration: vi.fn() }));

vi.mock('../../services/jobRunner', () => ({
  submitImageGeneration,
}));

vi.mock('../../hooks/useGatewayModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../hooks/useGatewayModels')>();
  return {
    ...actual,
    useGatewayModels: () => ({ models: [], defaultModel: '', loading: false, error: null }),
  };
});

const data = (overrides: Partial<CanvasNode['data']> = {}): CanvasNode['data'] => ({
  kind: 'imageToImage',
  label: '图生图',
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
  });

  it('removes style tabs and defaults to the normal preset', () => {
    renderNode(data());

    expect(screen.queryByRole('button', { name: '2D动漫' })).toBeNull();
    expect(screen.queryByRole('button', { name: '3D国漫' })).toBeNull();
    expect(screen.queryByRole('button', { name: '真人' })).toBeNull();
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
});
