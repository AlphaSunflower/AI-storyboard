// @vitest-environment jsdom

import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { normalizeModelDefault, parseModelParams, useGatewayModels } from './useGatewayModels';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('parseModelParams', () => {
  it('normalizes image capability fields returned by the gateway', () => {
    const params = parseModelParams(JSON.stringify({
      sizes: '1K,2K,4K',
      sizeDefault: '2K',
      qualities: 'standard,high',
      qualityDefault: 'high',
      styles: 'anime,realistic',
      styleDefault: 'anime',
      aspectRatios: '1:1,16:9',
      aspectRatioDefault: '16:9',
      n: { min: 1, max: 15, default: 2 },
      refImages: { min: 1, max: 14 },
      maxPromptChars: 300,
      spec: {
        request: [
          { key: 'size', label: 'size', type: 'string' },
          { key: 'response_format', label: 'response_format', type: 'string', default: 'url' },
          { key: 'prompt_optimizer', label: 'prompt_optimizer', type: 'bool', default: true },
        ],
      },
    }));

    expect(params.sizes).toEqual(['1K', '2K', '4K']);
    expect(params.sizeDefault).toBe('2K');
    expect(params.qualities).toEqual(['standard', 'high']);
    expect(params.qualityDefault).toBe('high');
    expect(params.styles).toEqual(['anime', 'realistic']);
    expect(params.styleDefault).toBe('anime');
    expect(params.aspectRatios).toEqual(['1:1', '16:9']);
    expect(params.aspectRatioDefault).toBe('16:9');
    expect(params.n).toEqual({ min: 1, max: 15, default: 2 });
    expect(params.refImages).toEqual({ min: 1, max: 14 });
    expect(params.maxPromptChars).toBe(300);
    expect(params.spec?.request).toHaveLength(3);
    expect(params.spec?.request?.[1]).toMatchObject({ key: 'response_format', default: 'url' });
  });
});

describe('normalizeModelDefault', () => {
  it('maps a known legacy default onto a supported option', () => {
    expect(normalizeModelDefault('hd', ['low', 'medium', 'high', 'auto'])).toBe('high');
  });

  it('falls back to the first supported option for an unknown default', () => {
    expect(normalizeModelDefault('ultra', ['low', 'medium', 'high'])).toBe('low');
  });
});

describe('useGatewayModels', () => {
  it('surfaces gateway errors instead of presenting an empty silent fallback', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false,
      status: 401,
      json: async () => ({ message: '登录已失效' }),
    }) as Response));

    const { result } = renderHook(() => useGatewayModels('image'));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.models).toEqual([]);
    expect(result.current.error).toBe('登录已失效');
  });

  it('falls back to the configured default image model when the network is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    }));

    const { result } = renderHook(() => useGatewayModels('image'));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.defaultModel).toBe('gpt-image-2');
    expect(result.current.models).toHaveLength(1);
    expect(result.current.error).toBeNull();
  });
});
