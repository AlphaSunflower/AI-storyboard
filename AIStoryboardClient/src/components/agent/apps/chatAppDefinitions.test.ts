import { beforeEach, describe, expect, it } from 'vitest';
import { buildChatAppDescriptor, canvasIdFromUrl } from './chatAppDefinitions';

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

  it('passes authentication and project context to the canvas app', () => {
    localStorage.setItem('accessToken', 'access-token');
    localStorage.setItem('user', JSON.stringify({ userId: 'user-1', displayName: '测试用户' }));

    const descriptor = buildChatAppDescriptor('canvas', { id: 'project-1', name: '测试项目' });
    expect(descriptor).not.toBeNull();

    const url = new URL(descriptor!.url);
    expect(url.searchParams.get('token')).toBe('access-token');
    expect(url.searchParams.get('userId')).toBe('user-1');
    expect(url.searchParams.get('name')).toBe('测试用户');
    expect(url.hash).toBe('#project=project-1');
  });

  it('opens the canvas app in controlled canvas mode', () => {
    localStorage.setItem('accessToken', 'access-token');
    localStorage.setItem('user', JSON.stringify({ userId: 'user-1', displayName: '测试用户' }));

    const descriptor = buildChatAppDescriptor('canvas', { id: 'project-1', name: '测试项目' }, {
      canvasMode: {
        conversationId: 'conversation-1',
        projectId: 'project-1',
        parentOrigin: 'http://localhost:5173',
      },
    });

    expect(descriptor).not.toBeNull();
    const url = new URL(descriptor!.url);
    expect(url.searchParams.get('embedded')).toBe('1');
    expect(url.searchParams.get('canvasMode')).toBe('1');
    expect(url.searchParams.get('conversationId')).toBe('conversation-1');
    expect(url.searchParams.get('projectId')).toBe('project-1');
    expect(url.searchParams.get('parentOrigin')).toBe('http://localhost:5173');
    expect(url.hash).toBe('#project=project-1');
    expect(descriptor!.canvasMode?.conversationId).toBe('conversation-1');
  });

  it('reopens a bound canvas directly in controlled mode', () => {
    localStorage.setItem('accessToken', 'access-token');

    const descriptor = buildChatAppDescriptor('canvas', { id: 'project-1', name: '测试项目' }, {
      canvasMode: {
        conversationId: 'conversation-1',
        projectId: 'project-1',
        canvasId: 'canvas-1',
        parentOrigin: 'http://localhost:5173',
      },
    });

    expect(new URL(descriptor!.url).hash).toBe('#project=project-1&canvas=canvas-1');
  });

  it('does not open the canvas without authentication', () => {
    expect(buildChatAppDescriptor('canvas', null)).toBeNull();
  });

  it('recovers the active canvas id from an existing child window url', () => {
    expect(canvasIdFromUrl('http://localhost:3000/#project=project-1&canvas=canvas-1')).toBe('canvas-1');
    expect(canvasIdFromUrl('http://localhost:3000/#project=project-1')).toBeUndefined();
  });
});
