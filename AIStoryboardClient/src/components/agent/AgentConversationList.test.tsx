import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentConversationList } from './AgentConversationList';
import { useAgentStore } from '../../stores/agentStore';
import { useToastStore } from '../../stores/toastStore';
import { agentApi } from '../../api/agent';
import { CONVERSATION_DRAG_MIME } from '../../lib/mentionReferences';

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

  it('把非当前会话拖进输入框时写入 conversation 拖拽负载', () => {
    render(<AgentConversationList />);
    const item = screen.getByText('对话二').closest('.conversation-item');
    expect(item).toBeTruthy();
    const setData = vi.fn();
    fireEvent.dragStart(item!, {
      dataTransfer: { setData, effectAllowed: '' },
    });

    expect(setData).toHaveBeenCalledWith(
      CONVERSATION_DRAG_MIME,
      expect.stringContaining('"id":"c2"'),
    );
    expect(setData).toHaveBeenCalledWith('text/plain', '@对话《对话二》');
  });

  it('非当前会话在悬浮时显示底色，移出后恢复', () => {
    render(<AgentConversationList />);
    const item = screen.getByText('对话二').closest('.conversation-item') as HTMLElement;
    expect(item.style.background).toBe('transparent');

    fireEvent.mouseEnter(item);
    expect(item.style.background).toBe('var(--color-surface-subtle)');

    fireEvent.mouseLeave(item);
    expect(item.style.background).toBe('transparent');
  });
});

describe('AgentConversationList 操作通知', () => {
  beforeEach(() => {
    useAgentStore.setState({
      conversations: [conversation('c1', '对话一'), conversation('c2', '对话二')],
      activeConversationId: 'c1',
      generating: {},
      replyUnread: {},
      taskUnread: {},
    });
    useToastStore.setState({ toasts: [] });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    useToastStore.setState({ toasts: [] });
    useAgentStore.setState({ conversations: [], generating: {}, replyUnread: {}, taskUnread: {} });
  });

  /** 底部确认弹窗与底部多选栏都叫「删除」，取最后一个（后渲染的弹窗） */
  const clickLastDeleteButton = () => {
    const buttons = screen.getAllByRole('button')
      .filter((button) => button.textContent?.trim() === '删除');
    fireEvent.click(buttons[buttons.length - 1]);
  };

  it('confirms a conversation rename', async () => {
    vi.spyOn(agentApi, 'updateConversation')
      .mockResolvedValue({ data: { data: conversation('c2', '新标题') } } as never);
    render(<AgentConversationList />);

    fireEvent.click(screen.getAllByLabelText('更多操作')[1]);
    fireEvent.click(screen.getByText('重命名'));
    const input = screen.getByDisplayValue('对话二');
    fireEvent.change(input, { target: { value: '新标题' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(1));
    expect(useToastStore.getState().toasts[0].title).toBe('已重命名');
    expect(useToastStore.getState().toasts[0].description).toBe('「新标题」');
  });

  it('confirms archiving a conversation', async () => {
    vi.spyOn(agentApi, 'updateConversation')
      .mockResolvedValue({
        data: {
          data: {
            id: 'c2',
            title: '对话二',
            status: 'archived',
            createdAt: '2026-10-09T10:00:00.000Z',
            updatedAt: new Date().toISOString(),
          },
        },
      } as never);
    render(<AgentConversationList />);

    fireEvent.click(screen.getAllByLabelText('更多操作')[1]);
    fireEvent.click(screen.getByText('归档'));

    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(1));
    expect(useToastStore.getState().toasts[0].title).toBe('已归档');
    expect(useToastStore.getState().toasts[0].description).toBe('「对话二」');
  });

  it('reports archive failures through the same store action', async () => {
    vi.spyOn(agentApi, 'updateConversation').mockRejectedValue(new Error('网络异常'));

    await expect(useAgentStore.getState().setConversationStatus('c2', 'archived')).rejects.toThrow('网络异常');
    expect(useToastStore.getState().toasts[0]).toMatchObject({
      title: '归档失败',
      description: '网络异常',
      variant: 'error',
    });
  });

  it('confirms deleting a single conversation', async () => {
    vi.spyOn(agentApi, 'deleteConversation').mockResolvedValue({} as never);
    render(<AgentConversationList />);

    fireEvent.click(screen.getAllByLabelText('更多操作')[1]);
    fireEvent.click(screen.getByText('删除'));
    clickLastDeleteButton();

    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(1));
    expect(useToastStore.getState().toasts[0].title).toBe('已删除对话');
    expect(useToastStore.getState().toasts[0].description).toBe('「对话二」');
  });

  it('summarises a batch delete as one notice', async () => {
    const remove = vi.spyOn(agentApi, 'deleteConversation').mockResolvedValue({} as never);
    render(<AgentConversationList />);

    fireEvent.click(screen.getByLabelText('多选删除'));
    fireEvent.click(screen.getByText('对话一'));
    fireEvent.click(screen.getByText('对话二'));
    clickLastDeleteButton();

    await waitFor(() => expect(remove).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(1));
    expect(useToastStore.getState().toasts[0].title).toBe('已删除 2 个对话');
  });
});
