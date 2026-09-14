import { beforeEach, describe, expect, it } from 'vitest';
import { buildChatAppDescriptor } from './chatAppDefinitions';

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

  it('does not open the canvas without authentication', () => {
    expect(buildChatAppDescriptor('canvas', null)).toBeNull();
  });
});
