import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { agentApi } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import {
  PROJECT_LIBRARY_DRAG_MIME,
  serializeDragPayload,
} from '../../lib/projectLibraryDrag';
import { ChatComposer } from './ChatComposer';

describe('ChatComposer project library drops', () => {
  beforeEach(() => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      refImageUrls: [],
      pickedReferenceVideos: [],
      pickedReferenceAudios: [],
    });
    vi.spyOn(agentApi, 'listConversationAssetGroups').mockResolvedValue({
      data: { code: 0, message: '', data: [] },
    } as never);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    useAgentStore.getState().newConversation();
  });

  it('adds a dropped project library image to the composer references', async () => {
    render(<ChatComposer />);

    const payload = serializeDragPayload({
      kind: 'asset',
      assetId: 'asset-1',
      mediaType: 'IMAGE',
      url: '/api/files/images/reference.png',
      name: '参考图',
    });
    fireEvent.drop(document.querySelector('.chat-composer-card')!, {
      dataTransfer: {
        types: [PROJECT_LIBRARY_DRAG_MIME],
        getData: (type: string) => type === PROJECT_LIBRARY_DRAG_MIME ? payload : '',
        files: [],
      },
    });

    await waitFor(() => {
      expect(useAgentStore.getState().refImageUrls).toContain('/api/files/images/reference.png');
    });
  });

  it('attaches a dropped project library group to the conversation', async () => {
    const attach = vi.spyOn(agentApi, 'attachConversationAssetGroup').mockResolvedValue({
      data: { code: 0, message: '', data: null },
    } as never);
    render(<ChatComposer />);

    const payload = serializeDragPayload({
      kind: 'group',
      groupId: 'group-1',
      name: '主角集',
    });
    fireEvent.drop(document.querySelector('.chat-composer-card')!, {
      dataTransfer: {
        types: [PROJECT_LIBRARY_DRAG_MIME],
        getData: (type: string) => type === PROJECT_LIBRARY_DRAG_MIME ? payload : '',
        files: [],
      },
    });

    await waitFor(() => {
      expect(attach).toHaveBeenCalledWith('conversation-1', 'group-1');
    });
  });

  it('offers interrupt-and-send while the agent is streaming', () => {
    useAgentStore.setState({ streaming: true });
    render(<ChatComposer />);

    const input = screen.getByPlaceholderText('可输入新指令打断当前生成…');
    fireEvent.change(input, { target: { value: '改成新的方向' } });

    expect(screen.getByRole('button', { name: '停止并发送' })).toBeTruthy();
  });
});
