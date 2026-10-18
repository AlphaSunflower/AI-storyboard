import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { RunTaskGraphCard } from './RunTaskGraphCard';
import { useAgentStore } from '../../stores/agentStore';
import type { AgentToolDraft } from '../../api/agent';

function draft(overrides: Partial<AgentToolDraft>): AgentToolDraft {
  return {
    name: 'run_task_graph',
    text: '',
    input: '',
    status: 'node_running',
    ...overrides,
  };
}

describe('RunTaskGraphCard', () => {
  beforeEach(() => {
    useAgentStore.setState({ subagentProgress: {} });
  });

  afterEach(() => {
    cleanup();
  });

  it('shows a restoring placeholder instead of vanishing on a partial input', () => {
    render(<RunTaskGraphCard draft={draft({
      status: 'node_running',
      input: '{"task":"恢复中","nodes":[{"id":"script"',
    })} />);

    expect(screen.getByTestId('run-task-graph-restoring')).toBeTruthy();
    expect(screen.getByText('正在恢复任务图…')).toBeTruthy();
  });

  it('uses live per-node progress from SUBAGENT events', () => {
    useAgentStore.setState({
      subagentProgress: {
        'call-1': { research: { status: 'RUNNING', role: 'researcher' } },
      },
    });
    render(<RunTaskGraphCard toolCallId="call-1" draft={draft({
      input: JSON.stringify({
        task: '实时',
        nodes: [{ id: 'research', role: 'researcher' }],
      }),
    })} />);

    expect(screen.getByText('运行中')).toBeTruthy();
  });

  it('renders repair and second-review rows not present in the original graph', () => {
    render(<RunTaskGraphCard draft={draft({
      status: 'node_completed',
      input: JSON.stringify({
        task: '修复轮',
        nodes: [{ id: 'review', role: 'reviewer' }],
      }),
      text: JSON.stringify({
        results: [
          { id: 'review', status: 'COMPLETED', output: '不通过' },
          { id: 'script-fix', status: 'COMPLETED', output: '已修订' },
          { id: 'review-2', status: 'COMPLETED', output: '通过' },
        ],
      }),
    })} />);

    expect(screen.getAllByText('修订').length).toBeGreaterThan(0);
    expect(screen.getByText('复核')).toBeTruthy();
    expect(screen.getAllByText('通过').length).toBeGreaterThan(0);
  });

  it('renders nodes, dependencies and completed results', () => {
    render(<RunTaskGraphCard draft={draft({
      status: 'node_completed',
      input: JSON.stringify({
        task: '分镜试点',
        nodes: [
          { id: 'research', role: 'researcher', instruction: '调研' },
          { id: 'review', role: 'reviewer', dependsOn: ['research'] },
        ],
      }),
      text: JSON.stringify({
        ok: true,
        result: {
          success: true,
          results: [
            { id: 'research', status: 'COMPLETED', output: '调研完成' },
            { id: 'review', status: 'COMPLETED', output: '通过' },
          ],
        },
      }),
    })} />);

    expect(screen.getByText('分镜试点')).toBeTruthy();
    expect(screen.getByText('调研')).toBeTruthy();
    expect(screen.getByText('依赖')).toBeTruthy();
    expect(screen.getAllByText('调研完成').length).toBeGreaterThan(0);
    expect(screen.getAllByText('完成')).toHaveLength(2);
  });

  it('shows pending while the tool is still running', () => {
    render(<RunTaskGraphCard draft={draft({
      input: JSON.stringify({
        task: '等待中',
        nodes: [{ id: 'research', role: 'researcher' }],
      }),
    })} />);

    expect(screen.getByText('等待')).toBeTruthy();
  });

  it('renders nothing when the input is not a task graph', () => {
    const { container } = render(<RunTaskGraphCard draft={draft({ input: '{"foo":1}' })} />);
    expect(container.querySelector('[data-testid="run-task-graph"]')).toBeNull();
  });
});
