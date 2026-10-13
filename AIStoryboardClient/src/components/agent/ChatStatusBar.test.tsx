import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ChatStatusBar } from './ChatStatusBar';
import { useAgentStore } from '../../stores/agentStore';
import type { AgentMessage, AgentTurnMetrics } from '../../api/agent';

function turn(partial: Partial<AgentTurnMetrics>): AgentTurnMetrics {
  return {
    inputTokens: 0,
    outputTokens: 0,
    cachedInputTokens: 0,
    totalTokens: 0,
    durationMs: 0,
    modelCalls: 1,
    complete: true,
    ...partial,
  };
}

const assistantMetrics = turn({
  inputTokens: 7_177_232,
  outputTokens: 52_533,
  cachedInputTokens: 7_093_632,
  totalTokens: 7_229_765,
  contextTokens: 127_000,
  contextWindowTokens: 1_000_000,
  durationMs: 220_000,
  modelCalls: 104,
  modelDurationMs: 200_000,
  toolDurationMs: 12_000,
  systemPromptTokens: 1_500,
  toolDefinitionTokens: 5_600,
  messageTokens: 119_900,
});

const messages: AgentMessage[] = [
  {
    id: 'u1', conversationId: 'conversation-1', role: 'user',
    content: '画一个分镜', createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'a1', conversationId: 'conversation-1', role: 'assistant',
    content: '好的', createdAt: '2026-01-01T00:00:01Z',
    metadata: { metrics: assistantMetrics },
  },
];

beforeEach(() => {
  useAgentStore.setState({
    activeConversationId: 'conversation-1',
    messages: [],
    generating: {},
  });
});

afterEach(cleanup);

describe('ChatStatusBar', () => {
  it('falls back to the disclaimer when there is no usage data', () => {
    render(<ChatStatusBar />);

    expect(screen.getByText('内容由 AI 生成，请仔细甄别')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /轮/ })).toBeNull();
  });

  it('summarizes rounds, steps, tokens and cache hit in the bar', () => {
    useAgentStore.setState({ messages });
    render(<ChatStatusBar />);

    expect(screen.getByText('1 轮 104 步')).toBeTruthy();
    expect(screen.getByText('7.2M tok')).toBeTruthy();
    expect(screen.getByText('缓存命中 99%')).toBeTruthy();
    expect(screen.getByRole('meter').textContent).toContain('13%');
  });

  it('opens the run statistics card with model and tool timing', () => {
    useAgentStore.setState({ messages });
    render(<ChatStatusBar />);

    fireEvent.click(screen.getByRole('button', { name: /1 轮 104 步/ }));

    const card = screen.getByRole('dialog', { name: '运行统计' });
    expect(card.textContent).toContain('轮次');
    expect(card.textContent).toContain('总步数');
    expect(card.textContent).toContain('模型用时');
    expect(card.textContent).toContain('3m20s');
    expect(card.textContent).toContain('工具用时');
    expect(card.textContent).toContain('输出速度');
  });

  it('marks missing timing metrics as unavailable instead of zero', () => {
    useAgentStore.setState({
      messages: [{
        id: 'a1', conversationId: 'conversation-1', role: 'assistant',
        content: '好的', createdAt: '2026-01-01T00:00:01Z',
        metadata: {
          metrics: turn({
            inputTokens: 1_000, outputTokens: 100, totalTokens: 1_100,
            durationMs: 2_000, modelCalls: 1,
          }),
        },
      }],
    });
    render(<ChatStatusBar />);

    fireEvent.click(screen.getByRole('button', { name: /1 步/ }));

    const card = screen.getByRole('dialog', { name: '运行统计' });
    expect(card.textContent).toContain('暂无');
    expect(card.textContent).not.toContain('0s');
  });

  it('opens the token usage card with uncached, cached-read and output rows', () => {
    useAgentStore.setState({ messages });
    render(<ChatStatusBar />);

    fireEvent.click(screen.getByRole('button', { name: /7\.2M tok/ }));

    const card = screen.getByRole('dialog', { name: 'Token 用量' });
    expect(card.textContent).toContain('7,229,765 tok');
    expect(card.textContent).toContain('未缓存输入');
    expect(card.textContent).toContain('83,600 tok');
    expect(card.textContent).toContain('缓存读取');
    expect(card.textContent).toContain('7,093,632 tok');
    expect(card.textContent).toContain('输出');
  });

  it('keeps a single card open at a time', () => {
    useAgentStore.setState({ messages });
    render(<ChatStatusBar />);

    fireEvent.click(screen.getByRole('button', { name: /1 轮 104 步/ }));
    fireEvent.click(screen.getByRole('button', { name: /7\.2M tok/ }));

    expect(screen.queryByRole('dialog', { name: '运行统计' })).toBeNull();
    expect(screen.getByRole('dialog', { name: 'Token 用量' })).toBeTruthy();
  });

  it('opens the context breakdown from the ring', () => {
    useAgentStore.setState({ messages });
    render(<ChatStatusBar />);

    fireEvent.click(screen.getByRole('meter'));

    const card = screen.getByRole('dialog', { name: '上下文组成明细' });
    expect(card.textContent).toContain('系统提示词');
    expect(card.textContent).toContain('工具定义');
    expect(card.textContent).toContain('对话消息');
    expect(card.querySelectorAll('[data-segment]')).toHaveLength(3);
  });

  it('closes the open card on Escape', () => {
    useAgentStore.setState({ messages });
    render(<ChatStatusBar />);

    fireEvent.click(screen.getByRole('button', { name: /1 轮 104 步/ }));
    expect(screen.getByRole('dialog', { name: '运行统计' })).toBeTruthy();

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByRole('dialog', { name: '运行统计' })).toBeNull();
  });

  it('hides the open card as soon as the wheel scrolls', () => {
    useAgentStore.setState({ messages });
    render(<ChatStatusBar />);

    fireEvent.click(screen.getByRole('button', { name: /1 轮 104 步/ }));
    expect(screen.getByRole('dialog', { name: '运行统计' })).toBeTruthy();

    fireEvent.wheel(document, { deltaY: 120 });

    expect(screen.queryByRole('dialog', { name: '运行统计' })).toBeNull();
  });

  it('hides the open card when the thread scrolls', () => {
    useAgentStore.setState({ messages });
    render(<ChatStatusBar />);

    fireEvent.click(screen.getByRole('button', { name: /7\.2M tok/ }));
    expect(screen.getByRole('dialog', { name: 'Token 用量' })).toBeTruthy();

    fireEvent.scroll(document);

    expect(screen.queryByRole('dialog', { name: 'Token 用量' })).toBeNull();
  });

  it('keeps the card while the reply is auto-scrolling the thread', () => {
    useAgentStore.setState({ messages, streaming: true });
    render(<ChatStatusBar />);

    fireEvent.click(screen.getByRole('button', { name: /7\.2M tok/ }));
    fireEvent.scroll(document);

    expect(screen.getByRole('dialog', { name: 'Token 用量' })).toBeTruthy();

    // 用户自己滚动仍然要收起
    fireEvent.wheel(document, { deltaY: 40 });
    expect(screen.queryByRole('dialog', { name: 'Token 用量' })).toBeNull();
  });

  it('keeps the card while scrolling inside the card itself', () => {
    useAgentStore.setState({ messages });
    render(<ChatStatusBar />);

    fireEvent.click(screen.getByRole('button', { name: /1 轮 104 步/ }));
    const card = screen.getByRole('dialog', { name: '运行统计' });

    fireEvent.wheel(card, { deltaY: 120 });

    expect(screen.getByRole('dialog', { name: '运行统计' })).toBeTruthy();
  });

  it('disables the context card for read-only conversations', () => {
    useAgentStore.setState({ messages });
    render(<ChatStatusBar disabled />);

    fireEvent.click(screen.getByRole('meter'));

    expect(screen.queryByRole('dialog', { name: '上下文组成明细' })).toBeNull();
  });

  it('closes any open card when the bar collapses with the composer', () => {
    useAgentStore.setState({ messages });
    const { rerender } = render(<ChatStatusBar />);

    fireEvent.click(screen.getByRole('button', { name: /1 轮 104 步/ }));
    expect(screen.getByRole('dialog', { name: '运行统计' })).toBeTruthy();

    rerender(<ChatStatusBar collapsed />);

    expect(screen.queryByRole('dialog', { name: '运行统计' })).toBeNull();
  });
});
