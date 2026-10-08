import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import client from './client';
import { agentApi, parseAgentTurnEnvelope, streamChat } from './agent';
import { setAccessToken } from '../lib/authSession';

describe('streamChat', () => {
  beforeEach(() => {
    setAccessToken('test-token');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('sends group and snapshot ids as separate fields', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('', { status: 200 }),
    );

    await streamChat(
      'conversation-1',
      '生成视频',
      [],
      () => undefined,
      [],
      ['group-1'],
      undefined,
      { assetGroupSnapshotIds: ['snapshot-1'] },
    );

    const body = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body));
    expect(body.assetGroupIds).toEqual(['group-1']);
    expect(body.assetGroupSnapshotIds).toEqual(['snapshot-1']);
  });

  it('sends editMessageId through the turn stream endpoint', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('', { status: 200 }),
    );

    await streamChat(
      'conversation-1',
      '修改后的问题',
      [],
      () => undefined,
      [],
      [],
      undefined,
      { editMessageId: 'message-1' },
    );

    expect(fetchMock.mock.calls[0]?.[0]).toContain('/conversations/conversation-1/turns/stream');
    const body = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body));
    expect(body.editMessageId).toBe('message-1');
  });

  it('parses only valid sequenced AgentScope envelopes', () => {
    expect(parseAgentTurnEnvelope({
      turnId: 'turn-1',
      seq: 3,
      type: 'agent_event',
      payload: { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'hello' } },
    })).toEqual({
      turnId: 'turn-1',
      seq: 3,
      type: 'agent_event',
      payload: { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'hello' } },
    });
    expect(parseAgentTurnEnvelope({ turnId: 'turn-1', seq: 0, type: 'agent_event', payload: {} })).toBeNull();
    expect(parseAgentTurnEnvelope({ turnId: 'turn-1', seq: 1, type: 'message', payload: {} })).toBeNull();
    expect(parseAgentTurnEnvelope({
      turnId: 'turn-1',
      seq: 4,
      type: 'media_critique',
      payload: { decision: 'REPAIR' },
    })?.type).toBe('media_critique');
    expect(parseAgentTurnEnvelope({
      turnId: 'turn-1',
      seq: 5,
      type: 'canvas_run_started',
      payload: { canvasRunId: 'canvas-run-1' },
    })?.type).toBe('canvas_run_started');
    expect(parseAgentTurnEnvelope({
      turnId: 'turn-1',
      seq: 6,
      type: 'canvas_run_updated',
      payload: { canvasRunId: 'canvas-run-1', revision: 2 },
    })?.type).toBe('canvas_run_updated');
  });

  it('delivers SSE envelopes without mapping legacy event names', async () => {
    const envelope = {
      turnId: 'turn-1',
      seq: 1,
      type: 'agent_event',
      payload: { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'hello' } },
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response([
      'event: agent_event',
      `data: ${JSON.stringify(envelope)}`,
      '',
      '',
    ].join('\n'), { status: 200 }));
    const received: unknown[] = [];

    await streamChat('conversation-1', 'hello', [], (event) => received.push(event));

    expect(received).toEqual([envelope]);
  });

  it('loads replay events after a sequence', async () => {
    const get = vi.spyOn(client, 'get').mockResolvedValue({ data: { data: [] } });

    await agentApi.listTurnEvents('conversation-1', 'turn-1', 7);

    expect(get).toHaveBeenCalledWith(
      '/agent/conversations/conversation-1/turns/turn-1/events',
      { params: { afterSeq: 7 } },
    );
  });

  it('omits orchestrationMode when creating a conversation without an explicit choice', async () => {
    const post = vi.spyOn(client, 'post').mockResolvedValue({ data: { data: {} } });

    await agentApi.createConversation('project-1', '新对话');

    expect(post).toHaveBeenCalledWith('/agent/conversations', {
      projectId: 'project-1',
      title: '新对话',
    });
  });

  it('sends approvalMode when creating a conversation with an explicit policy', async () => {
    const post = vi.spyOn(client, 'post').mockResolvedValue({ data: { data: {} } });

    await agentApi.createConversation('project-1', '新对话', 'FULL_ACCESS');

    expect(post).toHaveBeenCalledWith('/agent/conversations', {
      projectId: 'project-1',
      title: '新对话',
      approvalMode: 'FULL_ACCESS',
    });
  });

  it('updates approval mode through the dedicated endpoint', async () => {
    const patch = vi.spyOn(client, 'patch').mockResolvedValue({ data: { data: {} } });

    await agentApi.updateApprovalMode('conversation-1', 'REQUEST_APPROVAL');

    expect(patch).toHaveBeenCalledWith(
      '/agent/conversations/conversation-1/approval-mode',
      { approvalMode: 'REQUEST_APPROVAL' },
    );
  });

  it('loads generic project creative context and run critiques', async () => {
    const get = vi.spyOn(client, 'get').mockResolvedValue({ data: { data: {} } });

    await agentApi.getCreativeContext('project-1');
    await agentApi.getRunCritiques('turn-1');

    expect(get).toHaveBeenNthCalledWith(
      1,
      '/agent/projects/project-1/creative-context',
    );
    expect(get).toHaveBeenNthCalledWith(
      2,
      '/agent/runs/turn-1/critique',
    );
  });
});
