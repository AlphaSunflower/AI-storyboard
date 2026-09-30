import type { AgentAttachment, AgentMessage } from '../api/agent';
import type { LibraryMedia } from '../api/library';

const LIBRARY_MEDIA_PATH = /\/api\/library\/media\/([^/?#]+)\/content(?:\?|$)/i;

export function libraryMediaIdFromUrl(url?: string): string | null {
  if (!url) return null;
  const match = LIBRARY_MEDIA_PATH.exec(url);
  return match?.[1] ? decodeURIComponent(match[1]) : null;
}

function replaceLibraryUrl(
  url: string,
  replacements: Map<string, string>,
): string {
  const mediaId = libraryMediaIdFromUrl(url);
  if (!mediaId) return url;
  return replacements.get(mediaId) ?? url;
}

async function resolveReplacements(
  mediaIds: Set<string>,
  resolveMedia: (mediaId: string) => Promise<LibraryMedia | null>,
): Promise<Map<string, string>> {
  const replacements = new Map<string, string>();
  await Promise.all([...mediaIds].map(async (mediaId) => {
    try {
      const media = await resolveMedia(mediaId);
      const url = media?.contentUrl || media?.url;
      if (url) replacements.set(mediaId, url);
    } catch {
      // Keep the persisted URL when asset-service is temporarily unavailable.
    }
  }));
  return replacements;
}

export async function refreshLibraryMediaAttachments(
  attachments: AgentAttachment[],
  resolveMedia: (mediaId: string) => Promise<LibraryMedia | null>,
): Promise<AgentAttachment[]> {
  const mediaIds = new Set<string>();
  for (const attachment of attachments) {
    const mediaId = attachment.mediaId ?? libraryMediaIdFromUrl(attachment.url);
    if (mediaId) mediaIds.add(mediaId);
  }
  if (mediaIds.size === 0) return attachments;
  const replacements = await resolveReplacements(mediaIds, resolveMedia);
  if (replacements.size === 0) return attachments;
  return attachments.map((attachment) => {
    const mediaId = attachment.mediaId ?? libraryMediaIdFromUrl(attachment.url);
    const url = mediaId ? replacements.get(mediaId) : undefined;
    return url && mediaId
      ? { ...attachment, url, mediaId, source: 'library' }
      : attachment;
  });
}

export async function refreshLibraryMediaMessages(
  messages: AgentMessage[],
  resolveMedia: (mediaId: string) => Promise<LibraryMedia | null>,
): Promise<AgentMessage[]> {
  const mediaIds = new Set<string>();
  for (const message of messages) {
    const input = message.metadata?.messageInput;
    if (!input) continue;
    for (const url of [
      ...(input.picUrls ?? []),
      ...(input.referenceVideos ?? []),
      ...(input.referenceAudios ?? []),
    ]) {
      const mediaId = libraryMediaIdFromUrl(url);
      if (mediaId) mediaIds.add(mediaId);
    }
    for (const attachment of input.attachments ?? []) {
      const mediaId = attachment.mediaId ?? libraryMediaIdFromUrl(attachment.url);
      if (mediaId) mediaIds.add(mediaId);
    }
  }
  if (mediaIds.size === 0) return messages;

  const replacements = await resolveReplacements(mediaIds, resolveMedia);
  if (replacements.size === 0) return messages;

  return messages.map((message) => {
    const input = message.metadata?.messageInput;
    if (!input) return message;
    const attachments = input.attachments?.map((attachment): AgentAttachment => {
      const mediaId = attachment.mediaId ?? libraryMediaIdFromUrl(attachment.url);
      const url = mediaId ? replacements.get(mediaId) : undefined;
      if (!url) return attachment;
      return mediaId
        ? { ...attachment, url, mediaId, source: 'library' }
        : attachment;
    });
    return {
      ...message,
      metadata: {
        ...message.metadata,
        messageInput: {
          ...input,
          picUrls: input.picUrls?.map((url) => replaceLibraryUrl(url, replacements)),
          referenceVideos: input.referenceVideos?.map((url) => replaceLibraryUrl(url, replacements)),
          referenceAudios: input.referenceAudios?.map((url) => replaceLibraryUrl(url, replacements)),
          ...(attachments ? { attachments } : {}),
        },
      },
    };
  });
}
