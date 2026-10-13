import type { CanvasModeOptions, EmbeddedAppDescriptor } from './EmbeddedAppWindow';
import type { AgentAttachment } from '../../../api/agent';
import { buildSideChatFrameUrl } from '../sideChatFrame';

export interface ChatAppProject {
  id?: string;
  name?: string;
}

export interface ChatAppOpenOptions {
  canvasMode?: CanvasModeOptions;
  documentAttachment?: AgentAttachment;
  sideChat?: {
    conversationId: string;
    projectId: string;
    sourceTitle: string;
  };
}

export const CHAT_APP_KEYS = ['canvas', 'editor', 'docs', 'library', 'document', 'side-chat'] as const;

export type ChatAppKey = typeof CHAT_APP_KEYS[number];

export function canvasIdFromUrl(url: string): string | undefined {
  try {
    const match = new URL(url).hash.match(/(?:^#|&)canvas=([^&]+)/);
    return match ? decodeURIComponent(match[1]) : undefined;
  } catch {
    return undefined;
  }
}

/**
 * 画布网站地址：开发环境由 VITE_CANVAS_URL 指向画布前端（XiaoYueCanvasAgent），
 * 未配置时退回同源 /canvas。
 */
export function canvasSiteBase(): string {
  const base = (import.meta.env.VITE_CANVAS_URL as string | undefined)?.trim().replace(/\/$/, '')
    || '/canvas';
  return `${base}/`;
}

export interface CanvasSiteLocation {
  projectId?: string;
  canvasId?: string;
  /** 新窗口进入画布时随 URL 携带的媒体（画布子站再透传给内嵌 Moon 对话） */
  media?: string[];
}

/** 画布网站自身的地址：不带 embedded / canvasMode，只保留项目与画布定位。 */
export function buildCanvasSiteUrl({ projectId, canvasId, media }: CanvasSiteLocation = {}): string {
  if (!projectId) return canvasSiteBase();
  const mediaParam = media?.length
    ? `&media=${encodeURIComponent(JSON.stringify(media))}`
    : '';
  return `${canvasSiteBase()}#project=${encodeURIComponent(projectId)}${
    canvasId ? `&canvas=${encodeURIComponent(canvasId)}` : ''}${mediaParam}`;
}

/**
 * 直接打开画布网站，不进 /chat 的内嵌小窗。
 *
 * 只走新标签页，不按 window.open 的返回值回退到当前页跳转：带 noopener 时
 * 浏览器按规范返回 null，那不是"弹窗被拦截"，据此回退会把 /chat 一起跳走。
 */
export function openCanvasSite(location: CanvasSiteLocation = {}): void {
  const target = new URL(buildCanvasSiteUrl(location), window.location.origin).toString();
  window.open(target, '_blank', 'noopener,noreferrer');
}

/**
 * 顶栏应用入口的跳转规则：画布直接打开画布网站本身，
 * 编辑器/项目库/文档/侧边对话仍进 /chat 的内嵌小窗。
 */
export function openChatAppEntry(
  openEmbedded: (key: ChatAppKey, options?: ChatAppOpenOptions) => void,
  key: ChatAppKey,
  currentProject: ChatAppProject | null,
): void {
  if (key === 'canvas') {
    openCanvasSite({ projectId: currentProject?.id });
    return;
  }
  openEmbedded(key);
}

export function buildChatAppDescriptor(
  key: ChatAppKey,
  currentProject: ChatAppProject | null,
  options: ChatAppOpenOptions = {},
): EmbeddedAppDescriptor | null {
  if (key === 'document') {
    const attachment = options.documentAttachment;
    if (!attachment) return null;
    return {
      key,
      title: attachment.name || '文档预览',
      url: '',
      documentAttachment: attachment,
    };
  }

  if (key === 'side-chat') {
    const sideChat = options.sideChat;
    if (!sideChat) return null;
    return {
      key,
      title: sideChat.sourceTitle ? `侧边 · ${sideChat.sourceTitle}` : '侧边临时对话',
      url: buildSideChatFrameUrl(sideChat.conversationId, sideChat.projectId),
      projectId: sideChat.projectId,
    };
  }

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

  const normalizedBase = canvasSiteBase();
  const params = new URLSearchParams({ embedded: '1' });
  const canvasMode = options.canvasMode;
  const projectId = canvasMode?.projectId ?? currentProject?.id;
  if (canvasMode) {
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
    url: `${normalizedBase}?${params.toString()}${hash}`,
    projectId,
    ...(canvasMode ? { canvasMode } : {}),
  };
}
