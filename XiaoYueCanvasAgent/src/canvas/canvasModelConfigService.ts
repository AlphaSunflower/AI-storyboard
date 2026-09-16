import { apiJson } from '../services/morrowApi';
import { useCanvasStore } from '../store/canvasStore';
import type { CanvasNode } from '../types';
import { check, result } from './canvasTransaction';
import { failure } from './canvasErrors';
import type { CanvasCommandContext, CanvasCommandResult } from './canvasTypes';
import {
  buildModelCatalog,
  buildModelDataPatch,
  defaultsForModel,
  modelTypeForNode,
  ModelConfigValidationError,
  type ModelConfigModel,
} from './modelConfig';

export interface NodeModelConfigRead {
  nodeId: string;
  kind: string;
  modelType: 'image' | 'video';
  current: {
    model: string;
    params: Record<string, unknown>;
  };
  models: ModelConfigModel[];
  warnings: string[];
}

export interface NodeModelConfigWrite {
  nodeId: string;
  model: string;
  params: Record<string, unknown>;
  reset: boolean;
  changed: boolean;
}

const LEGACY_KEYS: Record<string, string> = {
  size: 'size',
  quality: 'quality',
  style: 'style',
  aspectRatio: 'aspectRatio',
  aspect_ratio: 'aspectRatio',
  n: 'n',
  duration: 'duration',
  resolution: 'resolution',
};

const storedParams = (node: CanvasNode): Record<string, unknown> => {
  const gateway = node.data.gatewayParams && typeof node.data.gatewayParams === 'object'
    ? node.data.gatewayParams as Record<string, unknown>
    : {};
  const legacy = Object.fromEntries(Object.entries(LEGACY_KEYS).flatMap(([fieldKey, dataKey]) => {
    const value = node.data[dataKey];
    return value === undefined ? [] : [[fieldKey, value]];
  }));
  return { ...legacy, ...gateway };
};

const resolveNode = (context: CanvasCommandContext, nodeId: string): CanvasNode | CanvasCommandResult => {
  const invalid = check(context);
  if (invalid) return invalid;
  const node = useCanvasStore.getState().nodes.find((candidate) => candidate.id === nodeId);
  if (!node) return failure('NODE_NOT_FOUND', '节点不存在', { nodeId });
  if (!modelTypeForNode(node.data.kind)) {
    return failure('NODE_MODEL_CONFIG_UNSUPPORTED', '该节点不支持模型参数配置', { nodeId, kind: node.data.kind });
  }
  return node;
};

const fetchCatalog = async (type: 'image' | 'video'): Promise<ModelConfigModel[]> => {
  const models = await apiJson<unknown[]>(`/models?type=${type}`);
  return buildModelCatalog(Array.isArray(models) ? models : []);
};

export async function getNodeModelConfig(
  context: CanvasCommandContext,
  params: { nodeId: string },
): Promise<CanvasCommandResult<NodeModelConfigRead>> {
  const resolved = resolveNode(context, params.nodeId);
  if ('ok' in resolved) return resolved as CanvasCommandResult<NodeModelConfigRead>;
  const node = resolved;
  const modelType = modelTypeForNode(node.data.kind)!;
  const warnings: string[] = [];
  let models: ModelConfigModel[] = [];
  try {
    models = await fetchCatalog(modelType);
  } catch (error) {
    warnings.push(`MODEL_CATALOG_UNAVAILABLE: ${error instanceof Error ? error.message : '模型目录不可用'}`);
  }
  const currentModelId = typeof node.data.model === 'string' ? node.data.model : '';
  const currentModel = models.find((model) => model.value === currentModelId)
    ?? models.find((model) => model.isDefault)
    ?? models[0];
  const stored = storedParams(node);
  const currentParams = currentModel
    ? Object.fromEntries(currentModel.fields.flatMap((field) => {
      const value = stored[field.key] ?? currentModel.defaults[field.key];
      return value === undefined ? [] : [[field.key, value]];
    }))
    : stored;
  return {
    ok: true,
    status: 'applied',
    revision: useCanvasStore.getState().revision,
    result: {
      nodeId: node.id,
      kind: node.data.kind,
      modelType,
      current: {
        model: currentModel?.value ?? currentModelId,
        params: currentParams,
      },
      models,
      warnings,
    },
  };
}

export async function setNodeModelConfig(
  context: CanvasCommandContext,
  params: {
    nodeId: string;
    model?: string;
    params?: Record<string, unknown>;
    resetToDefaults?: boolean;
  },
): Promise<CanvasCommandResult<NodeModelConfigWrite>> {
  const resolved = resolveNode(context, params.nodeId);
  if ('ok' in resolved) return resolved as CanvasCommandResult<NodeModelConfigWrite>;
  const node = resolved;
  const modelType = modelTypeForNode(node.data.kind)!;
  let models: ModelConfigModel[];
  try {
    models = await fetchCatalog(modelType);
  } catch (error) {
    return failure(
      'MODEL_CATALOG_UNAVAILABLE',
      error instanceof Error ? error.message : '模型目录不可用',
      { modelType },
    );
  }
  const currentModelId = typeof node.data.model === 'string' ? node.data.model : '';
  const targetModelId = params.model?.trim()
    || currentModelId
    || models.find((model) => model.isDefault)?.value
    || models[0]?.value;
  const targetModel = models.find((model) => model.value === targetModelId);
  if (!targetModel) {
    return failure('MODEL_NOT_AVAILABLE', '指定模型不在当前节点可用目录中', {
      model: targetModelId,
      modelType,
    });
  }
  const reset = params.resetToDefaults === true
    || !currentModelId
    || currentModelId !== targetModel.value;
  const base = reset ? defaultsForModel(targetModel) : storedParams(node);
  try {
    const { values, patch } = buildModelDataPatch(targetModel, base, params.params ?? {});
    const previous = storedParams(node);
    const changed = JSON.stringify(previous) !== JSON.stringify(values)
      || currentModelId !== targetModel.value;
    const value: NodeModelConfigWrite = {
      nodeId: node.id,
      model: targetModel.value,
      params: values,
      reset,
      changed,
    };
    return result(
      context,
      { affectedNodeIds: [node.id], description: `更新节点模型参数：${targetModel.label}` },
      value,
      () => useCanvasStore.getState().updateNode(node.id, patch),
    );
  } catch (error) {
    if (error instanceof ModelConfigValidationError) {
      return failure(error.code, error.message, { path: error.path });
    }
    throw error;
  }
}
