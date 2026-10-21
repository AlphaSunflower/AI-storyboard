import { useState } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 统计 react-markdown 真正被渲染的次数。
 *
 * 背景：ChatPage 订阅 `messages`，流式期间每来一个 `TEXT_BLOCK_DELTA` 都会整页重渲染，
 * 消息列表是逐条渲染的。如果 MarkdownContent 没有 memo，**每一条历史消息**都会跟着
 * 重新解析一遍 markdown —— 对话越长越卡，这是「前端特别卡」的主因之一。
 * 这个文件锁住「只有内容变了的那条才重新解析」这个行为，防止 memo 被无意中改掉
 * （例如把稳定回调换回内联箭头函数，memo 会静默失效）。
 */
const markdownRenders = vi.hoisted(() => ({ count: 0 }));

vi.mock('react-markdown', () => ({
  default: ({ children }: { children?: React.ReactNode }) => {
    markdownRenders.count += 1;
    return <div data-testid="markdown">{children}</div>;
  },
}));

const { MessageBubble } = await import('./MessageBubble');

function Harness() {
  const [second, setSecond] = useState('第二条：镜头二推进到门前');
  return (
    <div>
      <MessageBubble role="assistant" content="第一条：镜头一建立环境" />
      <MessageBubble role="assistant" content={second} />
      <button type="button" onClick={() => setSecond('第二条：镜头二推进到门前（已改）')}>
        改第二条
      </button>
    </div>
  );
}

describe('MessageBubble markdown 重解析', () => {
  beforeEach(() => {
    markdownRenders.count = 0;
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

  it('只重新解析正文变化的那一条消息', () => {
    render(<Harness />);
    const baseline = markdownRenders.count;
    expect(baseline).toBeGreaterThan(0);
    // 两条消息都在渲染 markdown：没有 memo 时下面这次重渲染会同时命中它们（差值为 2）。
    expect(screen.getAllByTestId('markdown')).toHaveLength(2);

    fireEvent.click(screen.getByRole('button', { name: '改第二条' }));

    // 没有 memo 时这里会是 2（两条都重解析）；有 memo 时只有变化的那条重新解析。
    expect(markdownRenders.count - baseline).toBe(1);
  });
});
