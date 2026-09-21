import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { libraryApi } from '../../api/library';
import { useProjectStore } from '../../stores/projectStore';
import { CHAT_MEDIA_DRAG_MIME } from '../../lib/projectLibraryDrag';
import { MessageBubble } from './MessageBubble';

describe('MessageBubble project library import', () => {
  beforeEach(() => {
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    useProjectStore.setState({
      currentProject: { id: 'project-1', name: '测试项目' } as never,
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('imports generated images from the message action row into the current project', async () => {
    const register = vi.spyOn(libraryApi, 'registerGeneratedOutput').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: {
          id: 'asset-1',
          ownerUserId: 'user-1',
          scope: 'PROJECT',
          ownerProjectId: 'project-1',
          type: 'IMAGE',
          name: '生成图片',
          description: null,
          aiDescription: null,
          manualOverride: false,
          metadataState: 'AI_GENERATING',
          status: 'ACTIVE',
          coverUrl: '/api/files/images/generated.png',
          version: 1,
          tags: [],
          projects: [],
          folderId: null,
          folderPath: [],
          sourceType: 'generation',
          sourceId: 'record-1',
          createdAt: '',
          updatedAt: '',
        },
      },
    } as never);

    render(
      <MessageBubble
        role="assistant"
        content="![生成图片](/api/files/images/generated.png)"
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '导入素材库' }));

    await waitFor(() => {
      expect(register).toHaveBeenCalledWith(expect.objectContaining({
        projectId: 'project-1',
        producer: 'moon-chat',
        resultUrl: '/api/files/images/generated.png',
        mediaType: 'IMAGE',
      }));
    });
  });

  it('exposes message images as chat media drag payloads', () => {
    render(
      <MessageBubble
        role="assistant"
        content="![生成图片](/api/files/images/generated.png)"
      />,
    );

    const setData = vi.fn();
    fireEvent.dragStart(screen.getByRole('img'), {
      dataTransfer: { setData, effectAllowed: '' },
    });

    expect(setData).toHaveBeenCalledWith(
      CHAT_MEDIA_DRAG_MIME,
      JSON.stringify({
        projectId: 'project-1',
        mediaType: 'IMAGE',
        url: '/api/files/images/generated.png',
        name: '生成图片',
      }),
    );
  });
});
