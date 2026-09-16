import { jobAdapter } from '../services/capabilities';
import { listGenerationQueueItems, putGenerationQueueItem, saveAsset } from '../services/projectStorage';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { createImageAsset, createVideoAsset, getMediaNodeSize, getVideoNodeSize } from '../utils/mediaPipeline';
import type { PersistedAsset } from '../types';
import type { CanvasGraphV2, GenerationRecord } from './graphContracts';
import {
  materializeGenerationResult,
  type GenerationMaterializerPort,
  type MaterializerAssetEvent,
} from './generationMaterializer';
import { recoveryActionForQueueItem, type GenerationQueueItem } from './generationQueue';
import { generationResultLabel, resultAttachmentLabel } from './generationResultLabel';
import { linkGeneratedCanvasAssets } from '../services/canvasAssets';
import { requestAutoLayout } from './layout/autoLayout';
import { resolveResultSlots } from './resultSlots';

const recoveredGenerationRecord = (
  item: GenerationQueueItem,
  progress: number,
): GenerationRecord => {
  const state = useCanvasStore.getState();
  const capabilityId = item.requestSnapshot.capabilityId;
  const params: Record<string, unknown> = { ...(item.requestSnapshot.params ?? {}), capabilityId };
  const prompt = typeof params.prompt === 'string' ? params.prompt : '';
  const actionId = typeof params.actionId === 'string'
    ? params.actionId
    : capabilityId.startsWith('image.') ? 'generateImage'
      : capabilityId === 'video.generate' ? 'generateVideo'
        : capabilityId === 'storyboard.plan' ? 'plan'
          : capabilityId === 'agent.request' ? 'storyboardGrid'
            : 'generate';
  const sources = item.requestSnapshot.sourceAssetIds.flatMap((assetId, order) => {
    const node = state.nodes.find((candidate) => candidate.data.assetId === assetId);
    return node ? [{ nodeId: node.id, assetId, role: 'source', order }] : [];
  });
  return {
    id: item.generationRecordId,
    producerNodeId: item.producerNodeId,
    actionId,
    attempt: item.attempt,
    jobId: item.jobId,
    progress,
    status: 'running',
    queueStatus: 'running',
    materializationStatus: 'pending',
    materializedAssetIds: [],
    resultNodeIds: [],
    resultSlots: resolveResultSlots({
      schemaVersion: 2,
      nodes: state.nodes as CanvasGraphV2['nodes'],
      edges: state.edges as CanvasGraphV2['edges'],
      viewport: { x: 0, y: 0, zoom: 1 },
      generationRecords: state.generationRecords,
      executions: state.executions,
    }, item.producerNodeId, { includeFilled: true }),
    sources,
    prompt,
    effectivePrompt: prompt,
    params,
    model: typeof params.model === 'string' ? params.model : undefined,
    startedAt: item.createdAt,
  };
};

const productionPort: GenerationMaterializerPort = {
  fetchFile: (asset) => jobAdapter.fetchAsset({
    id: asset.assetId,
    name: asset.name,
    mimeType: asset.mimeType,
    downloadUrl: asset.downloadUrl,
  }),
  createAsset: async (file, persistedId) => {
    const operation = { type: 'remote' as const, params: { canvasAssetId: persistedId ?? '' } };
    return file.type.startsWith('video/')
      ? createVideoAsset(file, persistedId)
      : createImageAsset(file, persistedId, { operation });
  },
  saveAsset,
  upsertAsset: (asset) => useMediaStore.getState().upsertAsset(asset),
  attachResultNode: (resultNodeId, asset: PersistedAsset, record: GenerationRecord, targetNodeId) => {
    const size = asset.kind === 'video'
      ? getVideoNodeSize(asset.width, asset.height)
      : getMediaNodeSize(asset.width, asset.height);
    if (targetNodeId) {
      const existingLabel = useCanvasStore.getState().nodes
        .find((node) => node.id === targetNodeId)?.data.label;
      useCanvasStore.getState().attachAssetToNode(targetNodeId, {
        assetId: asset.id,
        label: resultAttachmentLabel(asset.id, existingLabel),
        size,
      });
      return targetNodeId;
    }
    return useCanvasStore.getState().materializeResultNode({
      resultNodeId,
      producerNodeId: record.producerNodeId,
      assetId: asset.id,
      kind: asset.kind,
      label: generationResultLabel(asset.id),
      size,
    });
  },
  updateRecord: (record) => {
    const exists = useCanvasStore.getState().generationRecords.some((item) => item.id === record.id);
    if (exists) useCanvasStore.getState().updateGenerationRecord(record);
    else useCanvasStore.getState().appendGenerationRecord(record);
  },
  getRecord: (recordId) => useCanvasStore.getState().generationRecords.find((record) => record.id === recordId),
};

export async function materializeJobAsset(
  record: GenerationRecord,
  asset: MaterializerAssetEvent,
  jobId: string,
) {
  const canvasId = useCanvasStore.getState().canvasId;
  if (!canvasId) throw new Error('画布尚未保存，无法映射生成结果');
  const linked = await linkGeneratedCanvasAssets(canvasId, record.producerNodeId, asset.assetId);
  const persisted = linked[0];
  if (!persisted) throw new Error('生成结果未建立画布资产映射');
  const settlement = await materializeGenerationResult(productionPort, record, {
    ...asset,
    assetId: persisted.id,
    persistedId: persisted.id,
    name: persisted.name ?? asset.name,
    mimeType: persisted.mimeType ?? asset.mimeType,
  }, jobId);
  if (settlement.created) {
    void requestAutoLayout([settlement.resultNodeId ?? record.producerNodeId]);
  }
  return settlement;
}

const hasMaterializedResult = (record: GenerationRecord) => {
  if (record.materializedAssetIds.length > 0) return true;
  const nodes = useCanvasStore.getState().nodes;
  return record.resultNodeIds.some((nodeId) =>
    Boolean(nodes.find((node) => node.id === nodeId)?.data.assetId));
};

const settleCompletedRecord = (record: GenerationRecord): GenerationRecord => {
  const materializationFailed = record.materializationStatus === 'failed';
  return {
    ...record,
    status: 'completed',
    queueStatus: 'terminal',
    progress: 1,
    materializationStatus: materializationFailed ? 'failed' : 'completed',
    materializationError: materializationFailed ? record.materializationError : undefined,
    completedAt: record.completedAt ?? Date.now(),
  };
};

const settleInterruptedRecord = (record: GenerationRecord, error: string): GenerationRecord => ({
  ...record,
  status: 'interrupted',
  queueStatus: 'interrupted',
  materializationStatus: record.materializationStatus === 'completed' ? 'completed' : 'failed',
  materializationError: record.materializationStatus === 'completed' ? undefined : error,
  completedAt: Date.now(),
});

let recoveryInFlight: Promise<void> | null = null;

export function recoverGenerationJobs() {
  if (recoveryInFlight) return recoveryInFlight;
  recoveryInFlight = recoverGenerationJobsNow().finally(() => {
    recoveryInFlight = null;
  });
  return recoveryInFlight;
}

async function recoverGenerationJobsNow() {
  const state = useCanvasStore.getState();
  const items = (await listGenerationQueueItems(state.projectId ?? undefined))
    .filter((item) => !state.canvasId || item.canvasId === state.canvasId);
  for (const item of items) {
    const action = recoveryActionForQueueItem(item);
    let current = useCanvasStore.getState().generationRecords.find((record) => record.id === item.generationRecordId);
    if (action === 'ignore' && item.status !== 'terminal') continue;
    if (action === 'interrupted') {
      await putGenerationQueueItem({ ...item, status: 'interrupted', updatedAt: Date.now() });
      if (current && current.status !== 'completed') {
        useCanvasStore.getState().updateNode(current.producerNodeId, { isGenerating: false, progressPercent: 0 });
        useCanvasStore.getState().updateGenerationRecord({
          ...current,
          status: 'interrupted',
          queueStatus: 'interrupted',
          materializationStatus: current.materializationStatus === 'completed' ? 'completed' : 'failed',
          materializationError: current.materializationStatus === 'completed' ? undefined : '应用刷新后执行中断，请重试',
          completedAt: Date.now(),
        });
      }
      continue;
    }
    if (item.jobId && (action === 'resume' || item.status === 'terminal')) {
      let snapshot;
      try {
        snapshot = await jobAdapter.getJob?.(item.jobId);
      } catch {
        // Snapshot unavailable: keep the SSE fallback below.
      }
      if (snapshot && ['completed', 'failed', 'cancelled', 'interrupted'].includes(snapshot.status)) {
        if (!current) {
          current = recoveredGenerationRecord(item, snapshot.progress ?? 0);
          useCanvasStore.getState().appendGenerationRecord(current);
        }
        if (current) {
          if (snapshot.status === 'completed') {
            let latest = current;
            for (const asset of snapshot.assets ?? []) {
              const settlement = await materializeJobAsset(latest, {
                assetId: asset.id,
                name: asset.name,
                mimeType: asset.mimeType,
                downloadUrl: asset.downloadUrl,
              }, item.jobId);
              latest = settlement.record;
            }
            useCanvasStore.getState().updateGenerationRecord(settleCompletedRecord(latest));
          } else {
            const failedStatus = snapshot.status === 'cancelled' ? 'cancelled' : snapshot.status;
            useCanvasStore.getState().updateGenerationRecord({
              ...current,
              status: failedStatus,
              queueStatus: failedStatus === 'cancelled' ? 'cancelled' : 'interrupted',
              materializationStatus: current.materializationStatus === 'completed' ? 'completed' : 'failed',
              materializationError: snapshot.status === 'cancelled'
                ? '任务已取消'
                : current.materializationStatus === 'completed' ? undefined : snapshot.error ?? '任务执行失败',
              error: snapshot.error,
              progress: snapshot.progress ?? current.progress,
              completedAt: Date.now(),
            });
          }
          useCanvasStore.getState().updateNode(current.producerNodeId, { isGenerating: false, progressPercent: 0 });
        }
        await putGenerationQueueItem({
          ...item,
          status: snapshot.status === 'completed' || snapshot.status === 'cancelled' ? 'terminal' : 'interrupted',
          error: snapshot.error,
          updatedAt: Date.now(),
        });
        continue;
      }
    }
    if (item.status === 'terminal' && current
      && (current.status !== 'completed' || current.materializationStatus === 'materializing')) {
      useCanvasStore.getState().updateGenerationRecord(settleCompletedRecord(current));
      useCanvasStore.getState().updateNode(current.producerNodeId, { isGenerating: false, progressPercent: 0 });
      continue;
    }
    if (action === 'ignore') continue;
    if (action === 'resume' && item.jobId && current) {
      jobAdapter.subscribe(item.jobId, (event) => {
        if (event.type === 'asset.created') {
          const latest = useCanvasStore.getState().generationRecords.find((record) => record.id === item.generationRecordId) ?? current;
          void materializeJobAsset(latest, {
            assetId: event.asset.id,
            name: event.asset.name,
            mimeType: event.asset.mimeType,
            downloadUrl: event.asset.downloadUrl,
          }, item.jobId!);
        } else if (event.type === 'job.completed' || event.type === 'job.failed' || event.type === 'job.cancelled') {
          const latest = useCanvasStore.getState().generationRecords.find((record) => record.id === item.generationRecordId);
          if (latest) {
            useCanvasStore.getState().updateNode(latest.producerNodeId, { isGenerating: false, progressPercent: 0 });
            useCanvasStore.getState().updateGenerationRecord({
              ...latest,
              status: event.type === 'job.completed' ? 'completed' : event.type === 'job.cancelled' ? 'cancelled' : 'failed',
              queueStatus: event.type === 'job.completed' ? 'terminal' : event.type === 'job.cancelled' ? 'cancelled' : 'interrupted',
              progress: event.type === 'job.completed' ? 1 : latest.progress,
              completedAt: Date.now(),
            });
          }
          void putGenerationQueueItem({ ...item, status: 'terminal', updatedAt: Date.now() });
        } else if (event.type === 'job.progress') {
          const latest = useCanvasStore.getState().generationRecords.find((record) => record.id === item.generationRecordId);
          if (latest) {
            const progress = Math.max(0, Math.min(1, event.progress > 1 ? event.progress / 100 : event.progress));
            useCanvasStore.getState().updateGenerationRecord({ ...latest, status: 'running', queueStatus: 'running', progress });
          }
        } else if (event.type === 'job.started' || event.type === 'job.queued') {
          const latest = useCanvasStore.getState().generationRecords.find((record) => record.id === item.generationRecordId);
          if (latest) {
            useCanvasStore.getState().updateGenerationRecord({
              ...latest,
              status: event.type === 'job.started' ? 'running' : 'queued',
              queueStatus: event.type === 'job.started' ? 'running' : 'queued',
            });
          }
        }
      });
    }
  }

  const queueItemByRecordId = new Map(items.map((item) => [item.generationRecordId, item]));
  const activeRecords = useCanvasStore.getState().generationRecords.filter((record) => {
    const active = record.status === 'queued'
      || record.status === 'running'
      || record.materializationStatus === 'materializing';
    if (!active) return false;
    const item = queueItemByRecordId.get(record.id);
    return !item
      || item.status === 'terminal'
      || item.status === 'cancelled'
      || item.status === 'interrupted';
  });
  for (const record of activeRecords) {
    let settled: GenerationRecord | null = null;
    if (hasMaterializedResult(record)) {
      settled = settleCompletedRecord(record);
    } else if (record.jobId) {
      let snapshot;
      try {
        snapshot = await jobAdapter.getJob?.(record.jobId);
      } catch {
        // Snapshot unavailable: do not keep a stale local record active forever.
      }
      if (snapshot?.status === 'completed') {
        let latest = record;
        for (const asset of snapshot.assets ?? []) {
          const settlement = await materializeJobAsset(latest, {
            assetId: asset.id,
            name: asset.name,
            mimeType: asset.mimeType,
            downloadUrl: asset.downloadUrl,
          }, record.jobId);
          latest = settlement.record;
        }
        settled = settleCompletedRecord(latest);
      } else if (snapshot?.status === 'failed' || snapshot?.status === 'interrupted' || snapshot?.status === 'cancelled') {
        const failedStatus = snapshot.status === 'cancelled' ? 'cancelled' : snapshot.status;
        settled = {
          ...record,
          status: failedStatus,
          queueStatus: failedStatus === 'cancelled' ? 'cancelled' : 'interrupted',
          materializationStatus: record.materializationStatus === 'completed' ? 'completed' : 'failed',
          materializationError: snapshot.status === 'cancelled'
            ? '任务已取消'
            : record.materializationStatus === 'completed' ? undefined : snapshot.error ?? '任务执行失败',
          error: snapshot.error,
          progress: snapshot.progress ?? record.progress,
          completedAt: Date.now(),
        };
      }
    }
    if (!settled) {
      settled = settleInterruptedRecord(record, '本地任务记录已丢失，请确认结果或重新发起');
    }
    useCanvasStore.getState().updateNode(record.producerNodeId, { isGenerating: false, progressPercent: 0 });
    useCanvasStore.getState().updateGenerationRecord(settled);
  }
}
