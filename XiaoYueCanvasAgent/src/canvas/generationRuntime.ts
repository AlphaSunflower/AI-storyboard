import { jobAdapter } from '../services/capabilities';
import { listGenerationQueueItems, putGenerationQueueItem, saveAsset } from '../services/projectStorage';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { createImageAsset, createVideoAsset, getMediaNodeSize, getVideoNodeSize } from '../utils/mediaPipeline';
import type { PersistedAsset } from '../types';
import type { GenerationRecord } from './graphContracts';
import {
  materializeGenerationResult,
  type GenerationMaterializerPort,
  type MaterializerAssetEvent,
} from './generationMaterializer';
import { recoveryActionForQueueItem } from './generationQueue';
import { generationResultLabel } from './generationResultLabel';
import { linkGeneratedCanvasAssets } from '../services/canvasAssets';

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
  attachResultNode: (resultNodeId, asset: PersistedAsset, record: GenerationRecord) => {
    const size = asset.kind === 'video'
      ? getVideoNodeSize(asset.width, asset.height)
      : getMediaNodeSize(asset.width, asset.height);
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
  return materializeGenerationResult(productionPort, record, {
    ...asset,
    assetId: persisted.id,
    persistedId: persisted.id,
    name: persisted.name ?? asset.name,
    mimeType: persisted.mimeType ?? asset.mimeType,
  }, jobId);
}

let recoveryInFlight: Promise<void> | null = null;

export function recoverGenerationJobs() {
  if (recoveryInFlight) return recoveryInFlight;
  recoveryInFlight = recoverGenerationJobsNow().finally(() => {
    recoveryInFlight = null;
  });
  return recoveryInFlight;
}

async function recoverGenerationJobsNow() {
  const items = await listGenerationQueueItems();
  for (const item of items) {
    const action = recoveryActionForQueueItem(item);
    const current = useCanvasStore.getState().generationRecords.find((record) => record.id === item.generationRecordId);
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
            useCanvasStore.getState().updateGenerationRecord({
              ...latest,
              status: 'completed',
              queueStatus: 'terminal',
              progress: 1,
              completedAt: latest.completedAt ?? Date.now(),
            });
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
}
