import { libraryApi, type LibraryAsset, type MediaType } from '../api/library';
import { useLibraryTaskStore } from '../stores/libraryTaskStore';

export interface ProjectLibraryImportInput {
  projectId: string;
  producer: 'moon-chat' | 'storyboard-editor';
  url: string;
  mediaType: MediaType;
  label?: string;
}

export async function importMediaToProjectLibrary(
  input: ProjectLibraryImportInput,
): Promise<LibraryAsset> {
  const mediaKey = await stableMediaKey(input.url);
  const generationRecordId = `${input.producer}:${mediaKey}`;
  const taskId = `library-import:${generationRecordId}`;
  const label = input.label?.trim() || defaultLabel(input.mediaType);
  const taskStore = useLibraryTaskStore.getState();

  taskStore.upsertTask({
    id: taskId,
    kind: 'import',
    label,
    detail: '导入项目库',
    status: 'queued',
    progress: 0,
  });

  try {
    const response = await libraryApi.registerGeneratedOutput({
      producer: input.producer,
      projectId: input.projectId,
      generationRecordId,
      resultIndex: 0,
      resultUrl: input.url,
      mediaType: input.mediaType,
    });
    const asset = response.data.data;
    taskStore.updateTask(taskId, {
      status: 'completed',
      progress: 100,
      assetId: asset.id,
    });
    if (asset.type === 'IMAGE') {
      taskStore.addAnalysisTask(
        asset.id,
        asset.name,
        asset.metadataState || 'AI_GENERATING',
      );
    }
    return asset;
  } catch (error) {
    taskStore.updateTask(taskId, {
      status: 'failed',
      error: error instanceof Error ? error.message : '导入项目库失败',
    });
    throw error;
  }
}

async function stableMediaKey(url: string): Promise<string> {
  const data = new TextEncoder().encode(url);
  if (globalThis.crypto?.subtle) {
    const digest = await globalThis.crypto.subtle.digest('SHA-256', data);
    return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
  }
  let hash = 2166136261;
  for (const byte of data) {
    hash ^= byte;
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(16);
}

function defaultLabel(mediaType: MediaType): string {
  if (mediaType === 'VIDEO') return '生成视频';
  if (mediaType === 'AUDIO') return '生成音频';
  return '生成图片';
}
