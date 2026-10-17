import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentAsset } from '../../api/agent';
import { libraryApi } from '../../api/library';
import { useAgentStore } from '../../stores/agentStore';
import { useAssetDetailStore } from '../../stores/assetDetailStore';
import { useProjectStore } from '../../stores/projectStore';
import { AssetDetailHost } from './AssetDetailHost';
import { MessageBubble } from './MessageBubble';

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

const record: AgentAsset = {
  id: 'image-record-1',
  conversationId: 'conversation-1',
  type: 'image',
  url: '/api/files/images/generated.png',
  prompt: '精细国风东方龙图案',
  model: 'Seedream 4.5',
  status: 'completed',
  taskId: null,
  error: null,
  createdAt: '2026-10-10T08:30:00.000Z',
  params: JSON.stringify({ size: '1024x1024' }),
  durationMs: 30_000,
  resultUrls: '["/api/files/images/generated.png"]',
};

function mockLibraryLookup() {
  return vi.spyOn(libraryApi, 'listAssets').mockResolvedValue({
    data: { code: 0, message: '', data: { records: [], total: 0, page: 1, size: 200 } },
  } as never);
}

describe('AssetDetailHost', () => {
  beforeEach(() => {
    // MessageBubble 的打字机效果会读 matchMedia；jsdom 没有实现。
    vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })));
  });

  afterEach(() => {
    cleanup();
    useAssetDetailStore.getState().closeAssetDetail();
    useAgentStore.setState({ activeConversationId: null, loadAssets: useAgentStore.getInitialState().loadAssets });
    useProjectStore.setState({ currentProject: null });
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('没打开东西时什么都不渲染', () => {
    render(
      <MemoryRouter>
        <AssetDetailHost />
      </MemoryRouter>,
    );

    expect(document.querySelector('.asset-detail')).toBeNull();
  });

  it('返回把全局目标清掉', () => {
    mockLibraryLookup();
    useAssetDetailStore.getState().openAssetDetail({ url: record.url, asset: record });

    render(
      <MemoryRouter>
        <AssetDetailHost />
      </MemoryRouter>,
    );

    expect(screen.getByRole('dialog', { name: /素材详情$/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '返回' }));

    expect(useAssetDetailStore.getState().target).toBeNull();
    expect(document.querySelector('.asset-detail')).toBeNull();
  });

  it('删除成功后关窗并刷新会话资产列表', async () => {
    mockLibraryLookup();
    vi.spyOn(agentApi, 'deleteAsset').mockResolvedValue({
      data: { code: 0, message: '', data: null },
    } as never);
    const loadAssets = vi.fn();
    useAgentStore.setState({ loadAssets });
    useAssetDetailStore.getState().openAssetDetail({ url: record.url, asset: record });

    render(
      <MemoryRouter>
        <AssetDetailHost />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '更多操作' }));
    fireEvent.click(within(document.querySelector('.context-menu') as HTMLElement)
      .getByRole('button', { name: '删除' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '删除' }));

    await waitFor(() => expect(useAssetDetailStore.getState().target).toBeNull());
    expect(loadAssets).toHaveBeenCalled();
  });

  it('会话里点一张图，弹出和生成中心同一个详情窗并补全提示词', async () => {
    useAgentStore.setState({ activeConversationId: 'conversation-1' });
    vi.spyOn(agentApi, 'listAssets').mockResolvedValue({
      data: { data: { records: [record], total: 1, page: 1, size: 50 } },
    } as never);
    mockLibraryLookup();

    render(
      <MemoryRouter initialEntries={['/chat']}>
        <MessageBubble role="assistant" content="![生成图片](/api/files/images/generated.png)" />
        <AssetDetailHost />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('img', { name: '生成图片' }));

    expect(await screen.findByRole('dialog', { name: /素材详情$/ })).toBeTruthy();
    await waitFor(() => expect(within(screen.getByRole('region', { name: '提示词' }))
      .getByText('精细国风东方龙图案')).toBeTruthy());
    expect(within(screen.getByRole('region', { name: '生成参数' })).getByText('Seedream 4.5')).toBeTruthy();
  });
});
