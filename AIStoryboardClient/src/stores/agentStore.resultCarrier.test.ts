import { beforeEach, describe, expect, it } from 'vitest';
import {
  attachResultToMessage,
  confirmResultFromToolResult,
  useAgentStore,
} from './agentStore';
import type { AgentMessage, AgentTaskView, ConfirmResultInfo } from '../api/agent';

const IMAGE_RESULT: ConfirmResultInfo = {
  kind: 'image',
  url: '/api/files/images/a.png',
  urls: ['/api/files/images/a.png', '/api/files/images/b.png'],
  assetIds: ['asset-a', 'asset-b'],
  sceneCount: 0,
  actions: [{ id: 'refine', title: '继续完善' }, { id: 'done', title: '满意完成' }],
};

function message(overrides: Partial<AgentMessage> = {}): AgentMessage {
  return {
    id: 'assistant-1',
    conversationId: 'conversation-1',
    role: 'assistant',
    content: '已生成 2 张图片',
    createdAt: '2026-10-14T08:00:00Z',
    ...overrides,
  };
}

function mediaTask(kind: string): AgentTaskView {
  return {
    taskId: 'task-1',
    conversationId: 'conversation-1',
    kind,
    slots: kind === 'image-batch'
      ? [{ index: 0, label: '第 1 张', status: 'completed', prompt: '', url: '/api/files/images/a.png', assetId: 'asset-a', error: '' }]
      : undefined,
  } as unknown as AgentTaskView;
}

beforeEach(() => {
  useAgentStore.setState({
    activeConversationId: 'conversation-1',
    messages: [message()],
    tasks: {},
    pendingPicUrls: [],
  });
});

describe('attachResultToMessage — 结果只能有一个载体', () => {
  it('消息上已有媒体任务卡时不挂旧确认动作，也不重复补附件（位条就是载体）', () => {
    useAgentStore.setState({
      messages: [message({ metadata: { taskCards: [{ taskId: 'task-1', title: '批量生成图片', kind: 'image-batch' }] } })],
      tasks: { 'task-1': mediaTask('image-batch') },
    });

    attachResultToMessage('assistant-1', IMAGE_RESULT);

    const stored = useAgentStore.getState().messages[0];
    // 这就是"新旧两套二次确认并存"的修复点：位条已经展示结果，旧确认卡不再出现
    expect(stored.metadata?.confirmResult).toBeUndefined();
    expect(stored.metadata?.sandboxAttachments ?? []).toHaveLength(0);
  });

  it('没有任何其他载体时把结果图补成消息附件，并挂上确认动作', () => {
    attachResultToMessage('assistant-1', IMAGE_RESULT);

    const stored = useAgentStore.getState().messages[0];
    expect(stored.metadata?.confirmResult?.kind).toBe('image');
    // 关键：图进了消息本身，而不是只存在于那张会被收起的卡片里
    expect((stored.metadata?.sandboxAttachments ?? []).map((a) => a.url))
      .toEqual(['/api/files/images/a.png', '/api/files/images/b.png']);
    expect(stored.metadata?.sandboxAttachments?.[0].kind).toBe('image');
    expect(stored.metadata?.sandboxAttachments?.[0].assetId).toBe('asset-a');
  });

  it('重复落同一结果不会产生重复附件', () => {
    attachResultToMessage('assistant-1', IMAGE_RESULT);
    attachResultToMessage('assistant-1', IMAGE_RESULT);

    expect(useAgentStore.getState().messages[0].metadata?.sandboxAttachments).toHaveLength(2);
  });

  it('消息不存在或结果为空时不动 store', () => {
    attachResultToMessage('missing-message', IMAGE_RESULT);
    attachResultToMessage('assistant-1', null);

    expect(useAgentStore.getState().messages[0].metadata?.confirmResult).toBeUndefined();
  });
});

describe('结果确认动作只认媒体生成工具', () => {
  const mediaPayload = JSON.stringify({ url: '/api/files/images/a.png', assetId: 'asset-a' });

  it('request_human_input 的 url 不能被当成生成结果', () => {
    // 回归：HITL「请求用户补充信息」卡片下面曾凭空出现「继续完善 / 满意完成」。
    expect(confirmResultFromToolResult('request_human_input', mediaPayload)).toBeNull();
  });

  it('媒体生成工具的 url 正常产生确认动作', () => {
    expect(confirmResultFromToolResult('generate_image', mediaPayload)?.kind).toBe('image');
    expect(confirmResultFromToolResult(
      'generate_video',
      JSON.stringify({ url: '/api/files/videos/a.mp4', mediaType: 'video' }),
    )?.kind).toBe('video');
  });
});

describe('收起确认动作不会带走结果图', () => {
  it('满意完成只清掉动作，sandboxAttachments 原样保留', () => {
    attachResultToMessage('assistant-1', IMAGE_RESULT);
    expect(useAgentStore.getState().messages[0].metadata?.confirmResult).toBeDefined();

    useAgentStore.getState().clearMessageConfirmResult('assistant-1');

    const stored = useAgentStore.getState().messages[0];
    expect(stored.metadata?.confirmResult).toBeUndefined();
    // 旧实现在这里会把图片一起弄没（图只画在那张全局卡片里）
    expect(stored.metadata?.sandboxAttachments).toHaveLength(2);
  });

  it('继续完善暂存参考图并收起动作，结果图仍在消息里', () => {
    attachResultToMessage('assistant-1', IMAGE_RESULT);

    useAgentStore.getState().refineAsset(IMAGE_RESULT, 'assistant-1');

    expect(useAgentStore.getState().pendingPicUrls).toHaveLength(1);
    expect(useAgentStore.getState().pendingPicUrls[0]).toContain('/api/files/images/a.png');
    const stored = useAgentStore.getState().messages[0];
    expect(stored.metadata?.confirmResult).toBeUndefined();
    expect(stored.metadata?.sandboxAttachments).toHaveLength(2);
  });

  it('清空动作只影响目标消息', () => {
    useAgentStore.setState({
      messages: [
        message({ id: 'assistant-1', metadata: { confirmResult: IMAGE_RESULT } }),
        message({ id: 'assistant-2', metadata: { confirmResult: IMAGE_RESULT } }),
      ],
    });

    useAgentStore.getState().clearMessageConfirmResult('assistant-1');

    const [first, second] = useAgentStore.getState().messages;
    expect(first.metadata?.confirmResult).toBeUndefined();
    expect(second.metadata?.confirmResult).toBeDefined();
  });
});
