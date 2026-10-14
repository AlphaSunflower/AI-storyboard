import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { AgentTaskView, AgentToolDraft } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { StandaloneTaskCards, ToolDraftList, ToolResultBlocks } from './ToolDraftList';

/**
 * 复合旅程步骤折叠的组件级验证：
 * - 带 `parentToolCallId` 的任务折到命中的工具卡下；
 * - 缺字段时渲染路径与今天逐字一致（工具卡下没有步骤块，任务卡仍独立渲染）；
 * - 未知/空白 id 既不崩溃也不产生重复卡片。
 */

function taskView(overrides: Partial<AgentTaskView> & { taskId: string }): AgentTaskView {
  return {
    conversationId: 'conversation-1',
    projectId: 'project-1',
    title: '复合旅程',
    toolName: 'produce_video_journey',
    kind: 'journey',
    status: 'running',
    stage: 'journey:image',
    progress: 25,
    resultSummary: '',
    error: '',
    parentTaskId: '',
    anchorMessageId: 'message-1',
    version: 1,
    createdAt: '2026-10-10T00:00:00Z',
    updatedAt: '2026-10-10T00:00:00Z',
    completedAt: null,
    assetIds: [],
    resultUrls: [],
    slots: [],
    result: '',
    liveText: '',
    liveThinking: '',
    liveStage: '',
    liveUpdatedAt: null,
    canCancel: true,
    canRetry: false,
    canFollowUp: false,
    ...overrides,
  };
}

const journeyDraft: AgentToolDraft = {
  name: 'produce_video_journey',
  input: '{"prompt":"雨夜街头"}',
  text: '{"ok":true,"taskId":"journey-1","taskKind":"journey"}',
  status: 'node_completed',
  seq: 1,
};

function seed(tasks: AgentTaskView[]) {
  useAgentStore.setState({
    activeConversationId: 'conversation-1',
    generating: {},
    tasks: Object.fromEntries(tasks.map((task) => [task.taskId, task])),
  });
}

afterEach(() => {
  cleanup();
  useAgentStore.setState({ activeConversationId: null, generating: {}, tasks: {}, messages: [], streaming: false });
});

describe('复合旅程步骤折叠到工具卡', () => {
  it('parentToolCallId 命中的父任务与步骤子任务折在匹配的工具卡下', () => {
    const parent = taskView({ taskId: 'journey-1', parentToolCallId: 'call-journey' });
    const child = taskView({
      taskId: 'child-1',
      title: '生成 2 张图片',
      toolName: 'generate_image_batch',
      kind: 'image-batch',
      parentTaskId: 'journey-1',
      parentToolCallId: 'call-journey',
      createdAt: '2026-10-10T00:00:01Z',
    });
    seed([parent, child]);

    const { container } = render(
      <>
        <ToolResultBlocks metadata={{ toolDrafts: { 'call-journey': journeyDraft } }} />
        <StandaloneTaskCards
          cards={[
            { taskId: 'journey-1', title: '复合旅程', kind: 'journey' },
            { taskId: 'child-1', title: '生成 2 张图片', kind: 'image-batch' },
          ]}
          metadata={{ toolDrafts: { 'call-journey': journeyDraft } }}
        />
      </>,
    );

    const row = container.querySelector('[data-tool-call-id="call-journey"]');
    expect(row).not.toBeNull();
    const nested = row!.querySelector('[data-nested-tool-tasks="call-journey"]');
    expect(nested).not.toBeNull();
    expect(nested!.querySelector('[data-task-id="journey-1"]')).not.toBeNull();
    expect(nested!.querySelector('[data-task-id="child-1"]')).not.toBeNull();
    expect(screen.getByText('步骤进度（2）')).toBeDefined();
    // 折叠后不再作为独立卡片重复渲染。
    expect(container.querySelectorAll('[data-task-id="journey-1"]')).toHaveLength(1);
    expect(container.querySelectorAll('[data-task-id="child-1"]')).toHaveLength(1);
  });

  it('缺 parentToolCallId 时渲染路径与今天一致（无步骤块 + 独立卡片）', () => {
    const plain = taskView({ taskId: 'task-1', toolName: 'start_storyboard_production', kind: 'long-tool' });
    seed([plain]);

    const { container } = render(
      <>
        <ToolResultBlocks metadata={{ toolDrafts: { 'call-plain': { ...journeyDraft, name: 'start_storyboard_production' } } }} />
        <StandaloneTaskCards
          cards={[{ taskId: 'task-1', title: '复合旅程', kind: 'long-tool' }]}
          metadata={{ toolDrafts: { 'call-plain': journeyDraft } }}
        />
      </>,
    );

    expect(container.querySelector('[data-nested-tool-tasks]')).toBeNull();
    expect(screen.queryByText(/步骤进度/)).toBeNull();
    // 旧路径：任务卡仍然作为独立卡片渲染在消息正文下方。
    expect(container.querySelectorAll('[data-task-id="task-1"]')).toHaveLength(1);
  });

  it('空白 parentToolCallId 与缺字段等价，不折叠也不重复', () => {
    const blank = taskView({ taskId: 'task-blank', parentToolCallId: '' });
    const spaces = taskView({ taskId: 'task-spaces', parentToolCallId: '   ' });
    seed([blank, spaces]);

    const { container } = render(
      <>
        <ToolResultBlocks metadata={{ toolDrafts: { 'call-1': journeyDraft } }} />
        <StandaloneTaskCards
          cards={[
            { taskId: 'task-blank', title: '空白', kind: 'journey' },
            { taskId: 'task-spaces', title: '空格', kind: 'journey' },
          ]}
          metadata={{ toolDrafts: { 'call-1': journeyDraft } }}
        />
      </>,
    );

    expect(container.querySelector('[data-nested-tool-tasks]')).toBeNull();
    expect(container.querySelectorAll('[data-task-id="task-blank"]')).toHaveLength(1);
    expect(container.querySelectorAll('[data-task-id="task-spaces"]')).toHaveLength(1);
  });

  it('未命中任何工具卡的 id 不折叠、不崩溃、也不丢卡片', () => {
    const unknown = taskView({ taskId: 'task-unknown', parentToolCallId: 'call-missing' });
    seed([unknown]);

    const { container } = render(
      <>
        <ToolDraftList drafts={{ 'call-present': journeyDraft }} />
        <StandaloneTaskCards
          cards={[{ taskId: 'task-unknown', title: '未知归属', kind: 'journey' }]}
          metadata={{ toolDrafts: { 'call-present': journeyDraft } }}
        />
      </>,
    );

    expect(container.querySelector('[data-tool-call-id="call-present"]')).not.toBeNull();
    expect(container.querySelector('[data-nested-tool-tasks]')).toBeNull();
    // 没有对应工具卡时任务卡必须留在独立渲染路径上，且只有一张。
    expect(container.querySelectorAll('[data-task-id="task-unknown"]')).toHaveLength(1);
  });

  it('两张工具卡只有命中的那张折叠步骤', () => {
    const child = taskView({ taskId: 'child-1', parentToolCallId: 'call-b', kind: 'image-batch' });
    seed([child]);

    const { container } = render(
      <ToolDraftList drafts={{ 'call-a': journeyDraft, 'call-b': { ...journeyDraft, seq: 2 } }} />,
    );

    expect(container.querySelector('[data-tool-call-id="call-a"] [data-nested-tool-tasks]')).toBeNull();
    expect(container.querySelector('[data-tool-call-id="call-b"] [data-nested-tool-tasks="call-b"]')).not.toBeNull();
    expect(container.querySelectorAll('[data-task-id="child-1"]')).toHaveLength(1);
  });
});
