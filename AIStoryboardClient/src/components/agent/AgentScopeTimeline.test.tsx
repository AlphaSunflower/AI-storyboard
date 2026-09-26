import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { useAgentStore, type AgentScopeTimelineEntry } from '../../stores/agentStore';
import { AgentScopeTimeline } from './AgentScopeTimeline';

function entry(overrides: Partial<AgentScopeTimelineEntry>): AgentScopeTimelineEntry {
  return {
    id: 'entry-1',
    turnId: 'turn-1',
    seq: 1,
    kind: 'plan',
    title: '生成执行计划',
    status: 'node_running',
    ...overrides,
  };
}

describe('AgentScopeTimeline', () => {
  afterEach(() => {
    cleanup();
    useAgentStore.setState({ activeConversationId: null, timelineByConversation: {} });
  });

  it('renders plan and subagent activity from turn events', () => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      timelineByConversation: {
        'conversation-1': [
          entry({ id: 'plan-1', seq: 3, title: '整理参考素材', status: 'node_completed' }),
          entry({
            id: 'subagent-1',
            seq: 4,
            kind: 'subagent',
            title: '视觉提示词导演',
            agent: 'visual-prompt-director',
            status: 'node_started',
          }),
        ],
      },
    });

    render(<AgentScopeTimeline />);

    expect(screen.getByLabelText('AgentScope 执行时间线')).toBeTruthy();
    expect(screen.getByText('整理参考素材')).toBeTruthy();
    expect(screen.getByText('视觉提示词导演')).toBeTruthy();
    expect(screen.getByText('visual-prompt-director')).toBeTruthy();
    expect(screen.getByText('已完成')).toBeTruthy();
    expect(screen.getByText('进行中')).toBeTruthy();
  });

  it('stays hidden when only artifact or tool activity exists', () => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      timelineByConversation: {
        'conversation-1': [
          entry({ id: 'artifact-1', kind: 'artifact', title: 'report.pdf', status: 'node_completed' }),
        ],
      },
    });

    render(<AgentScopeTimeline />);

    expect(screen.queryByLabelText('AgentScope 执行时间线')).toBeNull();
  });
});
