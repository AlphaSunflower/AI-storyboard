import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MessageBubble, splitMentionParts } from './MessageBubble';

beforeEach(() => {
  // MessageBubble 的打字机 effect 会读 prefers-reduced-motion，jsdom 没有 matchMedia
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
    matches: false,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('splitMentionParts（气泡里的 @ 标记着色）', () => {
  it('只认本轮真实登记的标记，手打的 @ 不吃', () => {
    const parts = splitMentionParts('看 @分镜3 和 @随便写的', ['@分镜3']);
    expect(parts.filter((part) => part.mention).map((part) => part.text)).toEqual(['@分镜3']);
    expect(parts.map((part) => part.text).join('')).toBe('看 @分镜3 和 @随便写的');
  });

  it('没有标记时原样返回整行', () => {
    expect(splitMentionParts('普通文本', undefined)).toEqual([{ text: '普通文本', mention: false }]);
  });

  it('同名不同长度的标记按最长优先匹配，不产生半截着色', () => {
    const parts = splitMentionParts('@分镜3 与 @分镜3#2', ['@分镜3', '@分镜3#2']);
    expect(parts.filter((part) => part.mention).map((part) => part.text))
      .toEqual(['@分镜3', '@分镜3#2']);
  });

  it('标记里的正则元字符按字面量处理', () => {
    const parts = splitMentionParts('引用 @设定(最终版).docx 结束', ['@设定(最终版).docx']);
    expect(parts.filter((part) => part.mention)).toHaveLength(1);
  });
});

describe('MessageBubble 渲染 @ 引用标记', () => {
  it('用户消息气泡把已登记的 @ 标记渲染成蓝色', () => {
    const { container } = render(
      <MessageBubble
        role="user"
        content="把 @分镜3 的镜头拉近一点"
        metadata={{
          messageInput: {
            references: [{
              kind: 'scene',
              id: 'scene-3',
              token: '@分镜3',
              name: '分镜3',
              sceneNumber: 3,
            }],
          },
        }}
      />,
    );

    const marked = container.querySelectorAll('[data-mention="true"]');
    expect(marked).toHaveLength(1);
    expect(marked[0].textContent).toBe('@分镜3');
    expect(container.textContent).toContain('的镜头拉近一点');
  });

  it('metadata 里没有引用时不做任何标记（零回归）', () => {
    const { container } = render(
      <MessageBubble role="user" content="普通问题" />,
    );
    expect(container.querySelectorAll('[data-mention="true"]')).toHaveLength(0);
    expect(container.textContent).toContain('普通问题');
  });
});
