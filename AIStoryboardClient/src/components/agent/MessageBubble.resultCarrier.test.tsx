import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { agentApi } from '../../api/agent';
import { useAgentStore, attachResultToMessage } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import type { AgentAttachment, AgentMessage, AgentTaskView, ConfirmResultInfo } from '../../api/agent';
import { MessageBubble } from './MessageBubble';

const ATTACHMENT: AgentAttachment = {
  url: '/api/files/images/a.png',
  name: '生成图片 1',
  mimeType: 'image/png',
  kind: 'image',
  assetId: 'asset-a',
};

const CONFIRM: ConfirmResultInfo = {
  kind: 'image',
  url: '/api/files/images/a.png',
  urls: ['/api/files/images/a.png'],
  sceneCount: 0,
  actions: [{ id: 'refine', title: '继续完善' }, { id: 'done', title: '满意完成' }],
};

function assistantMessage(overrides: Partial<AgentMessage> = {}): AgentMessage {
  return {
    id: 'assistant-1',
    conversationId: 'conversation-1',
    role: 'assistant',
    content: '已生成 1 张图片',
    createdAt: '2026-10-14T08:00:00Z',
    ...overrides,
  };
}

/** 让气泡由 store 驱动：这样"收起确认动作"这类写回 store 的行为在断言里可见。 */
function Harness() {
  const message = useAgentStore((state) => state.messages[0]);
  if (!message) return null;
  return (
    <MessageBubble
      role="assistant"
      messageId={message.id}
      content={message.content}
      metadata={message.metadata}
    />
  );
}

beforeEach(() => {
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
    matches: false,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
  useProjectStore.setState({ currentProject: null });
  useAgentStore.setState({
    activeConversationId: 'conversation-1',
    messages: [assistantMessage()],
    tasks: {},
    streaming: false,
    pendingPicUrls: [],
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('结果图与确认动作在消息里共存', () => {
  it('结果图和「继续完善/满意完成」同时渲染，点满意完成后结果图仍在', async () => {
    vi.spyOn(agentApi, 'confirmDone').mockResolvedValue({ data: { code: 0, message: '', data: null } } as never);
    attachResultToMessage('assistant-1', CONFIRM);
    useAgentStore.setState({
      messages: useAgentStore.getState().messages.map((message) => ({
        ...message,
        metadata: { ...message.metadata, sandboxAttachments: [ATTACHMENT] },
      })),
    });

    const { container } = render(<Harness />);
    expect(container.querySelector('.chat-message-attachments')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '满意完成' }));

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: '满意完成' })).toBeNull();
    });
    // 旧行为：图只画在那张被收起的全局卡片里，点一下就全没了
    expect(container.querySelector('.chat-message-attachments')).not.toBeNull();
  });

  it('位条已承载结果的消息不渲染旧确认动作', () => {
    const task = {
      taskId: 'task-1',
      conversationId: 'conversation-1',
      kind: 'image-batch',
      status: 'completed',
      progress: 100,
      assetIds: [],
      resultUrls: ['/api/files/images/a.png'],
      slots: [{
        index: 0, label: '第 1 张', status: 'completed', prompt: '',
        url: '/api/files/images/a.png', assetId: 'asset-a', error: '',
      }],
    } as unknown as AgentTaskView;
    useAgentStore.setState({
      messages: [assistantMessage({
        metadata: { taskCards: [{ taskId: 'task-1', title: '批量生成图片', kind: 'image-batch' }] },
      })],
      tasks: { 'task-1': task },
    });

    const { container } = render(<Harness />);

    expect(container.querySelector('.gen-slot-strip')).not.toBeNull();
    expect(screen.queryByRole('button', { name: '满意完成' })).toBeNull();
  });
});

describe('卡片分支不再吞掉产物', () => {
  it('video-plan 卡片消息仍渲染结果图与产物文件卡', () => {
    useAgentStore.setState({
      messages: [assistantMessage({
        content: '视频方案',
        metadata: {
          kind: 'video-plan',
          videoPlan: {
            message: '视频方案',
            duration: 8,
            actions: [{ id: 'generate_video', title: '开始生成视频' }],
          },
          sandboxAttachments: [ATTACHMENT],
        },
      })],
    });

    const { container } = render(<Harness />);

    expect(container.querySelector('.chat-message-attachments')).not.toBeNull();
  });

  it('已提交的 HITL 卡片消息仍渲染产物文件卡', () => {
    useAgentStore.setState({
      messages: [assistantMessage({
        content: '执行方案待确认',
        metadata: {
          hitl: {
            formContent: '执行方案待确认',
            actions: [{ id: 'agree', title: '同意' }],
            selectedActionId: 'agree',
            selectedActionTitle: '同意',
          },
          sandboxAttachments: [ATTACHMENT],
        },
      })],
    });

    const { container } = render(<Harness />);

    expect(container.querySelector('.chat-message-attachments')).not.toBeNull();
  });
});
