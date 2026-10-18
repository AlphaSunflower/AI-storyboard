import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ToolDraftList, ToolResultBlocks, TOOL_OUTPUT_PREVIEW_LIMIT } from './ToolDraftList';
import { useAgentStore } from '../../stores/agentStore';
import type { AgentToolDraft } from '../../api/agent';

afterEach(() => {
  cleanup();
  useAgentStore.setState({ activeConversationId: null, generating: {}, messages: [], streaming: false });
});

const draft = (overrides: Partial<AgentToolDraft> = {}): AgentToolDraft => ({
  name: 'run_python_script',
  text: 'print("hi")',
  input: '{"code":"print(\\"hi\\")"}',
  status: 'node_completed',
  seq: 1,
  ...overrides,
});

describe('ToolDraftList 工具输出', () => {
  it('没有草稿时不渲染任何东西', () => {
    const { container } = render(<ToolDraftList drafts={{}} />);
    expect(container.querySelector('[data-tool-draft-list]')).toBeNull();
  });

  it('过滤 __fragment__ 流式占位草稿', () => {
    render(<ToolDraftList drafts={{
      fragment: draft({ name: '__fragment__', text: '', input: '' }),
      real: draft(),
    }} />);

    expect(screen.getByText('工具调用（1）')).toBeDefined();
    expect(screen.queryByText('__fragment__')).toBeNull();
  });

  it('渲染工具名与状态，默认折叠输出、展开后显示 monospace 输出', () => {
    render(<ToolDraftList drafts={{ 'call-1': draft() }} />);

    expect(screen.getByText('工具调用（1）')).toBeDefined();
    expect(screen.getByText('run_python_script')).toBeDefined();
    expect(screen.getByText('已完成')).toBeDefined();

    // 折叠态：只有一行摘要，没有「输出」分区
    expect(screen.queryByText('输出')).toBeNull();

    fireEvent.click(screen.getByRole('button', { expanded: false }));
    expect(screen.getByText('输出')).toBeDefined();
    expect(screen.getByText('入参')).toBeDefined();
    expect(screen.getByText('print("hi")')).toBeDefined();
    expect(screen.getByText('{"code":"print(\\"hi\\")"}')).toBeDefined();
  });

  it('超长输出截断并给出「显示更多」', () => {
    const long = 'x'.repeat(TOOL_OUTPUT_PREVIEW_LIMIT + 50);
    render(<ToolDraftList drafts={{ 'call-long': draft({ text: long }) }} />);
    fireEvent.click(screen.getByRole('button', { expanded: false }));

    const more = screen.getByText(/显示更多/);
    expect(more.textContent).toContain(String(long.length));
    expect(screen.getByText(/…$/)).toBeDefined();

    fireEvent.click(more);
    // 展开后按钮变成「收起」，且不再截断
    expect(screen.getByText('收起')).toBeDefined();
  });

  it('纯 store state：直播（generating.toolDrafts）与重放（metadata 草稿）渲染同一份输出', () => {
    const replayDrafts: Record<string, AgentToolDraft> = {
      'call-replay': draft({ name: 'write_file', text: '重放输出', status: 'node_completed' }),
    };

    // 重放路径：没有 generating，只有 metadata 快照
    const replayRender = render(<ToolDraftList drafts={replayDrafts} />);
    expect(screen.getByText('write_file')).toBeDefined();
    expect(screen.getByText('重放输出')).toBeDefined();
    replayRender.unmount();

    // 直播路径：同一份草稿放在 generating 里，渲染结果一致
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      generating: {
        'conversation-1': {
          hint: '',
          turnId: 'turn-1',
          assistantId: 'assistant-1',
          finished: false,
          toolDrafts: replayDrafts,
        },
      },
    });
    render(<ToolDraftList liveAssistantId="assistant-1" />);

    expect(screen.getByText('write_file')).toBeDefined();
    expect(screen.getByText('重放输出')).toBeDefined();
  });

  it('直播草稿不属于当前气泡时不渲染别人的工具输出', () => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      generating: {
        'conversation-1': {
          hint: '',
          turnId: 'turn-1',
          assistantId: 'assistant-other',
          finished: false,
          toolDrafts: { 'call-x': draft({ name: 'other_tool' }) },
        },
      },
    });

    const { container } = render(<ToolDraftList liveAssistantId="assistant-1" />);
    expect(container.querySelector('[data-tool-draft-list]')).toBeNull();
  });
});

describe('ToolResultBlocks 渲染意图分派', () => {
  it('metadata.renders 按 kind 渲染，未知 kind 落到兜底', () => {
    render(
      <ToolResultBlocks
        metadata={{
          renders: [
            { kind: 'table', id: 'r1', status: 'completed', items: [{ 列: '值' }] },
            { kind: 'timeline-future', id: 'r2', status: 'running', items: ['未知项'] },
          ],
        }}
      />,
    );

    expect(document.querySelector('[data-render-kind="table"]')).not.toBeNull();
    expect(document.querySelector('[data-render-kind="timeline-future"]')).not.toBeNull();
    expect(screen.getByText('通用展示')).toBeDefined();
    // 未知 kind 的 items 走兜底列表渲染，内容不丢
    expect(screen.getByText('未知项')).toBeDefined();
  });

  it('工具草稿里的 render 也会被分派；两者同时在时不重复', () => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      generating: {
        'conversation-1': {
          hint: '',
          turnId: 'turn-1',
          assistantId: 'assistant-1',
          finished: false,
          toolDrafts: {
            'call-1': draft({
              name: 'run_python_script',
              render: { kind: 'list', id: 'draft-1', items: ['甲', '乙'] },
            }),
          },
        },
      },
    });

    render(
      <ToolResultBlocks
        metadata={{ renders: [{ kind: 'list', id: 'draft-1', items: ['甲', '乙'] }] }}
      />,
    );

    // 同一 id 的意图只渲染一次
    expect(document.querySelectorAll('[data-render-kind="list"]')).toHaveLength(1);
    expect(screen.getByText('甲')).toBeDefined();
    expect(screen.getByText('工具调用（1）')).toBeDefined();
  });

  it('既没有草稿也没有 renders 时整块不渲染（零回归）', () => {
    const { container } = render(<ToolResultBlocks metadata={{ kind: 'assistant-reply' }} />);
    expect(container.querySelector('[data-tool-result-blocks]')).toBeNull();
  });

  /**
   * 回归：直播草稿属于「发起本轮的那条消息」。不做归属判断时，同会话的每条历史消息
   * 都会挂上当前这轮的工具调用，而本轮收尾后它们又会从所有消息上一起消失。
   */
  it('直播草稿只挂在属主气泡上，其他消息回落到自己的 metadata', () => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      generating: {
        'conversation-1': {
          hint: '',
          turnId: 'turn-1',
          assistantId: 'assistant-live',
          finished: false,
          toolDrafts: { 'call-live': draft({ name: 'live_tool' }) },
        },
      },
    });

    const owner = render(<ToolResultBlocks messageId="assistant-live" />);
    expect(screen.getByText('live_tool')).toBeDefined();
    owner.unmount();

    render(
      <ToolResultBlocks
        messageId="assistant-other"
        metadata={{ toolDrafts: { 'call-own': draft({ name: 'own_tool' }) } }}
      />,
    );
    expect(screen.getByText('own_tool')).toBeDefined();
    expect(screen.queryByText('live_tool')).toBeNull();
  });
});
