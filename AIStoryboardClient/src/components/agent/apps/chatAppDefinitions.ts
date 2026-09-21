import type { CanvasModeOptions, EmbeddedAppDescriptor } from './EmbeddedAppWindow';

export interface ChatAppProject {
  id?: string;
  name?: string;
}

export interface ChatAppOpenOptions {
  canvasMode?: CanvasModeOptions;
}

export const CHAT_APP_KEYS = ['canvas', 'editor', 'docs', 'library'] as const;

export type ChatAppKey = typeof CHAT_APP_KEYS[number];

export function canvasIdFromUrl(url: string): string | undefined {
  try {
    const match = new URL(url).hash.match(/(?:^#|&)canvas=([^&]+)/);
    return match ? decodeURIComponent(match[1]) : undefined;
  } catch {
    return undefined;
  }
}

export function buildChatAppDescriptor(
  key: ChatAppKey,
  currentProject: ChatAppProject | null,
  options: ChatAppOpenOptions = {},
): EmbeddedAppDescriptor | null {
  if (key === 'docs') {
    return { key, title: '使用文档', url: '/docs?embedded=1' };
  }

  if (key === 'library') {
    const params = new URLSearchParams({ embedded: '1' });
    if (currentProject?.id) params.set('projectId', currentProject.id);
    return {
      key,
      title: currentProject?.name ? `项目库 · ${currentProject.name}` : '项目库',
      url: `/library?${params.toString()}`,
      projectId: currentProject?.id,
    };
  }

  if (key === 'editor') {
    if (!currentProject?.id) return null;
    const params = new URLSearchParams({ embedded: '1', projectId: currentProject.id });
    return {
      key,
      title: currentProject.name ? `分镜编辑器 · ${currentProject.name}` : '分镜编辑器',
      url: `/editor?${params.toString()}`,
      projectId: currentProject.id,
    };
  }

  const token = localStorage.getItem('accessToken');
  if (!token) return null;
  let userId = '';
  let name = '';
  try {
    const userInfo = JSON.parse(localStorage.getItem('user') ?? '{}') as {
      userId?: string;
      displayName?: string;
    };
    userId = userInfo.userId ?? '';
    name = userInfo.displayName ?? '';
  } catch {
    // Keep the canvas usable even if the cached user payload is malformed.
  }

  const base = (import.meta.env.VITE_CANVAS_URL as string | undefined)?.trim().replace(/\/$/, '')
    || 'http://localhost:3000';
  const params = new URLSearchParams({ token });
  if (userId) params.set('userId', userId);
  if (name) params.set('name', name);
  const canvasMode = options.canvasMode;
  const projectId = canvasMode?.projectId ?? currentProject?.id;
  if (canvasMode) {
    params.set('embedded', '1');
    params.set('canvasMode', '1');
    params.set('conversationId', canvasMode.conversationId);
    params.set('parentOrigin', canvasMode.parentOrigin);
    params.set('projectId', canvasMode.projectId);
  }
  const hash = projectId
    ? `#project=${encodeURIComponent(projectId)}${canvasMode?.canvasId ? `&canvas=${encodeURIComponent(canvasMode.canvasId)}` : ''}`
    : '';

  return {
    key,
    title: '画布',
    url: `${base}?${params.toString()}${hash}`,
    projectId,
    ...(canvasMode ? { canvasMode } : {}),
  };
}
