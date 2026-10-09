import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AgentConversationList } from './AgentConversationList';
import { useAgentStore } from '../../stores/agentStore';

function conversation(id: string, title: string) {
  return {
    id,
    title,
    status: 'active',
    createdAt: '2026-10-09T10:00:00.000Z',
    updatedAt: new Date().toISOString(),
  } as never;
}

describe('AgentConversationList generation markers', () => {
  beforeEach(() => {
    useAgentStore.setState({
      conversations: [conversation('c1', '对话一'), conversation('c2', '对话二')],
      activeConversationId: 'c1',
      generating: {},
      replyUnread: {},
      taskUnread: {},
    });
  });

  afterEach(() => {
    cleanup();
    useAgentStore.setState({ conversations: [], generating: {}, replyUnread: {}, taskUnread: {} });
  });

  it('marks a conversation as generating while its turn is still running', () => {
    useAgentStore.setState({ generating: { c2: { hint: '' } } });
    render(<AgentConversationList />);

    expect(screen.getByLabelText('正在生成').textContent).toContain('生成中');
  });

  it('drops the generating marker once the turn has finished', () => {
    useAgentStore.setState({ generating: { c2: { hint: '', finished: true } } });
    render(<AgentConversationList />);

    expect(screen.queryByLabelText('正在生成')).toBeNull();
  });

  it('shows a new marker after a background reply completes', () => {
    useAgentStore.setState({ replyUnread: { c2: true } });
    render(<AgentConversationList />);

    expect(screen.getByLabelText('有新回复')).toBeTruthy();
  });
});
