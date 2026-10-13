import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useChatApps } from './useChatApps';

const currentProject = { id: 'project-1', name: '项目一' };

function canvasMode(canvasId: string) {
  return {
    conversationId: 'conversation-1',
    projectId: 'project-1',
    parentOrigin: 'http://localhost:3000',
    canvasId,
  };
}

describe('useChatApps', () => {
  it('重复打开内容相同的应用时保持 tabs 引用不变', () => {
    const { result } = renderHook(() => useChatApps(currentProject));

    act(() => result.current.open('canvas', { canvasMode: canvasMode('canvas-1') }));
    const tabs = result.current.tabs;
    expect(tabs).toHaveLength(1);

    act(() => result.current.open('canvas', { canvasMode: canvasMode('canvas-1') }));

    expect(result.current.tabs).toBe(tabs);
  });

  it('画布目标变化时重建 tab', () => {
    const { result } = renderHook(() => useChatApps(currentProject));

    act(() => result.current.open('canvas', { canvasMode: canvasMode('canvas-1') }));
    const tabs = result.current.tabs;

    act(() => result.current.open('canvas', { canvasMode: canvasMode('canvas-2') }));

    expect(result.current.tabs).not.toBe(tabs);
    expect(result.current.tabs[0].canvasMode?.canvasId).toBe('canvas-2');
    expect(result.current.tabs[0].url).toContain('canvas=canvas-2');
  });
});
