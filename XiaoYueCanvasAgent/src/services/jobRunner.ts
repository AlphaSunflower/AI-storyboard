import { jobAdapter } from './capabilities';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { useAgentStore } from '../store/agentStore';
import { createId } from '../utils/id';
import { getAsset, listGenerationQueueItems, putGenerationQueueItem, saveAsset } from './projectStorage';
import { createImageAsset, createVideoAsset, getMediaNodeSize, getVideoNodeSize } from '../utils/mediaPipeline';
import type { JobEvent, PersistedAsset } from '../types';
import { materializeJobAsset } from '../canvas/generationRuntime';
import { getNodeDefinition, validateRunnable, type CanvasGraphV2, type GenerationRecord } from '../canvas/graphContracts';
import { acquireGenerationSlot, withGlobalSchedulerLock } from '../canvas/generationQueue';
import { buildGenerationSources } from '../canvas/generationRecords';
import {
  resolveResultSlots,
  validateResultSlotOwnership,
  type ResultSlotBinding,
} from '../canvas/resultSlots';
import { resolveStoryboardReferenceAssetIds, type StoryboardReferenceCatalogItem } from '../canvas/storyboardReferences';
import { requestAutoLayout } from '../canvas/layout/autoLayout';
import {
  linkGeneratedCanvasAssets,
  listCanvasAssets,
  uploadCanvasAsset,
  type CanvasAssetDescriptor,
} from './canvasAssets';

const eventNumber = (eventId?: string) => {
  const value = Number(eventId?.split(':').at(-1));
  return Number.isFinite(value) ? value : undefined;
};

const terminalStatuses = new Set<GenerationRecord['status']>(['completed', 'failed', 'cancelled', 'interrupted']);

const markUnfilledResultSlots = (
  record: GenerationRecord,
  phase: 'blocked' | 'failed',
  error: string,
) => {
  const filled = new Set(Object.values(record.resultBindings ?? {}));
  for (const slot of record.resultSlots ?? []) {
    if (filled.has(slot.nodeId)) continue;
    const node = useCanvasStore.getState().nodes.find((candidate) => candidate.id === slot.nodeId);
    if (!node || node.data.assetId) continue;
    useCanvasStore.getState().updateNode(slot.nodeId, {
      automation: {
        ...(typeof node.data.automation === 'object' && node.data.automation ? node.data.automation : {}),
        phase,
        error,
      },
    });
  }
};

export async function cancelCanvasGeneration(recordId: string) {
  const record = useCanvasStore.getState().generationRecords.find((item) => item.id === recordId);
  if (!record || terminalStatuses.has(record.status)) return;
  const queueItem = (await listGenerationQueueItems()).find((item) => item.id === recordId);
  const jobId = record.jobId ?? queueItem?.jobId;
  if (jobId) await jobAdapter.cancel(jobId);
  const cancelled: GenerationRecord = {
    ...record,
    status: 'cancelled',
    queueStatus: 'cancelled',
    materializationStatus: record.materializationStatus === 'completed' ? 'completed' : 'failed',
    materializationError: record.materializationStatus === 'completed' ? undefined : '任务已取消',
    completedAt: Date.now(),
  };
  useCanvasStore.getState().updateGenerationRecord(cancelled);
  useCanvasStore.getState().updateNode(record.producerNodeId, { isGenerating: false, progressPercent: 0 });
  if (queueItem) {
    await putGenerationQueueItem({
      ...queueItem,
      status: 'cancelled',
      error: undefined,
      updatedAt: Date.now(),
    });
  }
}

// 参考素材（图/视频）assetId → data URI（前端本地 Blob 资产 → 后端可直接消费的 data: 前缀）。
// 仅 ≤10MB 转（免得 base64 膨胀爆 64MB 请求体）；>10MB 留 sourceAssetIds 让后端 decide（query 本身需参考素材）。
export const blobToDataURI = (blob: Blob): Promise<string | null> =>
  new Promise((resolve) => {
    if (blob.size > 10 * 1024 * 1024) return resolve(null);
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : null);
    reader.onerror = () => resolve(null);
    reader.readAsDataURL(blob);
  });

// 把当次提交的源资产读取为数据库资产 ID；本地 Blob 先上传 MinIO 并回写画布节点 ID。
export interface ReferenceDataUriEntry {
  assetId: string;
  kind: 'image' | 'video';
  uri: string;
}

export const remapReferenceRoleAssetIds = (
  roles: unknown[],
  assetIdMap: Record<string, string>,
): unknown[] => roles.map((entry) => {
  if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return entry;
  const roleEntry = entry as Record<string, unknown>;
  const sourceAssetId = typeof roleEntry.assetId === 'string' ? roleEntry.assetId : undefined;
  const persistedAssetId = sourceAssetId ? assetIdMap[sourceAssetId] : undefined;
  return persistedAssetId ? { ...roleEntry, assetId: persistedAssetId } : roleEntry;
});

export const collectReferenceDataUris = async (
  sourceAssetIds: string[],
  onEntry?: (entry: ReferenceDataUriEntry) => void,
  signal?: AbortSignal,
) => {
  const images: string[] = [];
  const videos: string[] = [];
  const assetIdMap: Record<string, string> = {};
  const canvas = useCanvasStore.getState();
  const canvasId = canvas.canvasId;
  if (!canvasId) throw new Error('画布尚未保存，无法持久化参考素材');
  const known = new Map(
    (await listCanvasAssets(canvasId)).map((asset) => [asset.id, asset]),
  );

  const attachDatabaseAsset = async (
    sourceAssetId: string,
    descriptor: CanvasAssetDescriptor,
  ): Promise<string> => {
    if (sourceAssetId === descriptor.id) return descriptor.id;
    const local = useMediaStore.getState().assets[sourceAssetId] ?? await getAsset(sourceAssetId).catch(() => undefined);
    if (local) {
      const migrated = { ...local, id: descriptor.id };
      await saveAsset(migrated);
      useMediaStore.getState().upsertAsset(migrated);
    }
    for (const node of useCanvasStore.getState().nodes) {
      const patch: Record<string, string> = {};
      if (node.data.assetId === sourceAssetId) patch.assetId = descriptor.id;
      if (node.data.firstFrameAssetId === sourceAssetId) patch.firstFrameAssetId = descriptor.id;
      if (node.data.lastFrameAssetId === sourceAssetId) patch.lastFrameAssetId = descriptor.id;
      if (Object.keys(patch).length) useCanvasStore.getState().updateNode(node.id, patch);
    }
    return descriptor.id;
  };

  for (const aid of sourceAssetIds) {
    signal?.throwIfAborted();
    const asset = useMediaStore.getState().assets[aid] ?? await getAsset(aid).catch(() => undefined);
    if (!asset) continue;
    if (!useMediaStore.getState().assets[aid]) useMediaStore.getState().upsertAsset(asset);
    const blob = asset.previewBlob ?? asset.originalBlob ?? asset.thumbnailBlob;
    if (!blob) continue;
    let descriptor = known.get(aid);
    if (!descriptor) {
      const producerNodeId = useCanvasStore.getState().nodes
        .find((node) => node.data.assetId === aid)?.id
        ?? useCanvasStore.getState().selectedNodeId
        ?? '';
      descriptor = await uploadCanvasAsset(
        canvasId,
        producerNodeId,
        asset.kind,
        blob,
        asset.name,
      );
      known.set(descriptor.id, descriptor);
    }
    const assetId = await attachDatabaseAsset(aid, descriptor);
    assetIdMap[aid] = assetId;
    if (asset.kind === 'video') {
      videos.push(assetId);
      onEntry?.({ assetId, kind: 'video', uri: descriptor.url });
    } else {
      images.push(assetId);
      onEntry?.({ assetId, kind: 'image', uri: descriptor.url });
    }
  }
  return { images, videos, assetIdMap };
};

// 生图：把执行服务返回的图片落到目标（空图片）节点，带完整溯源。
const materializeLegacyGeneration = async (event: Extract<JobEvent, { type: 'asset.created' }>, targetNodeId: string) => {
  const currentCanvasId = useCanvasStore.getState().canvasId;
  if (!currentCanvasId) throw new Error('画布尚未保存，无法映射生成结果');
  const [persisted] = await linkGeneratedCanvasAssets(currentCanvasId, targetNodeId, event.asset.id);
  if (!persisted) throw new Error('生成结果未建立画布资产映射');
  const file = await jobAdapter.fetchAsset({ ...event.asset, id: persisted.id, mimeType: persisted.mimeType });
  const operation = { type: 'remote' as const, params: { jobId: event.jobId, remoteAssetId: event.asset.id, capabilityId: 'image.generate' } };
  const asset: PersistedAsset = file.type.startsWith('video/')
    ? await createVideoAsset(file, persisted.id)
    : await createImageAsset(file, persisted.id, { operation });
  asset.operation = operation;
  const { projectId, canvasId } = useCanvasStore.getState();
  asset.projectId = projectId ?? undefined;
  asset.canvasId = canvasId ?? undefined;
  await saveAsset(asset);
  useMediaStore.getState().upsertAsset(asset);
  const label = file.name.replace(/\.[^.]+$/, '') || '生成图片';
  const size = asset.kind === 'video' ? getVideoNodeSize(asset.width, asset.height) : getMediaNodeSize(asset.width, asset.height);
  useCanvasStore.getState().attachAssetToNode(targetNodeId, { assetId: asset.id, label, size });
  return asset;
};

// 生图/生视频/分镜的通用提交（capability 由调用方指定，params 透传生成参数 + 参考图源资产）。
export interface GenerationTerminalOutcome {
  status: 'succeeded' | 'failed' | 'cancelled';
  jobId: string;
  recordId?: string;
  error?: string;
}

export interface GenSubmitOpts {
  sourceAssetIds?: string[];
  params?: Record<string, unknown>;
  capabilityId?: string;
  batchId?: string;
  itemId?: string;
  sourceNodeIds?: string[];
  skipSlot?: boolean;
  resultSlots?: ResultSlotBinding[];
  onTerminal?: (outcome: GenerationTerminalOutcome) => void;
  signal?: AbortSignal;
}
const submitGen = async (text: string, targetNodeId: string, onFeedback: (message: string) => void, capabilityId: string, opts: GenSubmitOpts = {}) => {
  opts.signal?.throwIfAborted();
  const trimmed = text.trim();
  const canvas = useCanvasStore.getState();
  const projectId = canvas.projectId ?? 'local-project';
  const capabilities = await jobAdapter.getCapabilities(opts.signal);
  const capability = capabilities.find((item) => item.id === capabilityId);
  if (!capability?.available) throw new Error(capability?.unavailableReason ?? '未配置对应执行服务');

  const conversationId = useAgentStore.getState().ensureSession(projectId).conversationId;
  // 参考素材（前端本地 Blob 资产）→ data URI，合并进 params.referenceImages/referenceVideos 供后端直接消费
  //（后端 agent_assets 表无前端资产 id，sourceAssetIds selectById 查不到 → 图生图/图生视频参考图会丢失）
  const sourceAssetIds = opts.sourceAssetIds ?? [];
  const targetNode = canvas.nodes.find((node) => node.id === targetNodeId);
  let resultSlots: ResultSlotBinding[] = [];
  const actionId = String(opts.params?.actionId ?? (capabilityId === 'video.generate' ? 'generateVideo' : 'generate'));
  const action = getNodeDefinition(targetNode?.data.kind)?.actions.find((item) => item.id === actionId);
  const effectiveCapabilityId = opts.capabilityId ?? action?.capabilityId ?? capabilityId;
  const effectiveCapability = capabilities.find((item) => item.id === effectiveCapabilityId);
  if (!effectiveCapability?.available) throw new Error(effectiveCapability?.unavailableReason ?? '未配置对应执行服务');
  if (targetNode) {
    const graph: CanvasGraphV2 = {
      schemaVersion: 2,
      nodes: canvas.nodes as CanvasGraphV2['nodes'],
      edges: canvas.edges as CanvasGraphV2['edges'],
      viewport: { x: 0, y: 0, zoom: 1 },
      generationRecords: canvas.generationRecords,
    };
    const runnable = validateRunnable(graph, targetNodeId, actionId);
    if (!runnable.ok) throw new Error(runnable.message ?? '节点输入不完整，暂时不能运行');
    resultSlots = opts.resultSlots ?? resolveResultSlots(graph, targetNodeId);
    const slotConflict = validateResultSlotOwnership(graph, targetNodeId, resultSlots);
    if (!slotConflict.ok) throw new Error(slotConflict.message ?? '结果槽配置冲突');
  }
  const isMaterializedResult = Boolean(
    getNodeDefinition(targetNode?.data.kind)?.actions.some((action) =>
      action.capabilityId === effectiveCapabilityId && action.resultPolicy === 'media-nodes'),
  );
  const roleEntries = Array.isArray(opts.params?.referenceRoles)
    ? opts.params.referenceRoles.filter((item): item is { assetId?: unknown; role?: unknown } => Boolean(item && typeof item === 'object'))
    : [];
  const roleByAssetId = Object.fromEntries(roleEntries.flatMap((item) =>
    typeof item.assetId === 'string' && typeof item.role === 'string' ? [[item.assetId, item.role]] : []));
  const sources = buildGenerationSources({
    sourceAssetIds,
    sourceNodeIds: opts.sourceNodeIds,
    roleByAssetId,
    nodes: canvas.nodes,
    fallbackRole: typeof opts.params?.sourceRole === 'string' ? opts.params.sourceRole : 'source',
  });
  const generationRecord: GenerationRecord = {
    id: `generation-${createId()}`,
    batchId: opts.batchId,
    itemId: opts.itemId,
    producerNodeId: targetNodeId,
    actionId,
    attempt: 1,
    status: 'queued',
    progress: 0,
    queueStatus: 'pending_submit',
    materializationStatus: 'pending',
    materializedAssetIds: [],
    resultNodeIds: [],
    resultSlots,
    resultBindings: {},
    sources,
    prompt: trimmed,
    effectivePrompt: trimmed,
    params: { ...(opts.params ?? {}), capabilityId: effectiveCapabilityId },
    model: typeof opts.params?.model === 'string' ? opts.params.model : undefined,
    startedAt: Date.now(),
  };
  if (isMaterializedResult) useCanvasStore.getState().appendGenerationRecord(generationRecord);
  const queueId = generationRecord.id;
  const refUris = sourceAssetIds.length
    ? await collectReferenceDataUris(sourceAssetIds, undefined, opts.signal)
    : { images: [], videos: [], assetIdMap: {} };
  const remappedReferenceRoles = Array.isArray(opts.params?.referenceRoles)
    ? remapReferenceRoleAssetIds(opts.params.referenceRoles, refUris.assetIdMap)
    : undefined;
  const mergedParams: Record<string, unknown> = {
    prompt: trimmed,
    ...(opts.params ?? {}),
    ...(remappedReferenceRoles ? { referenceRoles: remappedReferenceRoles } : {}),
    canvas: {
      canvasId: canvas.canvasId ?? '',
      producerNodeId: targetNodeId,
      batchId: opts.batchId,
      sourceRole: opts.params?.sourceRole,
    },
    ...(refUris.images.length || refUris.videos.length
      ? { referenceAssetIds: [...refUris.images, ...refUris.videos] }
      : {}),
  };
  const persistQueue = (status: 'pending_submit' | 'running' | 'terminal' | 'interrupted', jobId?: string, error?: string) => {
    if (!isMaterializedResult) return;
    void putGenerationQueueItem({
      id: queueId,
      projectId,
      canvasId: canvas.canvasId ?? '',
      producerNodeId: targetNodeId,
      generationRecordId: generationRecord.id,
      requestSnapshot: { capabilityId: effectiveCapabilityId, sourceAssetIds, params: mergedParams },
      status,
      jobId,
      attempt: generationRecord.attempt,
      error,
      createdAt: generationRecord.startedAt,
      updatedAt: Date.now(),
    });
  };
  persistQueue('pending_submit');
  let slotReleased = false;
  let releaseSlot = () => {};
  const releaseExecutionSlot = () => {
    if (slotReleased) return;
    slotReleased = true;
    releaseSlot();
  };
  if (!opts.skipSlot) releaseSlot = await acquireGenerationSlot();
  let jobId: string;
  try {
    ({ jobId } = await jobAdapter.submit({
      clientRequestId: createId(),
      conversationId,
      capabilityId: effectiveCapabilityId,
      sourceAssetIds,
      context: { projectId, nodeIds: canvas.nodes.map((node) => node.id), selectedNodeId: targetNodeId },
      params: mergedParams,
    }, opts.signal));
  } catch (error) {
    releaseExecutionSlot();
    const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id);
    if (current) {
      useCanvasStore.getState().updateGenerationRecord({
        ...current,
        status: 'failed',
        queueStatus: 'interrupted',
        error: error instanceof Error ? error.message : '任务提交失败',
        completedAt: Date.now(),
      });
    }
    persistQueue('interrupted', undefined, error instanceof Error ? error.message : '任务提交失败');
    throw error;
  }

  useAgentStore.getState().putJob(projectId, { id: jobId, prompt: trimmed.slice(0, 24), sourceNodeId: targetNodeId, capabilityId: effectiveCapabilityId, status: 'queued', progress: 0, createdAt: Date.now() });
  const notifyTerminal = (status: GenerationTerminalOutcome['status'], error?: string) => {
    opts.onTerminal?.({
      status,
      jobId,
      recordId: useCanvasStore.getState().generationRecords.find((record) => record.jobId === jobId)?.id ?? generationRecord.id,
      error,
    });
  };
  if (isMaterializedResult) {
    useCanvasStore.getState().updateGenerationRecord({
      ...generationRecord,
      jobId,
      status: 'running',
      queueStatus: 'running',
      progress: 0,
    });
    persistQueue('running', jobId);
  }
  // 立即置生成态（不依赖 job.started 事件顺序），completed/failed/asset.created 时复位
  useCanvasStore.getState().updateNode(targetNodeId, { isGenerating: true, generationStartedAt: Date.now(), progressPercent: 0 });

  const seenAssets = new Set<string>();
  let assetQueue = Promise.resolve();
  const unsubscribe = jobAdapter.subscribe(jobId, (event) => {
    if (event.type === 'asset.created') {
      const dedupeKey = `${event.jobId}:${event.asset.id}`;
      if (seenAssets.has(dedupeKey)) return;
      seenAssets.add(dedupeKey);
      if (isMaterializedResult) {
        assetQueue = assetQueue.then(async () => {
          const currentRecord = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
          try {
            const settlement = await materializeJobAsset(currentRecord, {
              assetId: event.asset.id,
              name: event.asset.name,
              mimeType: event.asset.mimeType,
              downloadUrl: event.asset.downloadUrl,
            }, jobId);
            useAgentStore.getState().updateJob(projectId, jobId, { status: 'completed', progress: 1 });
            useCanvasStore.getState().updateNode(targetNodeId, { isGenerating: false, progressPercent: 0 });
            if (settlement.record.status === 'completed') {
              useCanvasStore.getState().updateGenerationRecord({ ...settlement.record, status: 'completed' });
            }
          } catch (error) {
            useAgentStore.getState().updateJob(projectId, jobId, { status: 'failed' });
            useCanvasStore.getState().updateNode(targetNodeId, { isGenerating: false, progressPercent: 0 });
            onFeedback(error instanceof Error ? error.message : '生成结果落地失败');
          }
        });
      } else {
        void materializeLegacyGeneration(event, targetNodeId)
        .then(() => { useAgentStore.getState().updateJob(projectId, jobId, { status: 'completed', progress: 1 }); useCanvasStore.getState().updateNode(targetNodeId, { isGenerating: false, progressPercent: 0 }); })
        .catch((error) => { useAgentStore.getState().updateJob(projectId, jobId, { status: 'failed' }); useCanvasStore.getState().updateNode(targetNodeId, { isGenerating: false, progressPercent: 0 }); onFeedback(error instanceof Error ? error.message : '生成结果落地失败'); });
      }
    } else if (event.type === 'job.progress') {
      const raw = Math.max(0, Math.min(1, event.progress > 1 ? event.progress / 100 : event.progress));
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'running', progress: raw });
      useCanvasStore.getState().updateNode(targetNodeId, { progressPercent: Math.round(raw * 100) });
      if (isMaterializedResult) {
        const currentRecord = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
        useCanvasStore.getState().updateGenerationRecord({
          ...currentRecord,
          status: 'running',
          queueStatus: 'running',
          progress: raw,
          lastEventSeq: eventNumber(event.eventId),
        });
      }
    } else if (event.type === 'job.started' || event.type === 'job.queued') {
      useCanvasStore.getState().updateNode(targetNodeId, { isGenerating: true });
      if (isMaterializedResult) {
        const currentRecord = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
        useCanvasStore.getState().updateGenerationRecord({
          ...currentRecord,
          status: event.type === 'job.started' ? 'running' : 'queued',
          queueStatus: event.type === 'job.started' ? 'running' : 'queued',
          lastEventSeq: eventNumber(event.eventId),
        });
      }
    } else if (event.type === 'job.failed') {
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'failed' });
      useCanvasStore.getState().updateNode(targetNodeId, { isGenerating: false, progressPercent: 0 });
      onFeedback(event.message);
      if (isMaterializedResult) {
        const currentRecord = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
        markUnfilledResultSlots(currentRecord, 'failed', event.message);
        useCanvasStore.getState().updateGenerationRecord({
          ...currentRecord,
          status: event.message.includes('中断') ? 'interrupted' : 'failed',
          queueStatus: event.message.includes('中断') ? 'interrupted' : 'terminal',
          error: event.message,
          completedAt: Date.now(),
          lastEventSeq: eventNumber(event.eventId),
        });
      }
      releaseExecutionSlot();
      persistQueue('interrupted', jobId, event.message);
      notifyTerminal('failed', event.message);
    } else if (event.type === 'job.completed') {
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'completed', progress: 1 });
      // 兜底清生成态：正常由 asset.created 清，但 materializeGeneration 慢/挂起时 job.completed 可能先到，
      // 不处理会让节点永久卡"生成中 95%"。失败已由 job.failed 清。
      useCanvasStore.getState().updateNode(targetNodeId, { isGenerating: false, progressPercent: 0 });
      if (isMaterializedResult) {
        const currentRecord = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
        void assetQueue.finally(() => {
          const latest = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? currentRecord;
          markUnfilledResultSlots(latest, 'blocked', '生成结果数量不足，结果槽未填充');
        });
        useCanvasStore.getState().updateGenerationRecord({
          ...currentRecord,
          status: 'completed',
          queueStatus: 'terminal',
          progress: 1,
          completedAt: Date.now(),
          lastEventSeq: eventNumber(event.eventId),
        });
      }
      releaseExecutionSlot();
      persistQueue('terminal', jobId);
      notifyTerminal('succeeded');
    } else if (event.type === 'job.cancelled') {
      if (isMaterializedResult) {
        const currentRecord = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
        markUnfilledResultSlots(currentRecord, 'failed', '任务已取消');
        useCanvasStore.getState().updateGenerationRecord({
          ...currentRecord,
          status: 'cancelled',
          queueStatus: 'cancelled',
          materializationStatus: 'failed',
          materializationError: '任务已取消',
          completedAt: Date.now(),
          lastEventSeq: eventNumber(event.eventId),
        });
      }
      releaseExecutionSlot();
      persistQueue('terminal', jobId);
      notifyTerminal('cancelled', '任务已取消');
    }
  });
  // 取消/解绑：subscribe 返回的 unsubscribe 在提交后不再需要 ref（当前无取消入口）。
  // 修复：原在 string jobId 上挂 __unsub 属性（strict 下抛 TypeError），死代码，已删。
  return jobId;
};

/** 提交生图任务：透传生成参数（model/size/aspectRatio/genType）+ 参考图源资产。 */
export const submitImageGeneration = async (prompt: string, targetNodeId: string, onFeedback: (message: string) => void, opts: GenSubmitOpts = {}) => {
  if (!prompt.trim()) throw new Error('请输入生图提示词');
  return submitGen(prompt, targetNodeId, onFeedback, 'image.generate', opts);
};

/** 提交生视频任务（video.generate）：透传 videoMode/首尾帧/视频参考 + model/aspectRatio/resolution。 */
export const submitVideoGeneration = async (prompt: string, targetNodeId: string, onFeedback: (message: string) => void, opts: GenSubmitOpts = {}) => {
  if (!prompt.trim()) throw new Error('请输入生视频提示词');
  return submitGen(prompt, targetNodeId, onFeedback, 'video.generate', opts);
};

export const submitBatchImageRefine = async (
  producerNodeId: string,
  requests: Array<{ itemId: string; nodeId: string; assetId: string; order: number; effectivePrompt: string }>,
  onFeedback: (message: string) => void,
  nodeParams: Record<string, unknown> = {},
) => {
  if (!requests.length) throw new Error('没有可处理的图片');
  const batchId = `batch-${createId()}`;
  const state = useCanvasStore.getState();
  const graph: CanvasGraphV2 = {
    schemaVersion: 2,
    nodes: state.nodes as CanvasGraphV2['nodes'],
    edges: state.edges as CanvasGraphV2['edges'],
    viewport: { x: 0, y: 0, zoom: 1 },
    generationRecords: state.generationRecords,
    executions: state.executions,
  };
  const resultSlots = resolveResultSlots(graph, producerNodeId);
  const execute = async (request: typeof requests[number], index: number) => submitImageGeneration(
    request.effectivePrompt,
    producerNodeId,
    onFeedback,
    {
      ...batchRefineOptions(request, batchId, nodeParams),
      resultSlots: resultSlots[index] ? [resultSlots[index]] : [],
    },
  );
  return withGlobalSchedulerLock(async () => {
    const results: string[] = [];
    for (let index = 0; index < requests.length; index += 2) {
      const chunk = requests.slice(index, index + 2);
    const submitted = await Promise.all(chunk.map((request, offset) => execute(request, index + offset)));
      results.push(...submitted);
    }
    return { batchId, jobIds: results };
  });
};

export const batchRefineOptions = (
  request: {
    itemId: string;
    nodeId: string;
    assetId: string;
    effectivePrompt: string;
  },
  batchId: string,
  nodeParams: Record<string, unknown> = {},
): GenSubmitOpts => ({
  sourceAssetIds: [request.assetId],
  sourceNodeIds: [request.nodeId],
  batchId,
  itemId: request.itemId,
  skipSlot: true,
  params: { ...nodeParams, actionId: 'refineBatch' },
});

// 提交分镜生成任务：把剧本交给设计助理（agent.request），编排器识别分镜意图后生成分镜，
// 经 agent.tool_call / asset.created 事件回落画布（派生节点由 handleJobEvent 处理）。
export const submitScriptDrafting = async (
  scriptText: string,
  targetNodeId: string,
  onFeedback: (message: string) => void,
  opts: Pick<GenSubmitOpts, 'sourceAssetIds' | 'sourceNodeIds' | 'onTerminal' | 'signal'> = {},
) => {
  const text = scriptText.trim();
  if (!text) throw new Error('剧本内容为空，请先输入剧本');
  const canvas = useCanvasStore.getState();
  const projectId = canvas.projectId ?? 'local-project';
  const capabilities = await jobAdapter.getCapabilities(opts.signal);
  const capability = capabilities.find((item) => item.id === 'storyboard.plan');
  if (!capability?.available) throw new Error(capability?.unavailableReason ?? '未配置分镜规划执行服务');

  const conversationId = useAgentStore.getState().ensureSession(projectId).conversationId;
  const sourceAssetIds = opts.sourceAssetIds ?? [];
  const sourceNodeIds = opts.sourceNodeIds ?? [];
  const collectedReferences: ReferenceDataUriEntry[] = [];
  const refUris = sourceAssetIds.length
    ? await collectReferenceDataUris(sourceAssetIds, (entry) => collectedReferences.push(entry), opts.signal)
    : { images: [], videos: [] };
  const referenceUrls = collectedReferences
    .filter((entry) => entry.kind === 'image')
    .map((entry) => entry.uri);
  const referenceCatalog: StoryboardReferenceCatalogItem[] = collectedReferences
    .filter((entry) => entry.kind === 'image')
    .map((entry, index) => ({
      index: index + 1,
      assetId: entry.assetId,
      nodeId: sourceNodeIds[sourceAssetIds.indexOf(entry.assetId)]
        ?? canvas.nodes.find((node) => node.data.assetId === entry.assetId)?.id
        ?? '',
    }));
  const sources = buildGenerationSources({
    sourceAssetIds,
    sourceNodeIds,
    nodes: canvas.nodes,
    fallbackRole: 'reference',
  });
  const generationRecord: GenerationRecord = {
    id: `generation-${createId()}`,
    producerNodeId: targetNodeId,
    actionId: 'plan',
    attempt: 1,
    status: 'queued',
    progress: 0,
    queueStatus: 'pending_submit',
    materializationStatus: 'pending',
    materializedAssetIds: [],
    resultNodeIds: [],
    sources,
    prompt: text,
    effectivePrompt: text,
    params: {
      ...(referenceUrls.length ? { referenceImages: referenceUrls } : {}),
      referenceCatalog,
    },
    startedAt: Date.now(),
  };
  useCanvasStore.getState().appendGenerationRecord(generationRecord);
  const persistQueue = (status: 'pending_submit' | 'running' | 'terminal' | 'interrupted', jobId?: string, error?: string) => {
    void putGenerationQueueItem({
      id: generationRecord.id,
      projectId,
      canvasId: canvas.canvasId ?? '',
      producerNodeId: targetNodeId,
      generationRecordId: generationRecord.id,
      requestSnapshot: {
        capabilityId: 'storyboard.plan',
        sourceAssetIds,
        params: {
          prompt: text,
          ...(referenceUrls.length ? { referenceImages: referenceUrls } : {}),
          referenceCatalog,
        },
      },
      status,
      jobId,
      attempt: generationRecord.attempt,
      error,
      createdAt: generationRecord.startedAt,
      updatedAt: Date.now(),
    });
  };
  persistQueue('pending_submit');
  let jobId: string;
  try {
    ({ jobId } = await jobAdapter.submit({
      clientRequestId: createId(),
      conversationId,
      capabilityId: 'storyboard.plan',
      sourceAssetIds,
      context: { projectId, nodeIds: canvas.nodes.map((node) => node.id), selectedNodeId: targetNodeId },
      params: {
        prompt: text,
        ...(referenceUrls.length ? { referenceImages: referenceUrls } : {}),
        referenceCatalog,
      },
    }, opts.signal));
  } catch (error) {
    const message = error instanceof Error ? error.message : '分镜规划提交失败';
    useCanvasStore.getState().updateGenerationRecord({
      ...generationRecord,
      status: 'failed',
      queueStatus: 'interrupted',
      materializationStatus: 'failed',
      error: message,
      completedAt: Date.now(),
    });
    persistQueue('interrupted', undefined, message);
    throw error;
  }
  useCanvasStore.getState().updateGenerationRecord({
    ...generationRecord,
    jobId,
    status: 'running',
    queueStatus: 'running',
  });
  useCanvasStore.getState().updateNode(targetNodeId, {
    isGenerating: true,
    generationStartedAt: Date.now(),
    progressPercent: 0,
  });
  persistQueue('running', jobId);

  useAgentStore.getState().putJob(projectId, { id: jobId, prompt: `生成分镜：${text.slice(0, 24)}…`, sourceNodeId: targetNodeId, capabilityId: 'storyboard.plan', status: 'queued', progress: 0, createdAt: Date.now() });

  jobAdapter.subscribe(jobId, (event) => {
    if (event.type === 'job.failed') {
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'failed' });
      onFeedback(event.message);
      const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
      useCanvasStore.getState().updateGenerationRecord({
        ...current,
        status: 'failed',
        queueStatus: 'interrupted',
        error: event.message,
        completedAt: Date.now(),
      });
      useCanvasStore.getState().updateNode(targetNodeId, { isGenerating: false, progressPercent: 0 });
      persistQueue('interrupted', jobId, event.message);
      opts.onTerminal?.({ status: 'failed', jobId, recordId: generationRecord.id, error: event.message });
    } else if (event.type === 'job.completed') {
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'completed', progress: 1 });
      const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
      useCanvasStore.getState().updateGenerationRecord({
        ...current,
        status: 'completed',
        queueStatus: 'terminal',
        progress: 1,
        materializationStatus: current.materializationStatus === 'materializing' ? 'materializing' : 'completed',
        completedAt: Date.now(),
      });
      useCanvasStore.getState().updateNode(targetNodeId, { isGenerating: false, progressPercent: 0 });
      persistQueue('terminal', jobId);
      opts.onTerminal?.({ status: 'succeeded', jobId, recordId: generationRecord.id });
    } else if (event.type === 'job.progress') {
      const progress = Math.max(0, Math.min(1, event.progress > 1 ? event.progress / 100 : event.progress));
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'running', progress });
      const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
      useCanvasStore.getState().updateGenerationRecord({ ...current, status: 'running', queueStatus: 'running', progress });
      useCanvasStore.getState().updateNode(targetNodeId, { isGenerating: true, progressPercent: Math.round(progress * 100) });
    } else if (event.type === 'storyboard.created') {
      const state = useCanvasStore.getState();
      const target = state.nodes.find((node) => node.id === targetNodeId);
      const plannerId = target?.data.kind === 'storyboardPlanner'
        ? target.id
        : state.createConnectedNode(targetNodeId, 'storyboardPlanner', {
          x: (target?.position.x ?? 0) + 560,
          y: target?.position.y ?? 0,
        });
      if (plannerId) {
        state.applyStoryboardPlan(plannerId, event.shots.map((shot) => ({
          ...shot,
          referenceAssetIds: resolveStoryboardReferenceAssetIds(shot.referenceHints ?? [], referenceCatalog),
        })));
        void requestAutoLayout([plannerId]);
      }
      const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
      useCanvasStore.getState().updateGenerationRecord({
        ...current,
        materializationStatus: 'completed',
        resultNodeIds: plannerId ? [...new Set([...current.resultNodeIds, plannerId])] : current.resultNodeIds,
      });
    } else if (event.type === 'agent.message') {
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'receiving' });
    } else if (event.type === 'job.cancelled') {
      const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
      useCanvasStore.getState().updateGenerationRecord({
        ...current,
        status: 'cancelled',
        queueStatus: 'cancelled',
        materializationStatus: 'failed',
        materializationError: '任务已取消',
        completedAt: Date.now(),
      });
      useCanvasStore.getState().updateNode(targetNodeId, { isGenerating: false, progressPercent: 0 });
      persistQueue('terminal', jobId);
      opts.onTerminal?.({ status: 'cancelled', jobId, recordId: generationRecord.id, error: '任务已取消' });
    }
  });

  return jobId;
};

// 提交分镜生成任务：把剧本/画面交给设计助理（agent.request），编排器识别分镜意图后生成分镜帧网格，
// 经 agent.tool_call / asset.created 事件回落画布（派生节点由 handleJobEvent 处理）。
export const submitStoryboardGeneration = async (prompt: string, targetNodeId: string, onFeedback: (message: string) => void, config?: { rows?: number; cols?: number; styleType?: string }) => {
  const text = prompt.trim();
  if (!text) throw new Error('请先输入剧本或画面提示词');
  const canvas = useCanvasStore.getState();
  const projectId = canvas.projectId ?? 'local-project';
  const capabilities = await jobAdapter.getCapabilities();
  const capability = capabilities.find((item) => item.id === 'agent.request');
  if (!capability?.available) throw new Error(capability?.unavailableReason ?? '未配置设计助理执行服务');

  const conversationId = useAgentStore.getState().ensureSession(projectId).conversationId;
  const generationRecord: GenerationRecord = {
    id: `generation-${createId()}`,
    producerNodeId: targetNodeId,
    actionId: 'storyboardGrid',
    attempt: 1,
    status: 'queued',
    progress: 0,
    queueStatus: 'pending_submit',
    materializationStatus: 'pending',
    materializedAssetIds: [],
    resultNodeIds: [],
    sources: [],
    prompt: text,
    effectivePrompt: text,
    params: { ...(config ?? {}) },
    startedAt: Date.now(),
  };
  useCanvasStore.getState().appendGenerationRecord(generationRecord);
  const persistQueue = (status: 'pending_submit' | 'running' | 'terminal' | 'interrupted', jobId?: string, error?: string) => {
    void putGenerationQueueItem({
      id: generationRecord.id,
      projectId,
      canvasId: canvas.canvasId ?? '',
      producerNodeId: targetNodeId,
      generationRecordId: generationRecord.id,
      requestSnapshot: {
        capabilityId: 'agent.request',
        sourceAssetIds: [],
        params: { prompt: text, ...(config ?? {}) },
      },
      status,
      jobId,
      attempt: generationRecord.attempt,
      error,
      createdAt: generationRecord.startedAt,
      updatedAt: Date.now(),
    });
  };
  persistQueue('pending_submit');
  let jobId: string;
  try {
    ({ jobId } = await jobAdapter.submit({
      clientRequestId: createId(),
      conversationId,
      capabilityId: 'agent.request',
      sourceAssetIds: [],
      context: { projectId, nodeIds: canvas.nodes.map((node) => node.id), selectedNodeId: targetNodeId },
      params: {
        prompt: `请根据以下剧本生成分镜帧网格（每帧含画面描述与生图提示词，${config?.rows ?? 2} 行 × ${config?.cols ?? 3} 列，风格 ${config?.styleType ?? '2d-anime'}）：\n\n${text}`,
      },
    }));
  } catch (error) {
    const message = error instanceof Error ? error.message : '分镜生成提交失败';
    useCanvasStore.getState().updateGenerationRecord({
      ...generationRecord,
      status: 'failed',
      queueStatus: 'interrupted',
      materializationStatus: 'failed',
      error: message,
      completedAt: Date.now(),
    });
    persistQueue('interrupted', undefined, message);
    throw error;
  }
  useCanvasStore.getState().updateGenerationRecord({
    ...generationRecord,
    jobId,
    status: 'running',
    queueStatus: 'running',
  });
  persistQueue('running', jobId);

  useAgentStore.getState().putJob(projectId, { id: jobId, prompt: `生成分镜：${text.slice(0, 24)}…`, sourceNodeId: targetNodeId, capabilityId: 'agent.request', status: 'queued', progress: 0, createdAt: Date.now() });

  const seenAssets = new Set<string>();
  let assetQueue = Promise.resolve();
  jobAdapter.subscribe(jobId, (event) => {
    if (event.type === 'job.queued') {
      const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
      useCanvasStore.getState().updateGenerationRecord({ ...current, status: 'queued', queueStatus: 'queued' });
    } else if (event.type === 'job.started') {
      const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
      useCanvasStore.getState().updateGenerationRecord({ ...current, status: 'running', queueStatus: 'running' });
    } else if (event.type === 'asset.created') {
      const dedupeKey = `${event.jobId}:${event.asset.id}`;
      if (seenAssets.has(dedupeKey)) return;
      seenAssets.add(dedupeKey);
      assetQueue = assetQueue.then(async () => {
        const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
        await materializeJobAsset(current, {
          assetId: event.asset.id,
          name: event.asset.name,
          mimeType: event.asset.mimeType,
          downloadUrl: event.asset.downloadUrl,
        }, jobId);
      }).catch((error) => {
        const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
        useCanvasStore.getState().updateGenerationRecord({
          ...current,
          materializationStatus: 'failed',
          materializationError: error instanceof Error ? error.message : '结果落地失败',
        });
      });
    } else if (event.type === 'job.failed') {
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'failed' });
      onFeedback(event.message);
      const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
      useCanvasStore.getState().updateGenerationRecord({
        ...current,
        status: 'failed',
        queueStatus: 'interrupted',
        error: event.message,
        completedAt: Date.now(),
      });
      persistQueue('interrupted', jobId, event.message);
    } else if (event.type === 'job.completed') {
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'completed', progress: 1 });
      const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
      useCanvasStore.getState().updateGenerationRecord({
        ...current,
        status: 'completed',
        queueStatus: 'terminal',
        progress: 1,
        materializationStatus: current.materializationStatus === 'materializing' ? 'materializing' : 'completed',
        completedAt: Date.now(),
      });
      persistQueue('terminal', jobId);
    } else if (event.type === 'job.progress') {
      const progress = Math.max(0, Math.min(1, event.progress > 1 ? event.progress / 100 : event.progress));
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'running', progress });
      const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
      useCanvasStore.getState().updateGenerationRecord({ ...current, status: 'running', queueStatus: 'running', progress });
    } else if (event.type === 'agent.message') {
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'receiving' });
    } else if (event.type === 'job.cancelled') {
      const current = useCanvasStore.getState().generationRecords.find((record) => record.id === generationRecord.id) ?? generationRecord;
      useCanvasStore.getState().updateGenerationRecord({
        ...current,
        status: 'cancelled',
        queueStatus: 'cancelled',
        materializationStatus: 'failed',
        materializationError: '任务已取消',
        completedAt: Date.now(),
      });
      persistQueue('terminal', jobId);
    }
    // asset.created 已在上方接入统一 materializer，避免独立节点提交时结果只留在后端。
  });

  return jobId;
};
