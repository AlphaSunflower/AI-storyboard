import type { LibraryAsset, MediaType } from '../api/library';

export const PROJECT_LIBRARY_DRAG_MIME = 'application/x-ai-storyboard-project-library';
export const CHAT_MEDIA_DRAG_MIME = 'application/x-ai-storyboard-chat-media';

export type ProjectLibraryDragPayload =
  | {
      kind: 'asset';
      assetId: string;
      mediaType: MediaType;
      url: string;
      name: string;
    }
  | {
      kind: 'group';
      groupId: string;
      name: string;
    };

export interface ChatMediaDragPayload {
  projectId: string;
  /** 素材所属对话：拖进项目库时据此归到对应的「产出素材」文件夹。 */
  conversationId?: string;
  mediaType: Extract<MediaType, 'IMAGE' | 'VIDEO'>;
  url: string;
  name: string;
}

export function serializeDragPayload(payload: ProjectLibraryDragPayload | ChatMediaDragPayload): string {
  return JSON.stringify(payload);
}

export function buildProjectLibraryDragPayload(
  asset: Pick<LibraryAsset, 'id' | 'name' | 'type' | 'coverUrl'>,
): ProjectLibraryDragPayload | null {
  if (asset.type === 'GROUP') {
    return { kind: 'group', groupId: asset.id, name: asset.name };
  }
  if (asset.coverUrl && (asset.type === 'IMAGE' || asset.type === 'VIDEO' || asset.type === 'AUDIO')) {
    return {
      kind: 'asset',
      assetId: asset.id,
      mediaType: asset.type,
      url: asset.coverUrl,
      name: asset.name,
    };
  }
  return null;
}

export function parseProjectLibraryDragPayload(raw: string): ProjectLibraryDragPayload | null {
  try {
    const value = JSON.parse(raw) as Partial<ProjectLibraryDragPayload> & { kind?: string };
    if (value.kind === 'group') {
      return typeof value.groupId === 'string' && value.groupId && typeof value.name === 'string'
        ? { kind: 'group', groupId: value.groupId, name: value.name }
        : null;
    }
    if (value.kind === 'asset') {
      return typeof value.assetId === 'string' && value.assetId
        && (value.mediaType === 'IMAGE' || value.mediaType === 'VIDEO' || value.mediaType === 'AUDIO')
        && typeof value.url === 'string' && value.url
        && typeof value.name === 'string'
        ? {
            kind: 'asset',
            assetId: value.assetId,
            mediaType: value.mediaType,
            url: value.url,
            name: value.name,
          }
        : null;
    }
    return null;
  } catch {
    return null;
  }
}

export function parseChatMediaDragPayload(raw: string): ChatMediaDragPayload | null {
  try {
    const value = JSON.parse(raw) as Partial<ChatMediaDragPayload>;
    return typeof value.projectId === 'string' && value.projectId
      && (value.mediaType === 'IMAGE' || value.mediaType === 'VIDEO')
      && typeof value.url === 'string' && value.url
      && typeof value.name === 'string'
      ? {
          projectId: value.projectId,
          conversationId: typeof value.conversationId === 'string' && value.conversationId
            ? value.conversationId
            : undefined,
          mediaType: value.mediaType,
          url: value.url,
          name: value.name,
        }
      : null;
  } catch {
    return null;
  }
}
