import { render } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { applyAgentTurnEnvelope, useAgentStore } from '../../stores/agentStore';
import { ToolResultBlocks } from './ToolDraftList';
import type { AgentMessageMetadata, AgentToolDraft, AgentTurnEventEnvelope } from '../../api/agent';

/**
 * 工具明细必须在「直播 / 刷新 / afterSeq 重放」三条路径上渲染成同一份东西。
 *
 * 三条路径的载体各不相同，这正是此前工具明细会丢的地方：
 * - 直播：`generating.toolDrafts`（前端从原生 `agent_event` 派生，**带** `seq`）；
 * - afterSeq 重放：把持久化的 turn 事件再喂一遍 reducer，落回 `generating.toolDrafts`；
 * - 刷新：服务端落库的 `metadata.toolDrafts`（`AgentTraceAccumulator` 产出，**不带** `seq`）。
 */
const CONVERSATION = 'conversation-1';
const ASSISTANT = 'assistant-1';
const CONTEXT = { conversationId: CONVERSATION, assistantId: ASSISTANT };
let turnCounter = 0;

/** 原生工具事件：与 AgentScope 的 ToolCall / ToolResult 事件一一对应。 */
const TOOL_EVENTS: Array<[string, Record<string, unknown>]> = [
  ['TOOL_CALL_START', { toolName: 'generate_image', toolCallId: 'call-1' }],
  ['TOOL_CALL_DELTA', { toolName: 'generate_image', toolCallId: 'call-1', delta: '{"prompt":"cat"}' }],
  ['TOOL_RESULT_START', { toolName: 'generate_image', toolCallId: 'call-1' }],
  ['TOOL_RESULT_TEXT_DELTA', { toolName: 'generate_image', toolCallId: 'call-1', delta: '{"ok":' }],
  ['TOOL_RESULT_TEXT_DELTA', { toolName: 'generate_image', toolCallId: 'call-1', delta: 'true}' }],
  ['TOOL_RESULT_END', { toolName: 'generate_image', toolCallId: 'call-1', state: 'SUCCESS' }],
  ['TOOL_CALL_START', { toolName: 'run_python_script', toolCallId: 'call-2' }],
  ['TOOL_RESULT_END', { toolName: 'run_python_script', toolCallId: 'call-2', state: 'ERROR' }],
];

function envelope(turnId: string, seq: number, eventType: string,
                  data: Record<string, unknown>): AgentTurnEventEnvelope {
  return { turnId, seq, type: 'agent_event', payload: { eventType, data } };
}

/**
 * 服务端 `AgentTraceAccumulator` 实际落库的形状（见
 * `AgentTraceAccumulatorToolDraftsTest`）：无 `seq`，靠对象插入顺序保序。
 */
const SERVER_DRAFTS: Record<string, AgentToolDraft> = {
  'call-1': {
    name: 'generate_image',
    text: '{"ok":true}',
    input: '{"prompt":"cat"}',
    status: 'node_completed',
    startedAt: 1_760_000_000_000,
  },
  'call-2': {
    name: 'run_python_script',
    text: '',
    input: '',
    status: 'node_failed',
    startedAt: 1_760_000_000_001,
  },
};

/** 每行的「状态 + 可见文本」，覆盖工具名、状态标签与折叠输出预览。 */
function renderedRows(container: HTMLElement) {
  return Array.from(container.querySelectorAll('[data-tool-status]')).map((row) => ({
    status: row.getAttribute('data-tool-status'),
    text: (row.textContent ?? '').replace(/\s+/g, ' ').trim(),
  }));
}

function baseState(overrides: Record<string, unknown> = {}) {
  useAgentStore.setState({
    activeConversationId: CONVERSATION,
    messages: [{
      id: ASSISTANT,
      conversationId: CONVERSATION,
      role: 'assistant',
      content: '',
      createdAt: '2026-10-14T08:00:00Z',
    }],
    generating: { [CONVERSATION]: { hint: '', assistantId: ASSISTANT } },
    tasks: {},
    ...overrides,
  });
}

/**
 * 直播/重放：把原生工具事件喂给 reducer，读 `generating.toolDrafts`。
 *
 * 每次必须换一个 turnId：`applyAgentTurnEnvelope` 里的 sequencer 是模块级单例，
 * 且 `accept` 会丢弃 `seq <= last`，同一个 turnId 再喂一遍会被整体忽略（假绿/假红都容易踩）。
 */
function reduceToolEvents() {
  const turnId = `turn-parity-${++turnCounter}`;
  useAgentStore.setState((state) => ({
    generating: {
      ...state.generating,
      [CONVERSATION]: { hint: '', turnId, assistantId: ASSISTANT },
    },
  }));
  TOOL_EVENTS.forEach(([eventType, data], index) => {
    applyAgentTurnEnvelope(envelope(turnId, index + 1, eventType, data), CONTEXT);
  });
  return useAgentStore.getState().generating[CONVERSATION]?.toolDrafts;
}

beforeEach(() => {
  baseState();
});

describe('工具明细三路一致性', () => {
  it('直播路径（带 seq）渲染出两个工具、含失败态', () => {
    const drafts = reduceToolEvents();
    expect(Object.keys(drafts ?? {})).toEqual(['call-1', 'call-2']);

    const { container } = render(<ToolResultBlocks metadata={{ toolDrafts: drafts }} />);
    const rows = renderedRows(container);

    expect(rows.map((row) => row.status)).toEqual(['node_completed', 'node_failed']);
    expect(rows[0].text).toContain('generate_image');
    expect(rows[0].text).toContain('已完成');
    expect(rows[0].text).toContain('{"ok":true}');
    expect(rows[1].text).toContain('run_python_script');
    // ERROR 必须显示"失败"，不能因为只判 FAIL/DENIED 而变成"已完成"
    expect(rows[1].text).toContain('失败');
  });

  it('afterSeq 重放（同一批事件再喂一遍）与直播渲染完全一致', () => {
    const live = render(<ToolResultBlocks metadata={{ toolDrafts: reduceToolEvents() }} />);
    const liveRows = renderedRows(live.container);
    live.unmount();

    baseState();
    const replayedRows = renderedRows(
      render(<ToolResultBlocks metadata={{ toolDrafts: reduceToolEvents() }} />).container);

    expect(replayedRows).toEqual(liveRows);
  });

  it('刷新路径（服务端 metadata，无 seq）与直播渲染完全一致', () => {
    const live = render(<ToolResultBlocks metadata={{ toolDrafts: reduceToolEvents() }} />);
    const liveRows = renderedRows(live.container);
    live.unmount();

    // 刷新：没有直播态，只有服务端落库的 metadata
    const metadata: AgentMessageMetadata = { toolDrafts: SERVER_DRAFTS };
    baseState({ generating: {} });
    const refreshedRows = renderedRows(render(<ToolResultBlocks metadata={metadata} />).container);

    expect(refreshedRows).toEqual(liveRows);
  });
});
