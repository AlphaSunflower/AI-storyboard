import { libraryApi, type LibraryAsset, type MediaType } from '../api/library';
import { useLibraryTaskStore } from '../stores/libraryTaskStore';

/** 会把生成结果回写到项目库的入口。同一张图从哪进来都要能被认出来，所以作为一张固定表。 */
export type ProjectLibraryProducer = 'moon-chat' | 'storyboard-editor';

export const PROJECT_LIBRARY_PRODUCERS: ProjectLibraryProducer[] = ['moon-chat', 'storyboard-editor'];

export interface ProjectLibraryImportInput {
  projectId: string;
  producer: ProjectLibraryProducer;
  url: string;
  mediaType: MediaType;
  label?: string;
  /** 素材来自哪个对话：导入后进入该对话的「产出素材」文件夹。编辑器导入不传。 */
  conversationId?: string;
  /** 已经算过幂等键时传入，避免重复算哈希并与查重结果对齐。 */
  generationRecordId?: string;
}

export async function importMediaToProjectLibrary(
  input: ProjectLibraryImportInput,
): Promise<LibraryAsset> {
  const generationRecordId = input.generationRecordId
    ?? await projectLibraryGenerationRecordId(input.producer, input.url);
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
    retryUrl: input.url,
    retryProducer: input.producer,
    retryMediaType: input.mediaType,
    retryProjectId: input.projectId,
  });

  try {
    const response = await libraryApi.registerGeneratedOutput({
      producer: input.producer,
      projectId: input.projectId,
      generationRecordId,
      resultIndex: 0,
      resultUrl: input.url,
      mediaType: input.mediaType,
      metadata: input.conversationId ? { conversationId: input.conversationId } : undefined,
    });
    const asset = response.data.data;
    taskStore.updateTask(taskId, {
      status: 'completed',
      progress: 100,
      assetId: asset.id,
    });
    return asset;
  } catch (error) {
    taskStore.updateTask(taskId, {
      status: 'failed',
      error: error instanceof Error ? error.message : '导入项目库失败',
    });
    throw error;
  }
}

/**
 * 后端回写幂等键：`producer:sha256(url)`。
 *
 * 必须和 `asset-service` 的 `GenerationOutputServiceImpl` 用同一口径，
 * 否则「查重」和「导入」会各算各的，同一张图能被导入两次。
 */
export async function projectLibraryGenerationRecordId(
  producer: ProjectLibraryProducer,
  url: string,
): Promise<string> {
  return `${producer}:${await stableMediaKey(url)}`;
}

/**
 * 这张素材是不是已经在项目库里了。
 *
 * 项目库不按 URL 建索引，只有回写时写进去的 `sourceId` 能认，而它有两种形态：
 * 聊天 / 分镜编辑器回写的是幂等键 `producer:sha256(url)`，Agent 成组回写的是生成资产 id。
 * 会话资产不知道当初从哪条路进去的，所以三种键都查一遍；
 * 查不到（或接口挂了）一律按「没导过」处理：让用户再点一次导入，也比把一个已经
 * 存在的素材说成「已导入」、点不动要好。
 */
export async function findProjectLibraryImport(input: {
  projectId: string;
  url: string;
  /** 生成资产 id：覆盖 Agent 成组回写（sourceId 存的是资产 id）那一路。 */
  assetId?: string | null;
}): Promise<LibraryAsset | null> {
  const hashKeys = await Promise.all(
    PROJECT_LIBRARY_PRODUCERS.map(async (producer) =>
      projectLibraryGenerationRecordId(producer, input.url)),
  );
  const wanted = new Set(hashKeys);
  if (input.assetId) wanted.add(input.assetId);
  try {
    const response = await libraryApi.listAssets({
      scope: 'PROJECT',
      projectId: input.projectId,
      origin: 'GENERATED',
      page: 1,
      size: 200,
    });
    const records = response.data.data?.records ?? [];
    return records.find((asset) => asset.sourceId != null && wanted.has(asset.sourceId)) ?? null;
  } catch {
    return null;
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
