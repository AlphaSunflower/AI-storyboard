// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MultiAngleDialog } from './MultiAngleDialog';

const { useGatewayModels } = vi.hoisted(() => ({
  useGatewayModels: vi.fn(),
}));

vi.mock('../hooks/useGatewayModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../hooks/useGatewayModels')>();
  return {
    ...actual,
    useGatewayModels,
  };
});

const renderDialog = (onConfirm = vi.fn()) => render(
  <MultiAngleDialog
    sourceLabel="角色图"
    sourceName="character.png"
    onClose={vi.fn()}
    onConfirm={onConfirm}
  />,
);

describe('MultiAngleDialog', () => {
  afterEach(() => {
    cleanup();
    useGatewayModels.mockReset();
  });

  it('defaults to the grid mode and submits fixed Seedream 4.5 parameters', async () => {
    useGatewayModels.mockReturnValue({
      models: [
        { value: 'gpt-image-2', label: 'GPT Image 2' },
        { value: 'doubao-seedream-4-5-251128', label: 'Seedream 4.5' },
      ],
      defaultModel: 'gpt-image-2',
      loading: false,
      error: null,
    });
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    renderDialog(onConfirm);

    expect(screen.getByRole('button', { name: /宫格图/ }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByText('Seedream 4.5')).toBeTruthy();
    expect(screen.getByText('2K')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '生成多角度' }));

    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith({
      multiAngleMode: 'grid',
      model: 'doubao-seedream-4-5-251128',
      size: '2K',
      n: 1,
    }));
  });

  it('submits four separate images after selecting the multi mode', async () => {
    useGatewayModels.mockReturnValue({
      models: [{ value: 'doubao-seedream-4-0-250828', label: 'Seedream 4.0' }],
      defaultModel: 'doubao-seedream-4-0-250828',
      loading: false,
      error: null,
    });
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    renderDialog(onConfirm);

    fireEvent.click(screen.getByRole('button', { name: /多图/ }));
    fireEvent.click(screen.getByRole('button', { name: '生成多角度' }));

    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith({
      multiAngleMode: 'multi',
      model: 'doubao-seedream-4-0-250828',
      size: '2K',
      n: 4,
    }));
  });

  it('disables generation when no supported Seedream model is available', () => {
    useGatewayModels.mockReturnValue({
      models: [{ value: 'gpt-image-2', label: 'GPT Image 2' }],
      defaultModel: 'gpt-image-2',
      loading: false,
      error: null,
    });
    renderDialog();

    expect(screen.getByText('缺少支持多角度的 Seedream 模型')).toBeTruthy();
    expect((screen.getByRole('button', { name: '生成多角度' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
