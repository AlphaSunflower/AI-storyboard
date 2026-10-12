import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  buildCanvasSiteUrl,
  buildChatAppDescriptor,
  canvasIdFromUrl,
  openCanvasSite,
  openChatAppEntry,
} from './chatAppDefinitions';

describe('chat app definitions', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('builds the embedded docs app without project context', () => {
    expect(buildChatAppDescriptor('docs', null)).toEqual({
      key: 'docs',
      title: '使用文档',
      url: '/docs?embedded=1',
    });
  });

  it('builds a local document tab without an external URL', () => {
    const attachment = {
      url: '/api/files/files/plan.docx',
      name: '项目计划书.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      kind: 'file' as const,
      source: 'upload' as const,
    };
    expect(buildChatAppDescriptor('document', null, {
      documentAttachment: attachment,
    })).toEqual({
      key: 'document',
      title: '项目计划书.docx',
      url: '',
      documentAttachment: attachment,
    });
  });

  it('builds a temporary side-chat child window for the current project', () => {
    expect(buildChatAppDescriptor('side-chat', null, {
      sideChat: {
        conversationId: 'side-1',
        projectId: 'project-1',
        sourceTitle: '主对话',
      },
    })).toEqual({
      key: 'side-chat',
      title: '侧边 · 主对话',
      url: '/chat?embed=1&sideChatId=side-1&projectId=project-1',
      projectId: 'project-1',
    });
  });

  it('builds an editor tab with the current project', () => {
    expect(buildChatAppDescriptor('editor', { id: 'project-1', name: '测试项目' })).toEqual({
      key: 'editor',
      title: '分镜编辑器 · 测试项目',
      url: '/editor?embedded=1&projectId=project-1',
      projectId: 'project-1',
    });
    expect(buildChatAppDescriptor('editor', null)).toBeNull();
  });

  it('builds a project library tab with optional project context', () => {
    expect(buildChatAppDescriptor('library', { id: 'project-1', name: '测试项目' })).toEqual({
      key: 'library',
      title: '项目库 · 测试项目',
      url: '/library?embedded=1&projectId=project-1',
      projectId: 'project-1',
    });
    expect(buildChatAppDescriptor('library', null)).toEqual({
      key: 'library',
      title: '项目库',
      url: '/library?embedded=1',
    });
  });

  it('passes project context to the same-origin canvas app without URL credentials', () => {
    const descriptor = buildChatAppDescriptor('canvas', { id: 'project-1', name: '测试项目' });
    expect(descriptor).not.toBeNull();

    const url = new URL(descriptor!.url, window.location.origin);
    expect(url.pathname).toBe('/canvas/');
    expect(url.searchParams.get('token')).toBeNull();
    expect(url.searchParams.get('embedded')).toBe('1');
    expect(url.searchParams.get('userId')).toBeNull();
    expect(url.hash).toBe('#project=project-1');
  });

  it('opens the canvas app in controlled canvas mode', () => {
    const descriptor = buildChatAppDescriptor('canvas', { id: 'project-1', name: '测试项目' }, {
      canvasMode: {
        conversationId: 'conversation-1',
        projectId: 'project-1',
        parentOrigin: 'http://localhost:5173',
      },
    });

    expect(descriptor).not.toBeNull();
    const url = new URL(descriptor!.url, window.location.origin);
    expect(url.searchParams.get('embedded')).toBe('1');
    expect(url.searchParams.get('canvasMode')).toBe('1');
    expect(url.searchParams.get('conversationId')).toBe('conversation-1');
    expect(url.searchParams.get('projectId')).toBe('project-1');
    expect(url.searchParams.get('parentOrigin')).toBe('http://localhost:5173');
    expect(url.hash).toBe('#project=project-1');
    expect(descriptor!.canvasMode?.conversationId).toBe('conversation-1');
  });

  it('reopens a bound canvas directly in controlled mode', () => {
    const descriptor = buildChatAppDescriptor('canvas', { id: 'project-1', name: '测试项目' }, {
      canvasMode: {
        conversationId: 'conversation-1',
        projectId: 'project-1',
        canvasId: 'canvas-1',
        parentOrigin: 'http://localhost:5173',
      },
    });

    expect(new URL(descriptor!.url, window.location.origin).hash).toBe('#project=project-1&canvas=canvas-1');
  });

  it('opens the canvas without project context for cookie-backed authentication', () => {
    expect(buildChatAppDescriptor('canvas', null)?.url).toBe('/canvas/?embedded=1');
  });

  it('recovers the active canvas id from an existing child window url', () => {
    expect(canvasIdFromUrl('http://localhost:3000/#project=project-1&canvas=canvas-1')).toBe('canvas-1');
    expect(canvasIdFromUrl('http://localhost:3000/#project=project-1')).toBeUndefined();
  });

  it('builds the canvas site url without embedding parameters', () => {
    expect(buildCanvasSiteUrl()).toBe('/canvas/');
    expect(buildCanvasSiteUrl({ projectId: 'project-1' })).toBe('/canvas/#project=project-1');
    expect(buildCanvasSiteUrl({ projectId: 'project-1', canvasId: 'canvas-1' }))
      .toBe('/canvas/#project=project-1&canvas=canvas-1');
  });

  it('opens the canvas site in its own window rather than an embedded frame', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue({} as Window);

    openCanvasSite({ projectId: 'project-1', canvasId: 'canvas-1' });

    expect(open).toHaveBeenCalledWith(
      new URL('/canvas/#project=project-1&canvas=canvas-1', window.location.origin).toString(),
      '_blank',
      'noopener,noreferrer',
    );
    open.mockRestore();
  });

  it('does not navigate the chat page when the new tab reports no handle', () => {
    // 带 noopener 时 window.open 按规范返回 null，这不是弹窗被拦截，
    // 不能据此回退到当前页跳转，否则新标签页和 /chat 会一起跳。
    const open = vi.spyOn(window, 'open').mockReturnValue(null);

    openCanvasSite({ projectId: 'project-1' });

    expect(open).toHaveBeenCalledTimes(1);
    expect(open).toHaveBeenCalledWith(
      new URL('/canvas/#project=project-1', window.location.origin).toString(),
      '_blank',
      'noopener,noreferrer',
    );
    open.mockRestore();
  });

  it('sends the canvas action to the site and keeps other apps embedded', () => {
    const openEmbedded = vi.fn();
    const open = vi.spyOn(window, 'open').mockReturnValue({} as Window);
    const project = { id: 'project-1', name: '测试项目' };

    openChatAppEntry(openEmbedded, 'canvas', project);

    expect(open).toHaveBeenCalledWith(
      new URL('/canvas/#project=project-1', window.location.origin).toString(),
      '_blank',
      'noopener,noreferrer',
    );
    expect(openEmbedded).not.toHaveBeenCalled();

    openChatAppEntry(openEmbedded, 'editor', project);

    expect(openEmbedded).toHaveBeenCalledWith('editor');
    expect(open).toHaveBeenCalledTimes(1);
    open.mockRestore();
  });
});
