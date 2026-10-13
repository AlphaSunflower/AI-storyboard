import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import { useToastStore } from '../../stores/toastStore';
import { planActionBar } from '../../lib/mediaActionBar';
import { ChatMediaActions } from './ChatMediaActions';

const IMAGE_URL = '/api/files/images/dragon.png';
const VIDEO_URL = '/api/files/videos/clip.mp4';

function toastsWithTitle(title: string) {
  return useToastStore.getState().toasts.filter((toast) => toast.title === title);
}

/** 模拟画框宽度：宽度够就全展示，不够才把尾部挪进「⋯」 */
function stubFrameWidth(width: number) {
  return vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({
    width,
    height: width,
    top: 0,
    left: 0,
    right: width,
    bottom: width,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  }) as DOMRect);
}

const IMAGE_ACTIONS = ['重新生成', '智能改图', '智能超清', '局部重绘', '去画布编辑', '消除笔', '扩图', '多角度', '引用为参考图'];

describe('ChatMediaActions', () => {
  beforeEach(() => {
    useToastStore.setState({ toasts: [] });
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      refImageUrls: [],
      pickedReferenceVideos: [],
      pickedReferenceAudios: [],
      messages: [],
    });
    useProjectStore.setState({ currentProject: { id: 'project-1', name: '项目' } as never });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    useToastStore.setState({ toasts: [] });
  });

  it('exposes the full image action set with Chinese tooltips', () => {
    render(<ChatMediaActions kind="image" src={IMAGE_URL} source={IMAGE_URL} alt="生成图片" />);

    for (const label of ['复制图片', '下载图片', ...IMAGE_ACTIONS]) {
      expect(screen.getByRole('button', { name: label })).toBeTruthy();
    }
  });

  it('shows every image-edit action in the bottom bar when the frame is wide enough', () => {
    const { container } = render(<ChatMediaActions kind="image" src={IMAGE_URL} source={IMAGE_URL} />);
    const bar = container.querySelector('.chat-media-actions__bar');
    const corner = container.querySelector('.chat-media-actions__corner');

    for (const label of IMAGE_ACTIONS) {
      expect(bar?.querySelector(`[aria-label="${label}"]`)).toBeTruthy();
    }
    // 全都放得下时连「⋯」都不用出现
    expect(bar?.querySelector('[aria-label="更多操作"]')).toBeNull();
    // 右上角只留复制 / 下载
    expect(corner?.querySelectorAll('button')).toHaveLength(2);
  });

  it('moves the actions that no longer fit into the overflow menu', () => {
    expect(planActionBar(0, 9)).toEqual({ visible: 9, overflow: 0 });
    expect(planActionBar(600, 9)).toEqual({ visible: 9, overflow: 0 });
    expect(planActionBar(200, 9)).toEqual({ visible: 4, overflow: 5 });
    expect(planActionBar(64, 9)).toEqual({ visible: 1, overflow: 8 });

    stubFrameWidth(160);
    const { container } = render(<ChatMediaActions kind="image" src={IMAGE_URL} source={IMAGE_URL} />);
    const bar = container.querySelector('.chat-media-actions__bar');

    expect(bar?.querySelector('[aria-label="重新生成"]')).toBeTruthy();
    expect(bar?.querySelector('[aria-label="智能改图"]')).toBeTruthy();
    expect(bar?.querySelector('[aria-label="引用为参考图"]')).toBeNull();
    expect(bar?.querySelector('[aria-label="更多操作"]')).toBeTruthy();
  });

  it('keeps the not-yet-built actions behind a not-implemented toast', () => {
    render(<ChatMediaActions kind="image" src={IMAGE_URL} source={IMAGE_URL} />);

    fireEvent.click(screen.getByRole('button', { name: '智能改图' }));
    fireEvent.click(screen.getByRole('button', { name: '智能超清' }));
    fireEvent.click(screen.getByRole('button', { name: '局部重绘' }));

    expect(toastsWithTitle('未实现该功能')).toHaveLength(3);
  });

  it('sends a regeneration turn with the image as reference', async () => {
    const sendMessage = vi.fn().mockResolvedValue(undefined);
    useAgentStore.setState({ sendMessage } as never);
    render(<ChatMediaActions kind="image" src={IMAGE_URL} source={IMAGE_URL} alt="生成图片" />);

    fireEvent.click(screen.getByRole('button', { name: '重新生成' }));

    await waitFor(() => {
      expect(sendMessage).toHaveBeenCalledWith(
        '重新生成这张图片，保持相同的提示词与风格',
        { picUrls: [IMAGE_URL] },
      );
    });
  });

  it('asks for confirmation before regenerating a video', async () => {
    const sendMessage = vi.fn().mockResolvedValue(undefined);
    useAgentStore.setState({ sendMessage } as never);
    render(<ChatMediaActions kind="video" src={VIDEO_URL} source={VIDEO_URL} />);

    fireEvent.click(screen.getByRole('button', { name: '重新生成' }));
    expect(sendMessage).not.toHaveBeenCalled();
    expect(screen.getByRole('alertdialog')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '确认重新生成' }));

    await waitFor(() => {
      expect(sendMessage).toHaveBeenCalledWith(
        '重新生成这个视频，保持相同的提示词与参数',
        { referenceVideos: [VIDEO_URL] },
      );
    });
  });

  it('quotes the media as composer reference material from the overflow menu', () => {
    stubFrameWidth(160);
    render(<ChatMediaActions kind="image" src={IMAGE_URL} source={IMAGE_URL} />);

    fireEvent.click(screen.getByRole('button', { name: '更多操作' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '引用为参考图' }));

    expect(useAgentStore.getState().refImageUrls).toEqual([IMAGE_URL]);
  });

  it('quotes a video as reference video from the overflow menu', () => {
    stubFrameWidth(120);
    render(<ChatMediaActions kind="video" src={VIDEO_URL} source={VIDEO_URL} />);

    fireEvent.click(screen.getByRole('button', { name: '更多操作' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '引用为参考视频' }));

    expect(useAgentStore.getState().pickedReferenceVideos).toEqual([VIDEO_URL]);
  });

  it('opens the frame picker with first, last and custom positions', () => {
    render(<ChatMediaActions kind="video" src={VIDEO_URL} source={VIDEO_URL} />);

    fireEvent.click(screen.getByRole('button', { name: '获取帧' }));

    expect(screen.getByRole('button', { name: '首帧' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '尾帧' })).toBeTruthy();
    expect(screen.getByText('自定义帧')).toBeTruthy();
    expect(screen.getByRole('button', { name: '抽取' })).toBeTruthy();
  });

  it('opens the canvas site in a new window carrying the media', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue({} as Window);
    render(<ChatMediaActions kind="image" src={IMAGE_URL} source={IMAGE_URL} />);

    fireEvent.click(screen.getByRole('button', { name: '去画布编辑' }));

    expect(open).toHaveBeenCalledTimes(1);
    const target = String(open.mock.calls[0][0]);
    expect(target).toContain('#project=project-1');
    expect(target).toContain(`media=${encodeURIComponent(JSON.stringify([IMAGE_URL]))}`);
  });
});
