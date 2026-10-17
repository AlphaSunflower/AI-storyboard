import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentAsset } from '../../api/agent';
import { libraryApi } from '../../api/library';
import { probeVideoDurationSeconds } from '../../lib/videoFrameExtract';
import { useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import { AssetDetailPane } from './AssetDetailPane';

vi.mock('gsap', () => ({
  default: {
    fromTo: vi.fn(),
    set: vi.fn(),
    to: vi.fn(),
    killTweensOf: vi.fn(),
    timeline: vi.fn(() => ({ to: vi.fn().mockReturnThis() })),
  },
}));
vi.mock('@gsap/react', () => ({
  useGSAP: vi.fn(),
}));

// 时长探测在 jsdom 里既不会触发 loadedmetadata 也不会报错，15 秒后才超时；
// 这里换成一个可控的桩，专门验「拿得到时长」和「拿不到时长」两条分支。
vi.mock('../../lib/videoFrameExtract', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/videoFrameExtract')>();
  return {
    ...actual,
    probeVideoDurationSeconds: vi.fn(() => Promise.reject(new Error('读不到时长'))),
  };
});

const twoResultImage: AgentAsset = {
  id: 'image-record-1',
  conversationId: 'conversation-1',
  type: 'image',
  url: '/api/files/images/one.png',
  prompt: '精细国风东方龙图案，暗金描边配朱砂红鳞片',
  model: 'Seedream 4.5',
  status: 'completed',
  taskId: null,
  error: null,
  createdAt: '2026-10-10T08:30:00.000Z',
  genType: 'text_to_image',
  params: JSON.stringify({ size: '2048x2048', quality: 'high', n: 2 }),
  durationMs: 84_000,
  resultUrls: '["/api/files/images/one.png","/api/files/images/two.png"]',
};

const videoAsset: AgentAsset = {
  ...twoResultImage,
  id: 'video-record-1',
  type: 'video',
  url: '/api/files/videos/clip.mp4',
  model: 'MiniMax-H3',
  genType: 'text_to_video',
  params: JSON.stringify({ resolution: '1080p', aspectRatio: '16:9', duration: '5' }),
  durationMs: null,
  resultUrls: '["/api/files/videos/clip.mp4"]',
};

function renderPane(asset: AgentAsset, url: string, onDeleted = vi.fn(), route = '/chat') {
  const onClose = vi.fn();
  render(
    <MemoryRouter initialEntries={[route]}>
      <AssetDetailPane target={{ url, asset }} onClose={onClose} onDeleted={onDeleted} />
    </MemoryRouter>,
  );
  return { onClose, onDeleted };
}

/** 聊天里点开的那种：只有 URL，没有记录，弹窗自己去会话资产里反查。 */
function renderBareMedia(url: string, known?: { prompt?: string; model?: string; createdAt?: string }) {
  const onClose = vi.fn();
  render(
    <MemoryRouter initialEntries={['/chat']}>
      <AssetDetailPane target={{ url, known }} onClose={onClose} onDeleted={vi.fn()} />
    </MemoryRouter>,
  );
  return { onClose };
}

function mockConversationAssets(records: AgentAsset[]) {
  return vi.spyOn(agentApi, 'listAssets').mockResolvedValue({
    data: { data: { records, total: records.length, page: 1, size: 50 } },
  } as never);
}

function mockLibraryLookup(records: unknown[] = []) {
  return vi.spyOn(libraryApi, 'listAssets').mockResolvedValue({
    data: { code: 0, message: '', data: { records, total: records.length, page: 1, size: 200 } },
  } as never);
}

function openMoreMenu() {
  fireEvent.click(screen.getByRole('button', { name: '更多操作' }));
}

/** 弹出来的坐标菜单：用它自己的容器定位，避免和右侧工具格里同名按钮撞车。 */
function moreMenu() {
  const surface = document.querySelector('.context-menu');
  if (!surface) throw new Error('操作菜单没有打开');
  return within(surface as HTMLElement);
}

describe('AssetDetailPane', () => {
  afterEach(() => {
    cleanup();
    useAgentStore.setState({
      composerInjection: null,
      activeConversationId: null,
      assets: null,
      injectComposerText: useAgentStore.getInitialState().injectComposerText,
      sendMessage: useAgentStore.getInitialState().sendMessage,
      addRefImageUrls: useAgentStore.getInitialState().addRefImageUrls,
    });
    useProjectStore.setState({ currentProject: null });
    vi.restoreAllMocks();
  });

  it('shows the prompt, the derived params and the generation meta', () => {
    mockLibraryLookup();
    useProjectStore.setState({ currentProject: { id: 'project-1', name: 'Project' } as never });
    renderPane(twoResultImage, '/api/files/images/one.png');

    expect(within(screen.getByRole('region', { name: '提示词' }))
      .getByText('精细国风东方龙图案，暗金描边配朱砂红鳞片')).toBeTruthy();

    // 模型、画幅（由 size 现算）、尺寸、质量、数量都在参数区里。
    const params = within(screen.getByRole('region', { name: '生成参数' }));
    expect(params.getByText('模型')).toBeTruthy();
    expect(params.getByText('Seedream 4.5')).toBeTruthy();
    expect(params.getByText('画幅')).toBeTruthy();
    expect(params.getByText('1:1')).toBeTruthy();
    expect(params.getByText('2048x2048')).toBeTruthy();

    // 元信息行给出耗时；生成方式由标题承担，不重复。
    expect(screen.getByText('1 分 24 秒')).toBeTruthy();
    expect(screen.getAllByText('文生图')).toHaveLength(1);
  });

  it('never offers a favourite button', () => {
    mockLibraryLookup();
    renderPane(twoResultImage, '/api/files/images/one.png');

    expect(screen.queryByText('收藏')).toBeNull();
    expect(screen.queryByRole('button', { name: /收藏/ })).toBeNull();
  });

  it('puts the prompt into the composer from the hover-only action', () => {
    mockLibraryLookup();
    renderPane(twoResultImage, '/api/files/images/one.png');

    fireEvent.click(screen.getByRole('button', { name: /使用提示词/ }));

    expect(useAgentStore.getState().composerInjection?.text)
      .toBe('精细国风东方龙图案，暗金描边配朱砂红鳞片');
  });

  it('lists copy, reference, library import and delete in the more menu', () => {
    mockLibraryLookup();
    useProjectStore.setState({ currentProject: { id: 'project-1', name: 'Project' } as never });
    renderPane(twoResultImage, '/api/files/images/one.png');

    openMoreMenu();

    const menu = moreMenu();
    for (const label of ['复制图片', '用作参考图', '导入到项目库', '删除']) {
      expect(menu.getByRole('button', { name: label })).toBeTruthy();
    }
  });

  it('disables the import entry once the asset is already in the project library', async () => {
    // sourceId 命中生成资产 id：这是 Agent 成组回写留下的形态。
    mockLibraryLookup([{ id: 'library-1', sourceId: 'image-record-1' }]);
    useProjectStore.setState({ currentProject: { id: 'project-1', name: 'Project' } as never });
    renderPane(twoResultImage, '/api/files/images/one.png');

    await waitFor(() => expect(libraryApi.listAssets).toHaveBeenCalled());
    openMoreMenu();

    const entry = moreMenu().getByRole('button', { name: '已导入项目库' });
    expect(entry).toHaveProperty('disabled', true);
    expect(moreMenu().queryByRole('button', { name: '导入到项目库' })).toBeNull();
  });

  it('imports into the project library on demand and then locks the entry', async () => {
    mockLibraryLookup();
    const register = vi.spyOn(libraryApi, 'registerGeneratedOutput').mockResolvedValue({
      data: { code: 0, message: '', data: { id: 'library-1' } },
    } as never);
    useProjectStore.setState({ currentProject: { id: 'project-1', name: 'Project' } as never });
    renderPane(twoResultImage, '/api/files/images/one.png');

    await waitFor(() => expect(libraryApi.listAssets).toHaveBeenCalled());
    openMoreMenu();
    fireEvent.click(moreMenu().getByRole('button', { name: '导入到项目库' }));

    await waitFor(() => expect(register).toHaveBeenCalledTimes(1));
    expect(register.mock.calls[0]?.[0]).toMatchObject({
      projectId: 'project-1',
      producer: 'moon-chat',
      resultIndex: 0,
      resultUrl: '/api/files/images/one.png',
      mediaType: 'IMAGE',
    });

    openMoreMenu();
    expect(moreMenu().getByRole('button', { name: '已导入项目库' })).toHaveProperty('disabled', true);
  });

  it('marks the editor as the origin when importing from there', async () => {
    mockLibraryLookup();
    const register = vi.spyOn(libraryApi, 'registerGeneratedOutput').mockResolvedValue({
      data: { code: 0, message: '', data: { id: 'library-1' } },
    } as never);
    useProjectStore.setState({ currentProject: { id: 'project-1', name: 'Project' } as never });
    renderPane(videoAsset, '/api/files/videos/clip.mp4', vi.fn(), '/editor');

    await waitFor(() => expect(libraryApi.listAssets).toHaveBeenCalled());
    openMoreMenu();
    fireEvent.click(moreMenu().getByRole('button', { name: '导入到项目库' }));

    await waitFor(() => expect(register).toHaveBeenCalledTimes(1));
    expect(register.mock.calls[0]?.[0]).toMatchObject({
      producer: 'storyboard-editor',
      mediaType: 'VIDEO',
    });
  });

  it('deletes only the result that is on the stage', async () => {
    mockLibraryLookup();
    const remove = vi.spyOn(agentApi, 'deleteAsset').mockResolvedValue({
      data: { code: 0, message: '', data: null },
    } as never);
    const { onDeleted } = renderPane(twoResultImage, '/api/files/images/two.png');

    openMoreMenu();
    fireEvent.click(moreMenu().getByRole('button', { name: '删除' }));

    expect(screen.getByRole('alertdialog')).toBeTruthy();
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '删除' }));

    await waitFor(() => expect(remove).toHaveBeenCalledWith('image-record-1', 1));
    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
  });

  it('deletes the whole record when only one result exists', async () => {
    mockLibraryLookup();
    const remove = vi.spyOn(agentApi, 'deleteAsset').mockResolvedValue({
      data: { code: 0, message: '', data: null },
    } as never);
    renderPane(videoAsset, '/api/files/videos/clip.mp4');

    openMoreMenu();
    fireEvent.click(moreMenu().getByRole('button', { name: '删除' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '删除' }));

    await waitFor(() => expect(remove).toHaveBeenCalledWith('video-record-1', undefined));
  });

  it('opens the lightbox from the stage and steps between results', () => {
    mockLibraryLookup();
    renderPane(twoResultImage, '/api/files/images/one.png');

    expect(screen.getByText('1 / 2')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '下一张结果' }));
    expect(screen.getByText('2 / 2')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '放大预览这张图片' }));
    expect(screen.getByRole('dialog', { name: '图片预览' })).toBeTruthy();
  });

  it('keeps the refinement tools and offers frame extraction for video', async () => {
    mockLibraryLookup();
    vi.mocked(probeVideoDurationSeconds).mockResolvedValue(8);
    renderPane(videoAsset, '/api/files/videos/clip.mp4');

    expect(screen.getByText('进一步改动')).toBeTruthy();
    expect(screen.getByRole('button', { name: /重新生成/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /用作参考视频/ })).toBeTruthy();
    expect(screen.getByText('获取帧')).toBeTruthy();
    expect(screen.getByRole('button', { name: '首帧' })).toBeTruthy();
    // 视频没有图片改图那套入口。
    expect(screen.queryByRole('button', { name: /局部重绘/ })).toBeNull();

    // 拿到时长之后自定义帧才可用，也不再显示「正在读取」。
    await waitFor(() => expect(screen.getByRole('button', { name: /抽取/ })).toHaveProperty('disabled', false));
    expect(screen.queryByText('正在读取视频时长…')).toBeNull();
  });

  it('says so instead of hanging on 「读取中」 when the duration cannot be probed', async () => {
    mockLibraryLookup();
    vi.mocked(probeVideoDurationSeconds).mockRejectedValue(new Error('读不到时长'));
    renderPane(videoAsset, '/api/files/videos/clip.mp4');

    await waitFor(() => expect(screen.getByText('读不到视频时长，自定义帧暂不可用')).toBeTruthy());
    expect(screen.getByRole('button', { name: /抽取/ })).toHaveProperty('disabled', true);
    // 首帧/尾帧不依赖时长，仍然可用。
    expect(screen.getByRole('button', { name: '首帧' })).toHaveProperty('disabled', false);
  });

  it('退出最上面那层浮层而不是把详情一起关掉', () => {
    mockLibraryLookup();
    const { onClose } = renderPane(twoResultImage, '/api/files/images/one.png');

    fireEvent.click(screen.getByRole('button', { name: '放大预览这张图片' }));
    expect(screen.getByRole('dialog', { name: '图片预览' })).toBeTruthy();

    // Esc 由详情在捕获阶段处理：灯箱关掉，详情本身留着（onClose 不该被调用）。
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: '图片预览' })).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
    expect(document.querySelector('.asset-detail')).toBeTruthy();
  });

  it('Esc 关掉删除确认框而不触发删除', () => {
    mockLibraryLookup();
    const remove = vi.spyOn(agentApi, 'deleteAsset').mockResolvedValue({
      data: { code: 0, message: '', data: null },
    } as never);
    renderPane(twoResultImage, '/api/files/images/one.png');

    openMoreMenu();
    fireEvent.click(moreMenu().getByRole('button', { name: '删除' }));
    expect(screen.getByRole('alertdialog')).toBeTruthy();

    fireEvent.keyDown(document.body, { key: 'Escape' });

    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(remove).not.toHaveBeenCalled();
  });

  it('是个模态弹窗：点遮罩关掉，点内容不关', () => {
    mockLibraryLookup();
    const { onClose } = renderPane(twoResultImage, '/api/files/images/one.png');

    const dialog = screen.getByRole('dialog', { name: /素材详情$/ });
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    // 弹窗挂在 body 上，不躲在侧边栏（带 backdrop-filter）里面。
    expect(dialog.parentElement?.classList.contains('asset-detail__backdrop')).toBe(true);

    // 点内容不关：遮罩的判定必须是「点到的就是遮罩自己」。
    fireEvent.pointerDown(dialog);
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.pointerDown(document.querySelector('.asset-detail__backdrop') as HTMLElement);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Esc 关掉弹窗本身', () => {
    mockLibraryLookup();
    const { onClose } = renderPane(twoResultImage, '/api/files/images/one.png');

    fireEvent.keyDown(document.body, { key: 'Escape' });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('打开时把焦点收进弹窗，并锁住背景滚动', () => {
    mockLibraryLookup();
    // 显式从「没锁」开始：滚动锁是全局状态，前一个用例里的灯箱会留下它自己的还原值。
    document.body.style.overflow = '';

    renderPane(twoResultImage, '/api/files/images/one.png');

    expect(document.activeElement?.classList.contains('asset-detail')).toBe(true);
    expect(document.body.style.overflow).toBe('hidden');

    cleanup();
    expect(document.body.style.overflow).toBe('');
  });

  it('tells the user when the model is missing instead of printing an empty line', () => {
    mockLibraryLookup();
    renderPane({ ...twoResultImage, model: null, prompt: null, params: null }, '/api/files/images/one.png');

    expect(screen.getByText('未记录模型')).toBeTruthy();
    expect(screen.getByText('这条素材没有记录提示词')).toBeTruthy();
    // 没有提示词就没有「使用提示词」，别给一个点了没反应的按钮。
    expect(screen.queryByRole('button', { name: /使用提示词/ })).toBeNull();
  });
});

describe('AssetDetailPane 会话里的裸媒体', () => {
  afterEach(() => {
    cleanup();
    useAgentStore.setState({
      activeConversationId: null,
      composerInjection: null,
      // 反查会先看 store 里已加载的那一页，用例之间必须清干净，否则互相命中。
      assets: null,
    });
    useProjectStore.setState({ currentProject: null });
    vi.restoreAllMocks();
  });

  it('聊天里点开一张图：按 URL 反查记录，补全提示词与参数', async () => {
    useAgentStore.setState({ activeConversationId: 'conversation-1' });
    const lookup = mockConversationAssets([twoResultImage]);
    mockLibraryLookup();

    renderBareMedia('/api/files/images/two.png');

    expect(lookup).toHaveBeenCalledWith('conversation-1', 1, 50);
    await waitFor(() => expect(within(screen.getByRole('region', { name: '提示词' }))
      .getByText('精细国风东方龙图案，暗金描边配朱砂红鳞片')).toBeTruthy());
    expect(within(screen.getByRole('region', { name: '生成参数' })).getByText('Seedream 4.5')).toBeTruthy();
    // 同一次生成的两张都在，缩略图条按记录展开。
    expect(screen.getByRole('tablist', { name: '这次生成的结果' })).toBeTruthy();
  });

  it('接口返回的签名地址也能和聊天里的裸地址对上（线上就是这里丢的信息）', async () => {
    useAgentStore.setState({ activeConversationId: 'conversation-1' });
    // 会话资产接口返回前会给本地 URL 签名；聊天正文里是生成当时的裸地址。
    mockConversationAssets([{
      ...twoResultImage,
      url: '/api/files/images/one.png?e=1770000000&s=9f2c',
      resultUrls: '["/api/files/images/one.png?e=1770000000&s=9f2c","/api/files/images/two.png?e=1770000000&s=9f2c"]',
    }]);
    mockLibraryLookup();

    renderBareMedia('/api/files/images/one.png');

    await waitFor(() => expect(within(screen.getByRole('region', { name: '提示词' }))
      .getByText('精细国风东方龙图案，暗金描边配朱砂红鳞片')).toBeTruthy());
    // 选中的那张要对齐到签名地址，否则缩略图条一张都不高亮。
    await waitFor(() => expect(screen.getByRole('tab', { name: '第 1 张结果' })
      .getAttribute('aria-selected')).toBe('true'));
    expect(screen.getByText('1 / 2')).toBeTruthy();
  });

  it('已经在侧边栏加载过的那一页能直接命中，不再打接口', async () => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      assets: { records: [twoResultImage], total: 1, page: 1, size: 20 },
    });
    const lookup = mockConversationAssets([]);
    mockLibraryLookup();

    renderBareMedia('/api/files/images/one.png');

    await waitFor(() => expect(within(screen.getByRole('region', { name: '提示词' }))
      .getByText('精细国风东方龙图案，暗金描边配朱砂红鳞片')).toBeTruthy());
    expect(lookup).not.toHaveBeenCalled();
  });

  it('查不到记录时降级成 URL 模式：图能看、能下载、能导入，但没有删除', async () => {
    useAgentStore.setState({ activeConversationId: 'conversation-1' });
    mockConversationAssets([]);
    mockLibraryLookup();
    useProjectStore.setState({ currentProject: { id: 'project-1', name: 'Project' } as never });

    renderBareMedia('https://cdn.example.com/outside.png');

    // 说清是「没找到记录」，而不是让用户以为这张图生成时没写提示词。
    await waitFor(() => expect(screen.getAllByText('没有找到这条素材的生成记录，只能按链接操作').length)
      .toBeGreaterThan(0));
    expect(screen.getByText('未记录生成信息')).toBeTruthy();

    openMoreMenu();
    expect(moreMenu().getByRole('button', { name: '导入到项目库' })).toBeTruthy();
    // 没有记录就没有可删的行，别给一个点下去会报错的删除。
    expect(moreMenu().getByRole('button', { name: '删除' })).toHaveProperty('disabled', true);
  });

  it('记录在但确实没写提示词时，文案和「没找到记录」区分开', async () => {
    useAgentStore.setState({ activeConversationId: 'conversation-1' });
    mockConversationAssets([{ ...twoResultImage, prompt: null, model: null, params: null }]);
    mockLibraryLookup();

    renderBareMedia('/api/files/images/one.png');

    await waitFor(() => expect(screen.getByText('这条素材没有记录提示词')).toBeTruthy());
    expect(screen.getByText('未记录模型')).toBeTruthy();
  });

  it('没有会话可查时直接进 URL 模式，不去打接口', async () => {
    const lookup = mockConversationAssets([]);
    mockLibraryLookup();
    useAgentStore.setState({ activeConversationId: null });

    renderBareMedia('/api/files/images/one.png');

    await waitFor(() => expect(screen.getAllByText('没有找到这条素材的生成记录，只能按链接操作').length)
      .toBeGreaterThan(0));
    expect(lookup).not.toHaveBeenCalled();
  });

  it('调用方已经知道提示词时（工具槽位），记录查不到也用得上', async () => {
    useAgentStore.setState({ activeConversationId: 'conversation-1' });
    mockConversationAssets([]);
    mockLibraryLookup();

    renderBareMedia('/api/files/images/one.png', { prompt: '槽位上的提示词', model: 'Seedream 4.5' });

    await waitFor(() => expect(within(screen.getByRole('region', { name: '提示词' }))
      .getByText('槽位上的提示词')).toBeTruthy());
    expect(screen.getByText('Seedream 4.5')).toBeTruthy();
  });

  it('显式给了 kind 就按它渲染，不靠扩展名猜', async () => {
    useAgentStore.setState({ activeConversationId: null });
    mockLibraryLookup();

    render(
      <MemoryRouter initialEntries={['/chat']}>
        <AssetDetailPane
          target={{ url: 'https://cdn.example.com/clip', kind: 'video' }}
          onClose={vi.fn()}
          onDeleted={vi.fn()}
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole('button', { name: '全屏播放这个视频' })).toBeTruthy();
    // 标题说这是视频（角标上也有一个「视频」，所以按标题元素定位）。
    expect(within(screen.getByRole('dialog', { name: /素材详情$/ })).getAllByText('视频').length)
      .toBeGreaterThan(0);
  });
});
