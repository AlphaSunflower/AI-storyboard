import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { libraryApi } from '../../api/library';
import { useProjectStore } from '../../stores/projectStore';
import { useChatAnnotationStore } from '../../stores/chatAnnotationStore';
import { useDocumentPreviewStore } from '../../stores/documentPreviewStore';
import { useSideChatSelectionStore } from '../../stores/sideChatSelectionStore';
import { useAgentStore } from '../../stores/agentStore';
import { CHAT_MEDIA_DRAG_MIME } from '../../lib/projectLibraryDrag';
import { rememberDocumentSource } from '../../lib/documentPreview';
import { assetUrl } from '../../config';
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

  it('renders a persistent canvas run card from message metadata', () => {
    render(
      <MessageBubble
        role="assistant"
        content=""
        metadata={{
          kind: 'canvas-run',
          canvasRunId: 'run-1',
          canvasId: 'canvas-1',
          title: '雪茄房转场画布',
          status: 'completed',
          nodeCount: 12,
          edgeCount: 13,
          revision: 4,
        }}
      />,
    );

    expect(screen.getByText('雪茄房转场画布')).toBeTruthy();
    expect(screen.getByText('12 节点 · 13 连线')).toBeTruthy();
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

  it('renders ==highlight== markers as mark elements and leaves code untouched', () => {
    render(
      <MessageBubble
        role="assistant"
        content="常规说明 `==保持原样==`，还有 ==最高优先级提醒=="
      />,
    );

    expect(screen.getByText('最高优先级提醒').tagName).toBe('MARK');
    expect(screen.getByText('==保持原样==').tagName).toBe('CODE');
  });

  it('keeps single newlines as line breaks in markdown content', () => {
    const { container } = render(
      <MessageBubble role="assistant" content={'第一行\n第二行'} />,
    );

    expect(container.querySelector('.chat-markdown br')).toBeTruthy();
  });

  it('lays out one preset slot per generated image and drops the inline copies', () => {
    const openGenerationCenter = vi.fn();
    useAgentStore.setState({
      tasks: {
        'task-1': {
          taskId: 'task-1',
          conversationId: 'conversation-1',
          projectId: 'project-1',
          title: '批量生成 3 张图片',
          toolName: 'image',
          kind: 'image-batch',
          status: 'running',
          stage: '',
          progress: 33,
          resultSummary: '',
          error: '',
          parentTaskId: '',
          anchorMessageId: '',
          version: 1,
          createdAt: '',
          updatedAt: '',
          assetIds: [],
          resultUrls: [],
          result: '',
          liveText: '',
          liveThinking: '',
          liveStage: '',
          canCancel: true,
          canRetry: false,
          canFollowUp: false,
          slots: [
            {
              index: 0,
              label: '分镜 1',
              status: 'completed',
              prompt: '海边',
              url: '/api/files/images/scene-1.png',
              assetId: 'asset-1',
              error: '',
            },
            { index: 1, label: '分镜 2', status: 'running', prompt: '草原', url: '', assetId: '', error: '' },
            { index: 2, label: '分镜 3', status: 'pending', prompt: '雪山', url: '', assetId: '', error: '' },
          ],
        },
      } as never,
    });
    const { container } = render(
      <MessageBubble
        role="assistant"
        content={'已补齐缺图分镜。\n已生成 3 张图片\n![分镜 1](/api/files/images/scene-1.png)\n//# (async-task:task-1:end)'}
        metadata={{ taskCards: [{ taskId: 'task-1', title: '批量生成 3 张图片', kind: 'image-batch' }] } as never}
        onOpenGenerationCenter={openGenerationCenter}
      />,
    );

    expect(container.querySelectorAll('.gen-slot')).toHaveLength(3);
    expect(container.querySelectorAll('.gen-slot__media img')).toHaveLength(1);
    expect(container.querySelector('[data-status="generating"]')).toBeTruthy();
    expect(container.querySelector('[data-status="queued"]')).toBeTruthy();
    // 状态胶囊 + 无障碍朗读各一份，取自同一帧的进度文案
    expect(screen.getAllByText('生成中 33%').length).toBeGreaterThan(0);
    expect(screen.queryByText('已生成 3 张图片')).toBeNull();
    expect(screen.queryByText(/async-task/)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '查看全部' }));
    expect(openGenerationCenter).toHaveBeenCalledTimes(1);

    useAgentStore.setState({ tasks: {} });
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

  it('defers external markdown images until the user clicks the placeholder', () => {
    const { container } = render(
      <MessageBubble
        role="assistant"
        content="![外部图片](https://cdn.example.com/remote.png)"
      />,
    );

    expect(container.querySelector('img')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '点击加载外部图片' }));
    expect(screen.getByRole('img').getAttribute('referrerpolicy')).toBe('no-referrer');
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

    const copy = screen.getByRole('button', { name: /复制/ });
    const edit = screen.getByRole('button', { name: '编辑' });
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

    fireEvent.click(screen.getByRole('button', { name: '编辑' }));
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

  it('opens a Markdown attachment in the chat document child window', async () => {
    const url = '/api/files/files/project-brief.md';
    rememberDocumentSource(url, new Blob([
      '# 项目概览\n\n- 目标：生成分镜\n- 状态：进行中',
    ], { type: 'text/markdown' }));

    render(
      <MessageBubble
        role="user"
        content="请阅读文档"
        metadata={{
          messageInput: {
            attachments: [{
              url,
              name: 'project-brief.md',
              mimeType: 'text/markdown',
              kind: 'file',
              source: 'upload',
            }],
          },
        }}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '打开文档 project-brief.md' }));

    expect(useDocumentPreviewStore.getState().attachment).toMatchObject({
      url,
      name: 'project-brief.md',
      kind: 'file',
    });
  });

  const renderFileAttachmentBubble = () => render(
    <MessageBubble
      messageId="m-file"
      conversationId="c1"
      role="assistant"
      content="文档已经写好"
      metadata={{
        sandboxAttachments: [{
          url: '/api/files/files/mood.md',
          name: '此刻的心情.md',
          mimeType: 'text/markdown',
          kind: 'file',
          source: 'upload',
        }],
      }}
    />,
  );

  it('hints that a message file chip has a context menu', async () => {
    renderFileAttachmentBubble();

    const chip = screen.getByRole('button', { name: '打开文档 此刻的心情.md' });
    fireEvent.pointerEnter(chip.closest('.warm-tooltip__trigger')!, {
      pointerType: 'mouse',
      buttons: 0,
    });

    expect((await screen.findByRole('tooltip')).textContent).toContain('右键查看更多操作');
  });

  it('downloads a message file from the right-click menu', () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    renderFileAttachmentBubble();

    fireEvent.contextMenu(screen.getByRole('button', { name: '打开文档 此刻的心情.md' }));
    fireEvent.click(screen.getByRole('button', { name: '下载' }));

    expect(click).toHaveBeenCalledTimes(1);
    click.mockRestore();
  });

  it('quotes a message file back into the composer attachments', () => {
    useAgentStore.setState({ pendingAttachments: [] });
    renderFileAttachmentBubble();

    fireEvent.contextMenu(screen.getByRole('button', { name: '打开文档 此刻的心情.md' }));
    fireEvent.click(screen.getByRole('button', { name: '引用' }));

    expect(useAgentStore.getState().pendingAttachments).toMatchObject([{
      url: '/api/files/files/mood.md',
      name: '此刻的心情.md',
      kind: 'file',
    }]);
  });

  it('asks about a message file in the side chat', () => {
    const openSideChat = vi.spyOn(useAgentStore.getState(), 'openSideChat')
      .mockResolvedValue(undefined);
    useSideChatSelectionStore.getState().clear();
    renderFileAttachmentBubble();

    fireEvent.contextMenu(screen.getByRole('button', { name: '打开文档 此刻的心情.md' }));
    fireEvent.click(screen.getByRole('button', { name: '在侧边提问' }));

    expect(useSideChatSelectionStore.getState().selections).toMatchObject([{
      text: '文件「此刻的心情.md」：/api/files/files/mood.md',
      messageId: 'm-file',
    }]);
    expect(openSideChat).toHaveBeenCalledWith('c1', undefined);

    openSideChat.mockRestore();
  });
});

describe('MessageBubble annotations', () => {
  beforeEach(() => {
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    useProjectStore.setState({ currentProject: null });
    useChatAnnotationStore.getState().reset();
    useSideChatSelectionStore.getState().clear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    useChatAnnotationStore.getState().reset();
    useSideChatSelectionStore.getState().clear();
  });

  const renderAssistant = () => render(
    <MessageBubble
      messageId="m1"
      conversationId="c1"
      role="assistant"
      variant="deepseek"
      content="明白，我停下,等你说完。"
    />,
  );

  const stubRangeRect = () => {
    const proto = Range.prototype as unknown as { getBoundingClientRect?: () => DOMRect };
    Object.defineProperty(proto, 'getBoundingClientRect', {
      configurable: true,
      writable: true,
      value: () => ({
        top: 120, left: 100, bottom: 140, right: 180, width: 80, height: 20, x: 100, y: 120,
        toJSON: () => ({}),
      } as DOMRect),
    });
    return () => { delete proto.getBoundingClientRect; };
  };

  const waitForHighlightPass = async () => {
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 30)); });
  };

  it('shows the count badge on the quoted text and opens the editor there', async () => {
    const restore = stubRangeRect();
    useChatAnnotationStore.setState({
      annotations: [{
        id: 'a1', conversationId: 'c1', messageId: 'm1',
        quote: '明白，我停下,等你说完。', comment: '先别动手', createdAt: 1,
      }],
    });
    renderAssistant();
    await waitForHighlightPass();

    fireEvent.click(screen.getByRole('button', { name: '1 条注释徽标' }));
    const editor = await screen.findByRole('dialog', { name: '编辑注释' });

    // 编辑框贴着被引用原文：top = 原文 rect.bottom(140) + 8，left = 原文 left(100)
    expect((editor as HTMLElement).style.top).toBe('148px');
    expect((editor as HTMLElement).style.left).toBe('100px');

    const input = editor.querySelector('textarea')!;
    fireEvent.change(input, { target: { value: '改好的评论' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    expect(useChatAnnotationStore.getState().annotations[0].comment).toBe('改好的评论');
    expect(useChatAnnotationStore.getState().editingId).toBeNull();
    restore();
  });

  it('opens the annotation list at the quoted text when the badge covers several', async () => {
    const restore = stubRangeRect();
    useChatAnnotationStore.setState({
      annotations: [
        { id: 'a1', conversationId: 'c1', messageId: 'm1', quote: '明白', comment: '第一处', createdAt: 1 },
        { id: 'a2', conversationId: 'c1', messageId: 'm1', quote: '我停下', comment: '第二处', createdAt: 2 },
      ],
    });
    renderAssistant();
    await waitForHighlightPass();

    fireEvent.click(screen.getByRole('button', { name: '2 条注释徽标' }));
    const list = await screen.findByRole('dialog', { name: '注释列表' });
    expect((list as HTMLElement).style.top).toBe('148px');
    restore();
  });

  it('skips the editor in a hidden duplicate layout instead of pinning it to 0,0', () => {
    // /chat 会同时渲染手机端与桌面端消息，隐藏的那份拿到的 rect 全是 0；
    // 这种情况不能再画一个编辑框，否则会有一个飘到窗口左上角。
    useChatAnnotationStore.setState({
      annotations: [{
        id: 'a1', conversationId: 'c1', messageId: 'm1',
        quote: '这段原文在消息里找不到', comment: '', createdAt: 1,
      }],
    });
    renderAssistant();
    const rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      top: 0, left: 0, bottom: 0, right: 0, width: 0, height: 0, x: 0, y: 0, toJSON: () => ({}),
    } as DOMRect);

    act(() => { useChatAnnotationStore.getState().openEditor('a1'); });
    expect(screen.queryByRole('dialog', { name: '编辑注释' })).toBeNull();

    rectSpy.mockRestore();
  });

  it('hides the embedded annotation context on sent user messages', () => {
    render(
      <MessageBubble
        messageId="m1"
        conversationId="c1"
        role="user"
        variant="deepseek"
        content={'【引用注释】\n1. 原文：明白\n   评论：无\n【/引用注释】\n\n我的问题'}
      />,
    );

    expect(screen.getByText('我的问题')).toBeTruthy();
    expect(screen.queryByText(/引用注释/)).toBeNull();
  });

  it('shows a quote action on selection and opens the editor only after clicking it', () => {
    const rangeProto = Range.prototype as unknown as {
      getBoundingClientRect?: () => DOMRect;
    };
    Object.defineProperty(rangeProto, 'getBoundingClientRect', {
      configurable: true,
      writable: true,
      value: () => ({
        top: 120, left: 100, bottom: 140, right: 180, width: 80, height: 20, x: 100, y: 120,
        toJSON: () => ({}),
      } as DOMRect),
    });
    renderAssistant();

    const paragraph = screen.getByText('明白，我停下,等你说完。');
    const textNode = paragraph.firstChild as Text;
    const range = document.createRange();
    range.setStart(textNode, 0);
    range.setEnd(textNode, 4);
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);

    fireEvent.mouseUp(document.querySelector('.chat-live-text')!);

    expect(screen.getByRole('button', { name: '引用这段内容' })).toBeTruthy();
    expect(screen.queryByRole('dialog', { name: '添加注释' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '引用这段内容' }));
    expect(screen.getByRole('dialog', { name: '添加注释' })).toBeTruthy();

    delete rangeProto.getBoundingClientRect;
    window.getSelection()?.removeAllRanges();
  });

  it('adds the selected text to side-chat context and opens a temporary side chat', () => {
    const restore = stubRangeRect();
    const openSideChat = vi.spyOn(useAgentStore.getState(), 'openSideChat')
      .mockResolvedValue(undefined);
    renderAssistant();

    const paragraph = screen.getByText('明白，我停下,等你说完。');
    const range = document.createRange();
    range.setStart(paragraph.firstChild as Text, 0);
    range.setEnd(paragraph.firstChild as Text, 4);
    const selectedText = range.toString();
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);
    fireEvent.mouseUp(document.querySelector('.chat-live-text')!);
    fireEvent.click(screen.getByRole('button', { name: '侧边提问' }));

    expect(useSideChatSelectionStore.getState().selections).toHaveLength(1);
    expect(useSideChatSelectionStore.getState().selections[0].text).toBe(selectedText);
    expect(openSideChat).toHaveBeenCalledWith('c1', undefined);

    openSideChat.mockRestore();
    restore();
    window.getSelection()?.removeAllRanges();
  });

  it('keeps the toolbar on scroll instead of splitting it from the highlight', async () => {
    // 回归：旧实现滚动即收起弹框、但原生选区还留着 → 只剩高亮没有弹框的“半截状态”。
    // 现在弹框与高亮绑定同一状态：滚动只重锚定，两者同生共死。
    const restore = stubRangeRect();
    renderAssistant();

    const paragraph = screen.getByText('明白，我停下,等你说完。');
    const range = document.createRange();
    range.setStart(paragraph.firstChild as Text, 0);
    range.setEnd(paragraph.firstChild as Text, 4);
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);

    fireEvent.mouseUp(document.querySelector('.chat-live-text')!);
    expect(screen.getByRole('button', { name: '引用这段内容' })).toBeTruthy();

    fireEvent.scroll(window);
    await waitForHighlightPass();
    expect(screen.getByRole('button', { name: '引用这段内容' })).toBeTruthy();

    restore();
    window.getSelection()?.removeAllRanges();
  });
});

describe('MessageBubble block actions', () => {
  const writeText = vi.fn(async () => undefined);
  const write = vi.fn(async () => undefined);
  const createObjectURL = vi.fn(() => 'blob:mock');

  beforeEach(() => {
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText, write },
    });
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      writable: true,
      value: createObjectURL,
    });
    writeText.mockClear();
    write.mockClear();
    createObjectURL.mockClear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: undefined });
  });

  /** 拦截 anchor.click，返回最近一次下载的文件名与所在链接。 */
  const captureDownload = () => {
    const appendChild = vi.spyOn(document.body, 'appendChild');
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    // 右键菜单、Tooltip 也会 portal 到 body，只认最近一次插入的下载锚点
    return () => [...appendChild.mock.calls]
      .reverse()
      .map((call) => call[0])
      .find((node): node is HTMLAnchorElement => node instanceof HTMLAnchorElement)!;
  };

  it('copies and downloads markdown tables from the block toolbar', async () => {
    const lastLink = captureDownload();
    render(
      <MessageBubble
        role="assistant"
        content={'| 项目 | 设计 |\n| --- | --- |\n| 风格 | 现代轻奢 |'}
      />,
    );

    expect(screen.getByRole('table').closest('.chat-markdown-block--table')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '复制表格' }));
    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith('| 项目 | 设计 |\n| --- | --- |\n| 风格 | 现代轻奢 |');
    });
    await screen.findByRole('button', { name: '已复制' });

    fireEvent.click(screen.getByRole('button', { name: '下载表格' }));
    expect(lastLink().download).toBe('table-1.csv');
    expect(createObjectURL).toHaveBeenCalledTimes(1);
  });

  it('labels code blocks with their fence language and exports the matching extension', async () => {
    const lastLink = captureDownload();
    render(<MessageBubble role="assistant" content={'```ts\nconst a = 1;\n```'} />);

    const label = screen.getByText('ts');
    // 语言标签必须落在 data-annotation-ui 里，否则会污染注释引用的文本映射
    expect(label.closest('[data-annotation-ui]')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '复制代码' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('const a = 1;'));

    fireEvent.click(screen.getByRole('button', { name: '下载代码' }));
    expect(lastLink().download).toBe('snippet-1.ts');
  });

  it('falls back to .txt for fenced blocks without a known language', () => {
    const lastLink = captureDownload();
    render(<MessageBubble role="assistant" content={'```\n纯文本片段\n```'} />);

    expect(screen.getByText('text')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '下载代码' }));
    expect(lastLink().download).toBe('snippet-1.txt');
  });

  it('keeps the message meta row free of a whole-message download', () => {
    render(<MessageBubble role="assistant" content="正文内容" createdAt="2026-10-12T15:30:00" />);

    expect(screen.getByRole('button', { name: /复制/ })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '下载消息' })).toBeNull();
  });

  it('falls back to copying the image url when bitmaps are unsupported', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline'); }));
    render(<MessageBubble role="assistant" content="![封面](/api/files/images/cover.png)" />);

    fireEvent.click(screen.getByRole('button', { name: '复制图片' }));
    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith(assetUrl('/api/files/images/cover.png'));
    });
    expect(screen.getByRole('button', { name: '下载图片' })).toBeTruthy();
  });

  it('keeps image and video right-click menus alongside the new toolbar', async () => {
    const lastLink = captureDownload();
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline'); }));
    render(
      <MessageBubble
        role="assistant"
        content={'![封面](/api/files/images/cover.png)\n\nhttps://cdn.example.com/clip.mp4'}
      />,
    );

    fireEvent.contextMenu(screen.getByRole('img'));
    fireEvent.click(screen.getByRole('button', { name: '下载' }));
    await waitFor(() => expect(lastLink()?.download).toBe('封面.png'));

    expect(screen.getByRole('button', { name: '复制视频' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '下载视频' })).toBeTruthy();
  });

  it('hides block actions while the message is still streaming', async () => {
    // 让流式逐字补间一次性走完，测试只关心操作条在 streaming 期间不渲染
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      callback(Number.MAX_SAFE_INTEGER);
      return 0;
    });
    vi.stubGlobal('cancelAnimationFrame', () => {});
    render(
      <MessageBubble
        role="assistant"
        streaming
        content={'| 项目 | 设计 |\n| --- | --- |\n| 风格 | 现代轻奢 |'}
      />,
    );

    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    expect(screen.queryByRole('button', { name: '复制表格' })).toBeNull();
    expect(screen.queryByRole('button', { name: '下载表格' })).toBeNull();
  });
});
