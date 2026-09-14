import type { EmbeddedAppDescriptor } from './EmbeddedAppWindow';

export interface ChatAppProject {
  id?: string;
  name?: string;
}

export const CHAT_APP_KEYS = ['canvas', 'editor', 'docs'] as const;

export type ChatAppKey = typeof CHAT_APP_KEYS[number];

export function buildChatAppDescriptor(
  key: ChatAppKey,
  currentProject: ChatAppProject | null,
): EmbeddedAppDescriptor | null {
  if (key === 'docs') {
    return { key, title: '使用文档', url: '/docs?embedded=1' };
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
  const projectId = currentProject?.id;
  const hash = projectId ? `#project=${encodeURIComponent(projectId)}` : '';

  return {
    key,
    title: '画布',
    url: `${base}?${params.toString()}${hash}`,
    projectId,
  };
}
