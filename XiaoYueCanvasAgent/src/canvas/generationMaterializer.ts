import type { PersistedAsset } from '../types';
import type { GenerationRecord } from './graphContracts';

export interface MaterializerAssetEvent {
  assetId: string;
  persistedId?: string;
  name: string;
  mimeType: string;
  downloadUrl?: string;
}

export interface GenerationMaterializerPort {
  fetchFile: (asset: MaterializerAssetEvent) => Promise<File>;
  createAsset: (file: File, persistedId?: string) => Promise<PersistedAsset>;
  saveAsset: (asset: PersistedAsset) => Promise<void>;
  upsertAsset: (asset: PersistedAsset) => void;
  attachResultNode: (
    resultNodeId: string,
    asset: PersistedAsset,
    record: GenerationRecord,
    targetNodeId?: string,
  ) => string;
  updateRecord: (record: GenerationRecord) => void;
  getRecord?: (recordId: string) => GenerationRecord | undefined;
}

export interface MaterializationSettlement {
  created: boolean;
  record: GenerationRecord;
  asset?: PersistedAsset;
  resultNodeId?: string;
}

const canMaterialize = (record: GenerationRecord) =>
  record.status !== 'cancelled' && record.status !== 'interrupted';

const mediaKindOf = (event: MaterializerAssetEvent): 'image' | 'video' =>
  event.mimeType?.startsWith('video/') || /\.(mp4|webm|mov)$/i.test(event.name)
    ? 'video'
    : 'image';

export async function materializeGenerationResult(
  port: GenerationMaterializerPort,
  record: GenerationRecord,
  event: MaterializerAssetEvent,
  jobId: string,
): Promise<MaterializationSettlement> {
  const resultNodeId = `result:${record.id}:${event.assetId}`;
  const boundNodeId = record.resultBindings?.[resultNodeId];
  if (
    (boundNodeId || record.resultNodeIds.includes(resultNodeId))
    && record.materializationStatus === 'completed'
  ) {
    return { created: false, record, resultNodeId: boundNodeId ?? resultNodeId };
  }

  if (!canMaterialize(record)) {
    const ignored: GenerationRecord = {
      ...record,
      materializationStatus: 'failed',
      materializationError: '任务已取消，忽略晚到结果',
      completedAt: record.completedAt ?? Date.now(),
    };
    port.updateRecord(ignored);
    return { created: false, record: ignored };
  }

  const materializing: GenerationRecord = {
    ...record,
    jobId: record.jobId ?? jobId,
    materializationStatus: 'materializing',
    materializationError: undefined,
  };
  port.updateRecord(materializing);

  try {
    const file = await port.fetchFile(event);
    const asset = await port.createAsset(file, event.persistedId);
    await port.saveAsset(asset);
    const latest = port.getRecord?.(record.id) ?? materializing;
    if (!canMaterialize(latest)) {
      const ignored: GenerationRecord = {
        ...latest,
        materializationStatus: 'failed',
        materializationError: '任务已取消，忽略晚到结果',
        completedAt: latest.completedAt ?? Date.now(),
      };
      port.updateRecord(ignored);
      return { created: false, record: ignored, asset };
    }
    port.upsertAsset(asset);
    const usedSlots = new Set(Object.values(latest.resultBindings ?? {}));
    const slot = (latest.resultSlots ?? [])
      .filter((candidate) => candidate.kind === mediaKindOf(event) && !usedSlots.has(candidate.nodeId))
      .sort((left, right) => left.order - right.order)[0];
    const actualNodeId = port.attachResultNode(resultNodeId, asset, latest, slot?.nodeId);
    const completed: GenerationRecord = {
      ...latest,
      materializationStatus: 'completed',
      materializedAssetIds: [...new Set([...latest.materializedAssetIds, asset.id])],
      resultNodeIds: [...new Set([...latest.resultNodeIds, actualNodeId])],
      resultBindings: { ...(latest.resultBindings ?? {}), [resultNodeId]: actualNodeId },
      materializationError: undefined,
      completedAt: materializing.completedAt ?? Date.now(),
    };
    port.updateRecord(completed);
    return { created: true, record: completed, asset, resultNodeId: actualNodeId };
  } catch (caught) {
    const failed: GenerationRecord = {
      ...materializing,
      materializationStatus: 'failed',
      materializationError: caught instanceof Error ? caught.message : '生成结果落地失败',
    };
    port.updateRecord(failed);
    return { created: false, record: failed };
  }
}
