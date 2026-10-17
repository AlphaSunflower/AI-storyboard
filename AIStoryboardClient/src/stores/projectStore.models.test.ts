import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../api/ai', () => ({
  aiApi: {
    aiModels: vi.fn(),
  },
}));

vi.mock('../api/projects', () => ({
  projectApi: {
    update: vi.fn(),
    get: vi.fn(),
  },
}));

import { aiApi } from '../api/ai';
import { useProjectStore } from './projectStore';
import { DEFAULT_IMAGE_MODEL, DEFAULT_UNDERSTANDING_MODEL, DEFAULT_VIDEO_MODEL } from '../config';

const SEEDREAM = 'doubao-seedream-4-0-250828';
const GPT_IMAGE = 'gpt-image-2';

function respond(payload: {
  version?: string;
  imageModels?: Array<{ value: string; label: string; default?: string }>;
  videoModels?: Array<{ value: string; label: string; default?: string }>;
  understandingModels?: Array<{ value: string; label: string; default?: string }>;
}) {
  vi.mocked(aiApi.aiModels).mockResolvedValue({
    data: {
      data: {
        imageModels: [],
        videoModels: [],
        understandingModels: [],
        ...payload,
      },
    },
  } as never);
}

describe('projectStore 模型目录同步', () => {
  beforeEach(() => {
    localStorage.clear();
    useProjectStore.setState({
      imageModel: DEFAULT_IMAGE_MODEL,
      videoModel: DEFAULT_VIDEO_MODEL,
      understandingModel: DEFAULT_UNDERSTANDING_MODEL,
      imageModelOptions: [],
      videoModelOptions: [],
      understandingModelOptions: [],
      modelCatalogVersion: null,
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('网关默认变化时，没手选过的编辑器跟随新默认', async () => {
    respond({ version: 'v1', imageModels: [{ value: GPT_IMAGE, label: 'GPT Image 2', default: 'true' }] });

    await useProjectStore.getState().fetchAiModels();

    expect(useProjectStore.getState().imageModel).toBe(GPT_IMAGE);
    expect(useProjectStore.getState().modelCatalogVersion).toBe('v1');
  });

  it('用户手选过的模型不会被网关默认覆盖，且刷新后仍保留', async () => {
    useProjectStore.getState().setImageModel(SEEDREAM);
    expect(JSON.parse(localStorage.getItem('editorModelChoice') ?? '{}')).toMatchObject({ image: SEEDREAM });

    respond({
      version: 'v1',
      imageModels: [
        { value: SEEDREAM, label: 'Seedream 4.0' },
        { value: GPT_IMAGE, label: 'GPT Image 2', default: 'true' },
      ],
    });

    await useProjectStore.getState().fetchAiModels();

    expect(useProjectStore.getState().imageModel).toBe(SEEDREAM);

    // 「刷新」= 重新加载模块：初始值从 localStorage 恢复，仍然是用户手选的那个
    vi.resetModules();
    const reloaded = await import('./projectStore');
    expect(reloaded.useProjectStore.getState().imageModel).toBe(SEEDREAM);
  });

  it('手选模型下架后回落到网关默认', async () => {
    useProjectStore.getState().setImageModel('retired-model');
    respond({ version: 'v2', imageModels: [{ value: GPT_IMAGE, label: 'GPT Image 2', default: 'true' }] });

    await useProjectStore.getState().fetchAiModels();

    expect(useProjectStore.getState().imageModel).toBe(GPT_IMAGE);
  });

  it('目录版本号没变时跳过整轮更新（focus/轮询不会打乱当前选择）', async () => {
    respond({ version: 'v1', imageModels: [{ value: GPT_IMAGE, label: 'GPT Image 2', default: 'true' }] });
    await useProjectStore.getState().fetchAiModels();
    expect(useProjectStore.getState().imageModel).toBe(GPT_IMAGE);

    // 人为放一个「不在列表里」的值：若第二轮没有被跳过，它会被重算成网关默认
    useProjectStore.setState({ imageModel: 'unexpected-value' });
    await useProjectStore.getState().fetchAiModels();

    expect(useProjectStore.getState().imageModel).toBe('unexpected-value');
  });

  it('目录版本号变化时立即重新计算（不等 60s）', async () => {
    respond({ version: 'v1', imageModels: [{ value: SEEDREAM, label: 'Seedream 4.0', default: 'true' }] });
    await useProjectStore.getState().fetchAiModels();
    expect(useProjectStore.getState().imageModel).toBe(SEEDREAM);

    respond({ version: 'v2', imageModels: [{ value: GPT_IMAGE, label: 'GPT Image 2', default: 'true' }] });
    await useProjectStore.getState().fetchAiModels();

    expect(useProjectStore.getState().imageModel).toBe(GPT_IMAGE);
    expect(useProjectStore.getState().modelCatalogVersion).toBe('v2');
  });

  it('网关返回空列表时保持静态兜底，不清空当前选择', async () => {
    useProjectStore.setState({ imageModel: DEFAULT_IMAGE_MODEL });
    respond({ version: 'v1' });

    await useProjectStore.getState().fetchAiModels();

    expect(useProjectStore.getState().imageModel).toBe(DEFAULT_IMAGE_MODEL);
  });
});
