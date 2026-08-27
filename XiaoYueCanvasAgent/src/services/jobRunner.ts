import { jobAdapter } from './capabilities';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { useAgentStore } from '../store/agentStore';
import { createId } from '../utils/id';
import { saveAsset } from './projectStorage';
import { createImageAsset, createVideoAsset, getMediaNodeSize, getVideoNodeSize } from '../utils/mediaPipeline';
import type { JobEvent, PersistedAsset } from '../types';

// 生图：把执行服务返回的图片落到目标（空图片）节点，带完整溯源。
const materializeGeneration = async (event: Extract<JobEvent, { type: 'asset.created' }>, targetNodeId: string) => {
  const file = await jobAdapter.fetchAsset(event.asset);
  const operation = { type: 'remote' as const, params: { jobId: event.jobId, remoteAssetId: event.asset.id, capabilityId: 'image.generate' } };
  const asset: PersistedAsset = file.type.startsWith('video/') ? await createVideoAsset(file) : await createImageAsset(file, undefined, { operation });
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

// 提交生图任务：未配置执行服务时如实抛错，不伪造结果。
export const submitImageGeneration = async (prompt: string, targetNodeId: string, onFeedback: (message: string) => void) => {
  const text = prompt.trim();
  if (!text) throw new Error('请输入生图提示词');
  const canvas = useCanvasStore.getState();
  const projectId = canvas.projectId ?? 'local-project';
  const capabilities = await jobAdapter.getCapabilities();
  const capability = capabilities.find((item) => item.id === 'image.generate');
  if (!capability?.available) throw new Error(capability?.unavailableReason ?? '未配置图像执行服务');

  const conversationId = useAgentStore.getState().ensureSession(projectId).conversationId;
  const { jobId } = await jobAdapter.submit({
    clientRequestId: createId(),
    conversationId,
    capabilityId: 'image.generate',
    sourceAssetIds: [],
    context: { projectId, nodeIds: canvas.nodes.map((node) => node.id), selectedNodeId: targetNodeId },
    params: { prompt: text },
  });

  useAgentStore.getState().putJob(projectId, { id: jobId, prompt: text, sourceNodeId: targetNodeId, capabilityId: 'image.generate', status: 'queued', progress: 0, createdAt: Date.now() });

  const seenAssets = new Set<string>();
  jobAdapter.subscribe(jobId, (event) => {
    if (event.type === 'asset.created') {
      const dedupeKey = `${event.jobId}:${event.asset.id}`;
      if (seenAssets.has(dedupeKey)) return;
      seenAssets.add(dedupeKey);
      void materializeGeneration(event, targetNodeId)
        .then(() => useAgentStore.getState().updateJob(projectId, jobId, { status: 'completed', progress: 1 }))
        .catch((error) => {
          useAgentStore.getState().updateJob(projectId, jobId, { status: 'failed' });
          onFeedback(error instanceof Error ? error.message : '生图结果落地失败');
        });
    } else if (event.type === 'job.progress') {
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'running', progress: Math.max(0, Math.min(1, event.progress > 1 ? event.progress / 100 : event.progress)) });
    } else if (event.type === 'job.failed') {
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'failed' });
      onFeedback(event.message);
    } else if (event.type === 'job.completed') {
      useAgentStore.getState().updateJob(projectId, jobId, { status: 'completed', progress: 1 });
    }
  });

  return jobId;
};
