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

  it('renders exact token and duration metrics for completed assistant messages', () => {
    render(
      <MessageBubble
        role="assistant"
        content="回复完成"
        metadata={{
          kind: 'assistant-reply',
          metrics: {
            inputTokens: 1000,
            outputTokens: 234,
            cachedInputTokens: 120,
            totalTokens: 1234,
            durationMs: 8420,
            modelCalls: 2,
            complete: true,
          },
        }}
      />,
    );

    expect(screen.getByText('1,234 tokens')).toBeTruthy();
    expect(screen.getByText('8.4s').parentElement?.getAttribute('title')).toContain(
      '输入 1,000 · 输出 234 · 缓存 120',
    );
  });

  it('marks partial usage as a lower bound', () => {
    render(
      <MessageBubble
        role="assistant"
        content="部分指标"
        metadata={{
          kind: 'assistant-reply',
          metrics: {
            inputTokens: 90,
            outputTokens: 10,
            cachedInputTokens: 0,
            totalTokens: 100,
            durationMs: 600,
            modelCalls: 2,
            complete: false,
          },
        }}
      />,
    );

    expect(screen.getByText('≥ 100 tokens').parentElement?.getAttribute('title'))
      .toContain('部分模型未返回用量');
  });

  it('renders duration without token text when usage is unavailable', () => {
    render(
      <MessageBubble
        role="assistant"
        content="仅耗时"
        metadata={{
          kind: 'assistant-reply',
          metrics: {
            inputTokens: 0,
            outputTokens: 0,
            cachedInputTokens: 0,
            totalTokens: 0,
            durationMs: 350,
            modelCalls: 0,
            complete: false,
          },
        }}
      />,
    );

    expect(screen.queryByText(/tokens/)).toBeNull();
    expect(screen.getByText('350ms')).toBeTruthy();
  });

  it('renders markdown tables with dedicated chat styling', () => {
    render(
      <MessageBubble
        role="assistant"
        content={'| 项目 | 设计 |\n| --- | --- |\n| 风格 | 现代轻奢 |'}
      />,
    );

    const table = screen.getByRole('table');
    expect(table.closest('.chat-markdown-table-wrap')).toBeTruthy();
  });

  it('repairs strong markers adjacent to Chinese text', () => {
    render(
      <MessageBubble
        role="assistant"
        content="如果你对**风格（如改成新中式）、场景（海边 or 山谷）**有不同想法"
      />,
    );

    expect(screen.getByText('风格（如改成新中式）、场景（海边 or 山谷）').tagName).toBe('STRONG');
  });

  it('collapses four or more generated images into one batch receipt', () => {
    const openGenerationCenter = vi.fn();
    const images = Array.from(
      { length: 6 },
      (_, index) => `![分镜 ${index + 1}](/api/files/images/scene-${index + 1}.png)`,
    ).join('\n');
    const { container } = render(
      <MessageBubble
        role="assistant"
        content={`已补齐缺图分镜。\n已生成 6 张图片\n${images}\n//# (async-task:batch-1:end)`}
        onOpenGenerationCenter={openGenerationCenter}
      />,
    );

    expect(screen.getByText('本批图片已生成')).toBeTruthy();
    expect(screen.getByText('共 6 张，集中到生成中心查看')).toBeTruthy();
    expect(screen.queryByText('已生成 6 张图片')).toBeNull();
    expect(screen.queryByText(/async-task/)).toBeNull();
    expect(container.querySelectorAll('.chat-image-batch__cover img')).toHaveLength(4);
    expect(container.querySelector('.chat-image-batch__more')?.textContent).toBe('+2');

    fireEvent.click(screen.getByRole('button', { name: '查看全部 6 张生成图片' }));
    expect(openGenerationCenter).toHaveBeenCalledTimes(1);
  });

  it('keeps small image groups inline', () => {
    const images = Array.from(
      { length: 3 },
      (_, index) => `![分镜 ${index + 1}](/api/files/images/scene-${index + 1}.png)`,
    ).join('\n');
    render(<MessageBubble role="assistant" content={images} />);

    expect(screen.queryByText('本批图片已生成')).toBeNull();
    expect(screen.getAllByRole('img')).toHaveLength(3);
  });

  it('keeps legacy messages without metrics unchanged', () => {
    render(<MessageBubble role="assistant" content="旧消息" />);

    expect(screen.queryByText(/tokens/)).toBeNull();
    expect(screen.queryByText(/\d+(?:\.\d+)?(?:ms|s)/)).toBeNull();
  });

  it('shows edit next to copy for an editable user message', () => {
    const onEdit = vi.fn();
    render(
      <MessageBubble
        role="user"
        content="需要修改的问题"
        editable
        onEdit={onEdit}
      />,
    );

    const copy = screen.getByTitle('复制内容');
    const edit = screen.getByTitle('编辑消息');
    expect(copy.compareDocumentPosition(edit) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    fireEvent.click(edit);
    expect(onEdit).toHaveBeenCalledTimes(1);
  });

  it('edits the user message in place and submits the revised text', async () => {
    const submit = vi.fn().mockResolvedValue(undefined);
    render(
      <MessageBubble
        messageId="message-1"
        role="user"
        content="原始内容"
        editable
        onEditMessage={submit}
      />,
    );

    fireEvent.click(screen.getByTitle('编辑消息'));
    const editor = screen.getByLabelText('编辑消息内容');
    fireEvent.change(editor, { target: { value: '修改后的内容' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));

    await waitFor(() => {
      expect(submit).toHaveBeenCalledWith('message-1', '修改后的内容');
    });
    expect(screen.queryByLabelText('编辑消息内容')).toBeNull();
  });

  it('renders image and video attachments with working previews on user messages', () => {
    render(
      <MessageBubble
        role="user"
        content="这是什么"
        metadata={{
          messageInput: {
            attachments: [{
              url: '/api/files/images/reference.png',
              name: 'reference.png',
              mimeType: 'image/png',
              kind: 'image',
              source: 'upload',
            }, {
              url: '/api/files/videos/clip.mp4',
              name: 'clip.mp4',
              mimeType: 'video/mp4',
              kind: 'video',
              source: 'upload',
            }],
          },
        }}
      />,
    );

    expect(screen.getByRole('button', { name: '预览 reference.png' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '预览 clip.mp4' }));

    const dialog = screen.getByRole('dialog', { name: '图片预览' });
    expect(dialog.querySelector('video')).toBeTruthy();
  });
});
