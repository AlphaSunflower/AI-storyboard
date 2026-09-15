import { jobAdapter } from './capabilities';
import { collectReferenceDataUris } from './jobRunner';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { useAgentStore } from '../store/agentStore';
import { useStoryboardVideoStore, type StoryboardVideoTask } from '../store/storyboardVideoStore';
import { createVideoAsset } from '../utils/mediaPipeline';
import { putGenerationQueueItem, saveAsset } from './projectStorage';
import { createId } from '../utils/id';
import type { JobAssetDescriptor, PersistedAsset, StoryboardFrame } from '../types';
import type { GenerationRecord } from '../canvas/graphContracts';
import { buildGenerationSources } from '../canvas/generationRecords';
import { linkGeneratedCanvasAssets } from './canvasAssets';

/** 全局串行锁：同一时刻只跑一个分镜视频任务（一个分镜一个分镜生成）。 */
let processing = false;

const updateFrameInNode = (nodeId: string, frameIndex: number, patch: Partial<StoryboardFrame>) => {
  const canvas = useCanvasStore.getState();
  const node = canvas.nodes.find((item) => item.id === nodeId);
  if (!node) return;
  const frames = Array.isArray(node.data.frames) ? [...(node.data.frames as StoryboardFrame[])] : [];
  frames[frameIndex] = { ...(frames[frameIndex] ?? {}), ...patch };
  canvas.updateNode(nodeId, { frames });
};

const updateGenerationRecord = (task: StoryboardVideoTask, patch: Partial<GenerationRecord>) => {
  const current = useCanvasStore.getState().generationRecords.find((record) => record.id === task.generationRecordId);
  if (!current) return;
  useCanvasStore.getState().updateGenerationRecord({ ...current, ...patch });
};

const persistTaskQueue = (
  projectId: string,
  task: StoryboardVideoTask,
  status: 'pending_submit' | 'running' | 'terminal' | 'interrupted',
  jobId?: string,
  error?: string,
) => {
  void putGenerationQueueItem({
    id: task.generationRecordId,
    projectId,
    canvasId: useCanvasStore.getState().canvasId ?? '',
    producerNodeId: task.nodeId,
    generationRecordId: task.generationRecordId,
    requestSnapshot: {
      capabilityId: 'video.generate',
      sourceAssetIds: task.sourceAssetIds,
      params: { prompt: task.prompt, ...(task.config ?? {}) },
    },
    status,
    jobId,
    attempt: 1,
    error,
    createdAt: task.createdAt,
    updatedAt: Date.now(),
  });
};

const markFrameStatus = (task: StoryboardVideoTask, status: StoryboardVideoTask['status'], extra: Partial<StoryboardFrame> = {}) => {
  updateFrameInNode(task.nodeId, task.frameIndex, { videoStatus: status, ...extra });
  useStoryboardVideoStore.getState().update(task.queueId, { status });
};

/** 把后端生成结果下载并落地为该帧的视频。 */
const materializeVideoForFrame = async (task: StoryboardVideoTask, asset: JobAssetDescriptor, jobId: string) => {
  const currentCanvasId = useCanvasStore.getState().canvasId;
  if (!currentCanvasId) throw new Error('画布尚未保存，无法映射视频资产');
  const [persisted] = await linkGeneratedCanvasAssets(
    currentCanvasId, task.nodeId, asset.id);
  if (!persisted) throw new Error('视频生成结果未建立画布资产映射');
  const file = await jobAdapter.fetchAsset({
    ...asset,
    id: persisted.id,
    mimeType: persisted.mimeType,
  });
  const mediaAsset: PersistedAsset = await createVideoAsset(file, persisted.id);
  mediaAsset.operation = {
    type: 'remote',
    params: { jobId, remoteAssetId: persisted.id, capabilityId: 'video.generate' },
  };
  const { projectId, canvasId } = useCanvasStore.getState();
  if (mediaAsset.projectId === undefined) mediaAsset.projectId = projectId ?? undefined;
  if (mediaAsset.canvasId === undefined) mediaAsset.canvasId = canvasId ?? undefined;
  await saveAsset(mediaAsset);
  useMediaStore.getState().upsertAsset(mediaAsset);
  updateFrameInNode(task.nodeId, task.frameIndex, {
    videoAssetId: mediaAsset.id,
    videoStatus: 'completed',
  });
  updateGenerationRecord(task, {
    materializationStatus: 'completed',
    materializedAssetIds: [...new Set([
      ...(useCanvasStore.getState().generationRecords.find((record) => record.id === task.generationRecordId)?.materializedAssetIds ?? []),
      mediaAsset.id,
    ])],
    completedAt: Date.now(),
  });
};

/** 运行单个任务：提交 → 订阅 SSE → 终态返回。 */
const runSingleTask = async (projectId: string, task: StoryboardVideoTask): Promise<boolean> => {
  const store = useStoryboardVideoStore.getState();
  const canvas = useCanvasStore.getState();
  const capabilities = await jobAdapter.getCapabilities();
  const capability = capabilities.find((item) => item.id === 'video.generate');
  if (!capability?.available) {
    store.update(task.queueId, { status: 'failed', error: capability?.unavailableReason ?? '未配置视频执行服务' });
    updateFrameInNode(task.nodeId, task.frameIndex, { videoStatus: 'failed' });
    updateGenerationRecord(task, {
      status: 'failed',
      queueStatus: 'interrupted',
      materializationStatus: 'failed',
      error: capability?.unavailableReason ?? '未配置视频执行服务',
      completedAt: Date.now(),
    });
    persistTaskQueue(projectId, task, 'interrupted', undefined, capability?.unavailableReason ?? '未配置视频执行服务');
    return true;
  }

  // 排队中被取消（还没拿到 jobId）的任务直接跳过，避免仍去提交
  const live = useStoryboardVideoStore.getState().tasks[task.queueId];
  if (live && live.status !== 'queued') return true;
  const liveRecord = useCanvasStore.getState().generationRecords.find((record) => record.id === task.generationRecordId);
  if (liveRecord?.status === 'cancelled') {
    store.update(task.queueId, { status: 'cancelled' });
    updateFrameInNode(task.nodeId, task.frameIndex, { videoStatus: 'cancelled', videoJobId: undefined });
    return true;
  }

  const refUris = await collectReferenceDataUris(task.sourceAssetIds);
  const conversationId = useAgentStore.getState().ensureSession(projectId).conversationId;

  let jobId: string | undefined;
  try {
    const res = await jobAdapter.submit({
      clientRequestId: task.queueId,
      conversationId,
      capabilityId: 'video.generate',
      sourceAssetIds: task.sourceAssetIds,
      context: { projectId, nodeIds: canvas.nodes.map((node) => node.id), selectedNodeId: task.nodeId },
      params: {
        prompt: task.prompt,
        ...(task.config ?? {}),
        ...(refUris.images.length || refUris.videos.length
          ? { referenceAssetIds: [...refUris.images, ...refUris.videos] }
          : {}),
      },
    });
    jobId = res.jobId;
  } catch (error) {
    const message = error instanceof Error ? error.message : '任务提交失败';
    store.update(task.queueId, { status: 'failed', error: message });
    updateFrameInNode(task.nodeId, task.frameIndex, { videoStatus: 'failed' });
    updateGenerationRecord(task, {
      status: 'failed',
      queueStatus: 'interrupted',
      materializationStatus: 'failed',
      error: message,
      completedAt: Date.now(),
    });
    persistTaskQueue(projectId, task, 'interrupted', undefined, message);
    return true;
  }

  const currentTask = { ...task, jobId };
  store.update(task.queueId, { status: 'running', jobId });
  updateFrameInNode(task.nodeId, task.frameIndex, { videoStatus: 'running', videoJobId: jobId });
  updateGenerationRecord(task, { jobId, status: 'running', queueStatus: 'running' });
  persistTaskQueue(projectId, task, 'running', jobId);

  return await new Promise<boolean>((resolve) => {
    let resolved = false;
    const unsubscribe = jobAdapter.subscribe(jobId as string, (event) => {
      if (event.type === 'job.progress') {
        const raw = Math.max(0, Math.min(1, event.progress > 1 ? event.progress / 100 : event.progress));
        store.update(currentTask.queueId, { status: 'running', progress: raw });
        updateFrameInNode(task.nodeId, task.frameIndex, { videoStatus: 'running' });
        updateGenerationRecord(task, { status: 'running', queueStatus: 'running', progress: raw });
      } else if (event.type === 'asset.created') {
        void materializeVideoForFrame(currentTask, event.asset, jobId as string)
          .then(() => store.update(currentTask.queueId, { status: 'running', progress: 0.95 }))
          .catch((error) => {
            store.update(currentTask.queueId, { status: 'failed', error: error instanceof Error ? error.message : '结果落地失败' });
            updateFrameInNode(task.nodeId, task.frameIndex, { videoStatus: 'failed' });
            updateGenerationRecord(task, {
              status: 'failed',
              queueStatus: 'interrupted',
              materializationStatus: 'failed',
              materializationError: error instanceof Error ? error.message : '结果落地失败',
              completedAt: Date.now(),
            });
          });
      } else if (event.type === 'job.completed' && !resolved) {
        resolved = true;
        store.update(currentTask.queueId, { status: 'completed', progress: 1 });
        updateFrameInNode(task.nodeId, task.frameIndex, { videoStatus: 'completed' });
        updateGenerationRecord(task, { status: 'completed', queueStatus: 'terminal', progress: 1, completedAt: Date.now() });
        persistTaskQueue(projectId, task, 'terminal', jobId);
        unsubscribe();
        resolve(true);
      } else if (event.type === 'job.failed' && !resolved) {
        resolved = true;
        store.update(currentTask.queueId, { status: 'failed', error: event.message });
        updateFrameInNode(task.nodeId, task.frameIndex, { videoStatus: 'failed' });
        updateGenerationRecord(task, {
          status: 'failed',
          queueStatus: 'interrupted',
          error: event.message,
          completedAt: Date.now(),
        });
        persistTaskQueue(projectId, task, 'interrupted', jobId, event.message);
        unsubscribe();
        resolve(true);
      } else if (event.type === 'job.cancelled' && !resolved) {
        resolved = true;
        store.update(currentTask.queueId, { status: 'cancelled' });
        updateFrameInNode(task.nodeId, task.frameIndex, { videoStatus: 'cancelled' });
        updateGenerationRecord(task, {
          status: 'cancelled',
          queueStatus: 'cancelled',
          materializationStatus: 'failed',
          materializationError: '任务已取消',
          completedAt: Date.now(),
        });
        persistTaskQueue(projectId, task, 'terminal', jobId);
        unsubscribe();
        resolve(true);
      }
    });
  });
};

/** 顺序处理队列：始终只推进一个「排队中」的任务。 */
const processVideoQueue = async (projectId: string) => {
  if (processing) return;
  processing = true;
  try {
    // 队列本身按 createdAt 排序；被取消的已不在 queued，自动跳过
    while (true) {
      const tasks = Object.values(useStoryboardVideoStore.getState().tasks).sort((a, b) => a.createdAt - b.createdAt);
      const next = tasks.find((task) => task.status === 'queued');
      if (!next) break;
      const done = await runSingleTask(projectId, next);
      if (!done) break;
    }
  } finally {
    processing = false;
  }
};

/** 把勾选的分镜帧入队，并开始顺序生成视频。 */
export const enqueueStoryboardVideo = async (nodeId: string, frames: StoryboardFrame[]) => {
  const checked = frames
    .map((frame, index) => ({ frame, index }))
    .filter(({ frame }) => frame.checked);
  if (!checked.length) throw new Error('请先勾选要生成视频的分镜');
  const projectId = useCanvasStore.getState().projectId ?? 'local-project';
  const store = useStoryboardVideoStore.getState();

  for (const { frame, index } of checked) {
    const prompt = frame.prompt?.trim() || frame.description?.trim() || `分镜${index + 1}`;
    const generationRecordId = `generation-${createId()}`;
    const sourceAssetIds = [...(frame.assetIds ?? []), ...(frame.imageAssetId ? [frame.imageAssetId] : [])];
    const task: StoryboardVideoTask = {
      queueId: createId(),
      generationRecordId,
      nodeId,
      frameIndex: index,
      status: 'queued',
      progress: 0,
      prompt,
      config: frame.config ?? {},
      sourceAssetIds,
      createdAt: Date.now(),
    };
    const roleByAssetId = Object.fromEntries([
      ...(frame.assetIds ?? []).map((assetId) => [assetId, 'REFERENCE']),
      ...(frame.imageAssetId ? [[frame.imageAssetId, 'FIRST_FRAME']] : []),
    ]);
    const record: GenerationRecord = {
      id: generationRecordId,
      producerNodeId: nodeId,
      actionId: 'generateVideo',
      attempt: 1,
      status: 'queued',
      progress: 0,
      queueStatus: 'pending_submit',
      materializationStatus: 'pending',
      materializedAssetIds: [],
      resultNodeIds: [],
      sources: buildGenerationSources({
        sourceAssetIds,
        roleByAssetId,
        nodes: useCanvasStore.getState().nodes,
        fallbackRole: 'REFERENCE',
      }),
      prompt,
      effectivePrompt: prompt,
      params: { ...(frame.config ?? {}), actionId: 'generateVideo' },
      model: frame.config?.model,
      startedAt: task.createdAt,
    };
    useCanvasStore.getState().appendGenerationRecord(record);
    persistTaskQueue(projectId, task, 'pending_submit');
    store.enqueue(task);
    // 入队即置分镜状态；取消勾选（避免重复点击）
    updateFrameInNode(nodeId, index, { videoStatus: 'queued', videoJobId: undefined, checked: false });
  }
  void processVideoQueue(projectId);
};

/** 取消单个任务：排队且未提交的直接本地取消；已提交的走后端 cancel + SSE 事件。 */
export const cancelStoryboardVideo = async (queueId: string) => {
  const store = useStoryboardVideoStore.getState();
  const task = store.tasks[queueId];
  if (!task) return;
  if (task.status !== 'queued' && task.status !== 'running') return;
  // 尚未拿到 jobId，说明还没提交，直接本地取消（进程会在下一轮跳过它）
  if (!task.jobId) {
    store.update(queueId, { status: 'cancelled' });
    updateFrameInNode(task.nodeId, task.frameIndex, { videoStatus: 'cancelled', videoJobId: undefined });
    updateGenerationRecord(task, {
      status: 'cancelled',
      queueStatus: 'cancelled',
      materializationStatus: 'failed',
      materializationError: '任务已取消',
      completedAt: Date.now(),
    });
    persistTaskQueue(useCanvasStore.getState().projectId ?? 'local-project', task, 'terminal');
    return;
  }
  store.update(queueId, { status: 'cancelling' });
  try {
    await jobAdapter.cancel(task.jobId);
    updateGenerationRecord(task, {
      status: 'cancelled',
      queueStatus: 'cancelled',
      materializationStatus: 'failed',
      materializationError: '任务已取消',
      completedAt: Date.now(),
    });
    persistTaskQueue(useCanvasStore.getState().projectId ?? 'local-project', task, 'terminal', task.jobId);
    // 状态由 job.cancelled SSE 事件最终落地
  } catch (error) {
    // 取消失败：恢复原状，提示用户
    store.update(queueId, { status: task.status });
    throw error;
  }
};
