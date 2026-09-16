import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CanvasNode } from '../types';
import { useCanvasStore } from '../store/canvasStore';
import {
  buildModelCatalog,
  defaultsForModel,
  modelTypeForNode,
  validateModelParams,
} from './modelConfig';
import { getNodeModelConfig, setNodeModelConfig } from './canvasModelConfigService';

const gatewayModels = [
  {
    value: 'image-model',
    label: 'Image Model',
    params: JSON.stringify({
      n: { min: 1, max: 4, default: 2 },
      sizes: ['1K', '2K'],
      sizeDefault: '1K',
      spec: {
        request: [
          { key: 'size', label: 'size', type: 'string' },
          { key: 'style', label: 'style', type: 'object', fields: [
            { key: 'style_type', label: '类型', type: 'enum', options: ['photo', 'anime'], default: 'photo' },
            { key: 'style_weight', label: '强度', type: 'range', min: 1, max: 10, default: 5 },
          ] },
          { key: 'subjects', label: '参考物', type: 'arrayofobject', fields: [
            { key: 'type', label: '类型', type: 'enum', options: ['character', 'object'] },
          ] },
        ],
      },
    }),
  },
  {
    value: 'video-model',
    label: 'Video Model',
    default: 'true',
    params: JSON.stringify({
      durations: [4, 8, 12],
      durationDefault: 8,
      resolutions: ['720P', '1080P'],
      resolutionDefault: '720P',
    }),
  },
];

const node = (overrides: Partial<CanvasNode['data']> = {}): CanvasNode => ({
  id: 'node-1',
  type: 'textToImage',
  position: { x: 0, y: 0 },
  data: {
    kind: 'textToImage',
    label: '文生图',
    prompt: 'test',
    status: 'idle',
    accent: '#fff',
    ...overrides,
  },
});

describe('model config catalog', () => {
  it('merges self-describing fields with legacy gateway capabilities', () => {
    const [imageModel] = buildModelCatalog(gatewayModels);
    const size = imageModel.fields.find((field) => field.key === 'size');
    const style = imageModel.fields.find((field) => field.key === 'style');
    const n = imageModel.fields.find((field) => field.key === 'n');

    expect(size).toMatchObject({
      type: 'enum',
      options: ['1K', '2K'],
      default: '1K',
    });
    expect(n).toMatchObject({ type: 'range', min: 1, max: 4, default: 2 });
    expect(style?.fields?.map((field) => field.key)).toEqual(['style_type', 'style_weight']);
  });

  it('creates nested defaults and maps media actions to catalog types', () => {
    const [imageModel, videoModel] = buildModelCatalog(gatewayModels);

    expect(defaultsForModel(imageModel)).toEqual({
      size: '1K',
      n: 2,
      style: { style_type: 'photo', style_weight: 5 },
    });
    expect(defaultsForModel(videoModel)).toEqual({
      duration: 8,
      resolution: '720P',
    });
    expect(modelTypeForNode('textToImage')).toBe('image');
    expect(modelTypeForNode('imageToImage')).toBe('image');
    expect(modelTypeForNode('referenceToVideo')).toBe('video');
    expect(modelTypeForNode('storyboardPlanner')).toBeUndefined();
  });

  it('normalizes legacy enum defaults against the current option list', () => {
    const [model] = buildModelCatalog([{
      value: 'legacy-image',
      params: JSON.stringify({
        qualities: ['low', 'medium', 'high', 'auto'],
        qualityDefault: 'hd',
      }),
    }]);

    expect(model.fields.find((field) => field.key === 'quality')?.default).toBe('high');
    expect(defaultsForModel(model).quality).toBe('high');
  });
});

describe('model parameter validation', () => {
  it('accepts values matching recursive enum, range, object and array schemas', () => {
    const [imageModel] = buildModelCatalog(gatewayModels);

    expect(validateModelParams(imageModel.fields, {
      size: '2K',
      n: 3,
      style: { style_type: 'anime', style_weight: 8 },
      subjects: [{ type: 'character' }],
    })).toMatchObject({
      size: '2K',
      n: 3,
      style: { style_type: 'anime', style_weight: 8 },
      subjects: [{ type: 'character' }],
    });
  });

  it.each([
    [{ unknown: true }, 'MODEL_PARAM_UNKNOWN'],
    [{ size: '4K' }, 'MODEL_PARAM_ENUM'],
    [{ n: 9 }, 'MODEL_PARAM_RANGE'],
    [{ style: { style_type: 'watercolor' } }, 'MODEL_PARAM_ENUM'],
    [{ subjects: [{ type: 'animal' }] }, 'MODEL_PARAM_ENUM'],
    [{ subjects: {} }, 'MODEL_PARAM_TYPE'],
  ])('rejects invalid parameter %o', (params, code) => {
    const [imageModel] = buildModelCatalog(gatewayModels);

    expect(() => validateModelParams(imageModel.fields, params)).toThrowError(
      expect.objectContaining({ code }),
    );
  });
});

describe('canvas node model config service', () => {
  const context = { projectId: 'project-1', source: 'mcp' as const };

  beforeEach(() => {
    useCanvasStore.setState({
      projectId: 'project-1',
      canvasId: 'canvas-1',
      nodes: [node()],
      edges: [],
      generationRecords: [],
      executions: [],
      past: [],
      future: [],
      selectedNodeId: 'node-1',
      dragStartSnapshot: null,
      revision: 0,
    });
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => ({
      ok: true,
      status: 200,
      json: async () => String(input).includes('type=image') ? [gatewayModels[0]] : [gatewayModels[1]],
    }) as Response));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('returns the current node config and compatible model catalog', async () => {
    useCanvasStore.getState().updateNode('node-1', {
      model: 'image-model',
      gatewayParams: { size: '2K', n: 3 },
      size: '2K',
      n: 3,
    });

    const result = await getNodeModelConfig(context, { nodeId: 'node-1' });

    expect(result.ok).toBe(true);
    expect(result.result).toMatchObject({
      modelType: 'image',
      current: {
        model: 'image-model',
        params: { size: '2K', n: 3 },
      },
    });
    expect(result.result?.models.map((model) => model.value)).toEqual(['image-model']);
  });

  it('resets defaults on model change and then applies explicit overrides', async () => {
    const result = await setNodeModelConfig(context, {
      nodeId: 'node-1',
      model: 'image-model',
      params: {
        n: 4,
        style: { style_type: 'anime', style_weight: 7 },
      },
    });

    expect(result.ok).toBe(true);
    expect(useCanvasStore.getState().nodes[0].data).toMatchObject({
      model: 'image-model',
      size: '1K',
      n: 4,
      gatewayParams: {
        size: '1K',
        n: 4,
        style: { style_type: 'anime', style_weight: 7 },
      },
    });
  });

  it('merges partial changes when the selected model does not change', async () => {
    useCanvasStore.getState().updateNode('node-1', {
      model: 'image-model',
      size: '1K',
      n: 2,
      gatewayParams: { size: '1K', n: 2 },
    });

    const result = await setNodeModelConfig(context, {
      nodeId: 'node-1',
      params: { n: 3 },
    });

    expect(result.ok).toBe(true);
    expect(useCanvasStore.getState().nodes[0].data).toMatchObject({
      model: 'image-model',
      size: '1K',
      n: 3,
      gatewayParams: { size: '1K', n: 3 },
    });
  });

  it('resets the current model to gateway defaults on request', async () => {
    useCanvasStore.getState().updateNode('node-1', {
      model: 'image-model',
      size: '2K',
      n: 4,
      gatewayParams: { size: '2K', n: 4 },
    });

    const result = await setNodeModelConfig(context, {
      nodeId: 'node-1',
      resetToDefaults: true,
    });

    expect(result).toMatchObject({ ok: true, result: { reset: true } });
    expect(useCanvasStore.getState().nodes[0].data).toMatchObject({
      size: '1K',
      n: 2,
      gatewayParams: { size: '1K', n: 2 },
    });
  });

  it('does not mutate the node during dryRun', async () => {
    const result = await setNodeModelConfig({ ...context, dryRun: true }, {
      nodeId: 'node-1',
      model: 'image-model',
      params: { n: 1 },
    });

    expect(result.status).toBe('preview');
    expect(result.result).toMatchObject({ model: 'image-model', params: { n: 1, size: '1K' } });
    expect(useCanvasStore.getState().nodes[0].data.model).toBeUndefined();
  });

  it('blocks writes when the gateway catalog is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false,
      status: 503,
      json: async () => ({ message: 'gateway unavailable' }),
    }) as Response));

    const result = await setNodeModelConfig(context, {
      nodeId: 'node-1',
      model: 'image-model',
    });

    expect(result).toMatchObject({
      ok: false,
      error: { code: 'MODEL_CATALOG_UNAVAILABLE' },
    });
  });

  it('returns stored configuration with a warning when the catalog is unavailable', async () => {
    useCanvasStore.getState().updateNode('node-1', {
      model: 'saved-model',
      gatewayParams: { size: '2K' },
      size: '2K',
    });
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false,
      status: 503,
      json: async () => ({ message: 'gateway unavailable' }),
    }) as Response));

    const result = await getNodeModelConfig(context, { nodeId: 'node-1' });

    expect(result).toMatchObject({
      ok: true,
      result: {
        current: { model: 'saved-model', params: { size: '2K' } },
        warnings: [expect.stringContaining('MODEL_CATALOG_UNAVAILABLE')],
      },
    });
  });

  it('rejects model config for unsupported nodes', async () => {
    useCanvasStore.setState({
      nodes: [{
        ...node(),
        id: 'script-1',
        type: 'script',
        data: { kind: 'script', label: '剧本', prompt: '', status: 'idle', accent: '#fff' },
      }],
    });

    const result = await getNodeModelConfig(context, { nodeId: 'script-1' });

    expect(result).toMatchObject({
      ok: false,
      error: { code: 'NODE_MODEL_CONFIG_UNSUPPORTED' },
    });
  });

  it('rejects a model that is not in the node action catalog', async () => {
    const result = await setNodeModelConfig(context, {
      nodeId: 'node-1',
      model: 'video-model',
    });

    expect(result).toMatchObject({
      ok: false,
      error: { code: 'MODEL_NOT_AVAILABLE' },
    });
  });
});
