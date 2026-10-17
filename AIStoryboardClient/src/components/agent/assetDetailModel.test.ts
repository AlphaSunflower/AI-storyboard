import { describe, expect, it } from 'vitest';
import type { AgentAsset } from '../../api/agent';
import {
  aspectRatioFromSize,
  assetDetailTitle,
  assetMediaKind,
  buildAssetMetaEntries,
  buildAssetParamEntries,
  formatAssetDuration,
  formatAssetTime,
} from './assetDetailModel';

function asset(patch: Partial<AgentAsset> = {}): AgentAsset {
  return {
    id: 'asset-1',
    conversationId: 'conversation-1',
    type: 'image',
    url: '/api/files/images/one.png',
    prompt: '精细国风东方龙图案',
    model: 'Seedream 4.5',
    status: 'completed',
    taskId: null,
    error: null,
    createdAt: '2026-10-10T08:30:00.000Z',
    ...patch,
  };
}

describe('assetMediaKind', () => {
  it('trusts the asset type first', () => {
    expect(assetMediaKind('image', '/api/files/documents/a.pdf')).toBe('image');
    expect(assetMediaKind('video', '/api/files/images/a.png')).toBe('video');
    expect(assetMediaKind('document', '/api/files/images/a.png')).toBe('document');
  });

  it('falls back to the extension for reference uploads', () => {
    expect(assetMediaKind('reference', '/api/files/images/a.png')).toBe('image');
    expect(assetMediaKind('reference', '/api/files/videos/a.mp4?v=2')).toBe('video');
    expect(assetMediaKind(null, '/api/files/files/a.bin')).toBe('document');
  });
});

describe('aspectRatioFromSize', () => {
  it('reduces common image sizes to a ratio', () => {
    expect(aspectRatioFromSize('1024x1024')).toBe('1:1');
    expect(aspectRatioFromSize('1536x1024')).toBe('3:2');
    expect(aspectRatioFromSize('1024x1536')).toBe('2:3');
    expect(aspectRatioFromSize('1792x1024')).toBe('7:4');
    expect(aspectRatioFromSize('1024×768')).toBe('4:3');
  });

  it('refuses to guess when the size is not a common frame', () => {
    expect(aspectRatioFromSize('1023x1025')).toBeNull();
    expect(aspectRatioFromSize('2K')).toBeNull();
    expect(aspectRatioFromSize(null)).toBeNull();
  });
});

describe('buildAssetParamEntries', () => {
  it('keeps the model first and derives the ratio from the size', () => {
    const entries = buildAssetParamEntries(asset({
      params: JSON.stringify({ size: '2048x2048', quality: 'high', n: 1, mode: 'text' }),
    }));

    expect(entries.map((entry) => entry.key)).toEqual(['model', 'aspectRatio', 'size', 'quality', 'n', 'mode']);
    expect(entries[0]).toEqual({ key: 'model', label: '模型', value: 'Seedream 4.5' });
    expect(entries[1]).toEqual({ key: 'aspectRatio', label: '画幅', value: '1:1' });
  });

  it('prefers a recorded aspect ratio over the derived one', () => {
    const entries = buildAssetParamEntries(asset({
      type: 'video',
      params: JSON.stringify({ resolution: '1080p', aspectRatio: '16:9', size: '1920x1080', duration: '5' }),
    }));

    expect(entries.find((entry) => entry.key === 'aspectRatio')?.value).toBe('16:9');
    expect(entries.map((entry) => entry.key)).toEqual(['model', 'aspectRatio', 'resolution', 'size', 'duration']);
  });

  it('keeps unknown backend keys instead of dropping them', () => {
    const entries = buildAssetParamEntries(asset({ params: JSON.stringify({ seed: 42 }) }));
    expect(entries.at(-1)).toEqual({ key: 'seed', label: 'seed', value: '42' });
  });

  it('falls back to the model inside params and drops empty values', () => {
    const entries = buildAssetParamEntries(asset({
      model: null,
      params: JSON.stringify({ model: 'gpt-image-2', quality: '', negativePrompt: null }),
    }));

    expect(entries).toEqual([{ key: 'model', label: '模型', value: 'gpt-image-2' }]);
  });

  it('returns the model alone when there are no params at all', () => {
    expect(buildAssetParamEntries(asset({ params: null })))
      .toEqual([{ key: 'model', label: '模型', value: 'Seedream 4.5' }]);
  });

  it('does not blow up on malformed params json', () => {
    expect(buildAssetParamEntries(asset({ params: '{not json' })))
      .toEqual([{ key: 'model', label: '模型', value: 'Seedream 4.5' }]);
  });
});

describe('buildAssetMetaEntries', () => {
  it('reports duration and time, without repeating the generation kind', () => {
    const entries = buildAssetMetaEntries(asset({
      genType: 'text_to_image',
      durationMs: 84_000,
      createdAt: new Date().toISOString(),
    }));

    expect(entries.map((entry) => [entry.label, entry.value])).toEqual([
      ['耗时', '1 分 24 秒'],
      ['生成时间', expect.stringMatching(/^今天 /)],
    ]);
  });
});

describe('formatAssetDuration', () => {
  it('uses seconds under a minute and minutes above', () => {
    expect(formatAssetDuration(3_400)).toBe('3 秒');
    expect(formatAssetDuration(59_600)).toBe('1 分');
    expect(formatAssetDuration(125_000)).toBe('2 分 5 秒');
  });

  it('says so instead of printing a dash when the row has no duration', () => {
    expect(formatAssetDuration(null)).toBe('未记录');
    expect(formatAssetDuration(Number.NaN)).toBe('未记录');
  });
});

describe('formatAssetTime', () => {
  it('falls back to the raw string when it is not a date', () => {
    expect(formatAssetTime('not-a-date')).toBe('not-a-date');
    expect(formatAssetTime(null)).toBe('未记录');
  });
});

describe('assetDetailTitle', () => {
  it('names the generation kind for media and the file name for documents', () => {
    expect(assetDetailTitle(asset({ genType: 'image_to_image' }), '/api/files/images/one.png')).toBe('图生图');
    expect(assetDetailTitle(asset({ type: 'document' }), '/api/files/reports/q3.md')).toBe('q3.md');
  });
});
