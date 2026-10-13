import { beforeEach, describe, expect, it } from 'vitest';
import {
  GENERATION_PREFERENCES_STORAGE_KEY,
  defaultGenerationPreferences,
  deriveModelCapability,
  loadGenerationPreferences,
  normalizeGenerationPreferences,
  planCardPrefill,
  readModelCapability,
  saveGenerationPreferences,
  toGenerationPreferencesPayload,
  type GenerationPreferences,
} from './generationPreferences';

const custom = (): GenerationPreferences => ({
  auto: false,
  ignoreGenerationPlan: false,
  image: { model: 'doubao-seedream-4-0-250828', aspectRatio: '16:9', clarity: '2K', clarityParam: 'size' },
  video: { model: 'doubao-seedance-1-0', aspectRatio: '9:16', clarity: '1080P', clarityParam: 'resolution' },
});

beforeEach(() => {
  window.localStorage.clear();
});

describe('generationPreferences 形状与本地存储', () => {
  it('默认为自动，不忽略生成方案', () => {
    const defaults = defaultGenerationPreferences();
    expect(defaults.auto).toBe(true);
    expect(defaults.ignoreGenerationPlan).toBe(false);
    expect(defaults.image).toEqual({ model: '', aspectRatio: '', clarity: '', clarityParam: '' });
  });

  it('脏数据回落默认，字段缺失沿用默认', () => {
    expect(normalizeGenerationPreferences(null).auto).toBe(true);
    expect(normalizeGenerationPreferences('nonsense').ignoreGenerationPlan).toBe(false);
    const partial = normalizeGenerationPreferences({ auto: false, image: { model: ' m1 ', clarity: '2K' } });
    expect(partial.auto).toBe(false);
    expect(partial.image.model).toBe('m1');
    // 没记住清晰度参数键时清晰度不生效（避免下发到错误的参数）
    expect(partial.image.clarity).toBe('');
    expect(partial.video).toEqual({ model: '', aspectRatio: '', clarity: '', clarityParam: '' });
  });

  it('读写本地存储，坏 JSON 回落默认', () => {
    saveGenerationPreferences(custom());
    expect(loadGenerationPreferences()).toEqual(custom());

    window.localStorage.setItem(GENERATION_PREFERENCES_STORAGE_KEY, '{oops');
    expect(loadGenerationPreferences()).toEqual(defaultGenerationPreferences());
  });
});

describe('请求载荷', () => {
  it('自动且不忽略方案时不带字段（零回归）', () => {
    expect(toGenerationPreferencesPayload(defaultGenerationPreferences())).toBeUndefined();
  });

  it('自动但忽略方案时只带开关', () => {
    expect(toGenerationPreferencesPayload({
      ...defaultGenerationPreferences(),
      ignoreGenerationPlan: true,
    })).toEqual({ auto: true, ignoreGenerationPlan: true, image: {}, video: {} });
  });

  it('自定义时按参数键下发图片尺寸与视频分辨率，智能比例不下发', () => {
    expect(toGenerationPreferencesPayload(custom())).toEqual({
      auto: false,
      ignoreGenerationPlan: false,
      image: { model: 'doubao-seedream-4-0-250828', aspectRatio: '16:9', size: '2K' },
      video: { model: 'doubao-seedance-1-0', aspectRatio: '9:16', resolution: '1080P' },
    });
    expect(toGenerationPreferencesPayload({
      ...custom(),
      image: { ...custom().image, aspectRatio: '' },
    })?.image).toEqual({ model: 'doubao-seedream-4-0-250828', size: '2K' });
  });
});

describe('模型能力归一化', () => {
  it('图片优先尺寸档位，只有质量时标签回落「质量」', () => {
    const withSizes = deriveModelCapability({ sizes: ['1K', '2K'], sizeDefault: '2K', aspectRatios: ['16:9'] }, 'image');
    expect(withSizes?.ratioOptions).toEqual(['16:9']);
    expect(withSizes?.clarity).toEqual({ label: '清晰度', param: 'size', options: ['1K', '2K'], default: '2K' });

    const qualityOnly = deriveModelCapability({ qualities: ['low', 'high'] }, 'image');
    expect(qualityOnly?.clarity).toEqual({ label: '质量', param: 'quality', options: ['low', 'high'], default: '' });
    expect(qualityOnly?.ratioOptions).toEqual([]);
  });

  it('视频用分辨率，无能力声明时返回空', () => {
    const video = deriveModelCapability({ resolutions: ['720P', '1080P'], resolutionDefault: '720P' }, 'video');
    expect(video?.clarity).toEqual({ label: '清晰度', param: 'resolution', options: ['720P', '1080P'], default: '720P' });
    // 卡片路径下 params 是 JSON 字符串
    expect(deriveModelCapability('{"sizes":["2K"]}', 'image')?.clarity?.options).toEqual(['2K']);
    expect(deriveModelCapability('{broken', 'image')).toBeNull();
    expect(deriveModelCapability(undefined, 'video')).toBeNull();
  });

  it('后端优先，缺失时本地推导', () => {
    const fromBackend = readModelCapability({
      preferences: {
        ratioOptions: ['21:9', '16:9'],
        ratioDefault: '16:9',
        clarity: { label: '清晰度', param: 'size', options: ['2K'], default: '2K' },
      },
      params: { sizes: ['4K'] },
    }, 'image');
    expect(fromBackend?.ratioOptions).toEqual(['21:9', '16:9']);
    expect(fromBackend?.clarity?.options).toEqual(['2K']);

    const fallback = readModelCapability({ params: { sizes: ['4K'] } }, 'image');
    expect(fallback?.clarity?.options).toEqual(['4K']);
  });
});

describe('计划卡预选', () => {
  const models = [{
    value: 'doubao-seedream-4-0-250828',
    default: 'true',
    params: { sizes: ['1K', '2K'], aspectRatios: ['16:9'] },
  }];

  it('自动模式不预选', () => {
    expect(planCardPrefill(defaultGenerationPreferences(), 'image', models)).toEqual({});
  });

  it('自定义模式按偏好预选，键带前缀', () => {
    expect(planCardPrefill(custom(), 'image', models)).toEqual({
      imageModel: 'doubao-seedream-4-0-250828',
      imageAspectRatio: '16:9',
      imageSize: '2K',
    });
    expect(planCardPrefill(custom(), 'video', [{
      value: 'doubao-seedance-1-0',
      params: { resolutions: ['1080P'] },
    }], '')).toEqual({
      Model: 'doubao-seedance-1-0',
      AspectRatio: '9:16',
      Resolution: '1080P',
    });
  });
});
