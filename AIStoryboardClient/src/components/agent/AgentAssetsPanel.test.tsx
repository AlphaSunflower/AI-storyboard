import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentAsset } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { useDocumentPreviewStore } from '../../stores/documentPreviewStore';
import { AgentAssetsModal } from './AgentAssetsPanel';

vi.mock('gsap', () => ({
  default: { fromTo: vi.fn() },
}));
vi.mock('@gsap/react', () => ({
  useGSAP: vi.fn(),
}));

const documentAsset: AgentAsset = {
  id: 'document-1',
  conversationId: 'conversation-1',
  type: 'document',
  url: '/api/files/reports/quarterly.md',
  prompt: null,
  model: null,
  status: 'completed',
  taskId: 'task-1',
  error: null,
  createdAt: '2026-10-10T00:00:00.000Z',
  resultUrls: '["/api/files/reports/quarterly.md"]',
};

describe('AgentAssetsModal', () => {
  afterEach(() => {
    cleanup();
    useAgentStore.setState({
      assets: null,
      activeConversationId: null,
      loadAssets: useAgentStore.getInitialState().loadAssets,
    });
    useDocumentPreviewStore.setState({ attachment: null });
    vi.restoreAllMocks();
  });

  it('shows a document tab and opens document assets with the document preview store', () => {
    const loadAssets = vi.fn().mockResolvedValue(undefined);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      assets: {
        records: [documentAsset],
        total: 1,
        documentTotal: 1,
        page: 1,
        size: 20,
      },
      loadAssets,
    });

    render(
      <MemoryRouter>
        <AgentAssetsModal open onClose={vi.fn()} />
      </MemoryRouter>,
    );

    const documentTab = screen.getByRole('button', { name: /文档/ });
    expect(documentTab.textContent).toContain('1');
    fireEvent.click(documentTab);
    fireEvent.click(screen.getByRole('button', { name: /打开 quarterly.md/ }));

    expect(useDocumentPreviewStore.getState().attachment).toEqual({
      url: '/api/files/reports/quarterly.md',
      name: 'quarterly.md',
      mimeType: 'text/markdown',
      kind: 'file',
      assetId: 'document-1',
    });
  });
});

const multiImageAsset: AgentAsset = {
  id: 'image-record-1',
  conversationId: 'conversation-1',
  type: 'image',
  url: '/api/files/images/one.png',
  prompt: '一只龙',
  model: 'seedream',
  status: 'completed',
  taskId: null,
  error: null,
  createdAt: '2026-10-10T00:00:00.000Z',
  resultUrls: '["/api/files/images/one.png","/api/files/images/two.png"]',
};

const singleImageAsset: AgentAsset = {
  ...multiImageAsset,
  id: 'image-record-2',
  url: '/api/files/images/solo.png',
  resultUrls: '["/api/files/images/solo.png"]',
};

const videoAsset: AgentAsset = {
  ...multiImageAsset,
  id: 'video-record-1',
  type: 'video',
  url: '/api/files/videos/clip.mp4',
  resultUrls: '["/api/files/videos/clip.mp4"]',
};

function renderGrid(records: AgentAsset[]) {
  const loadAssets = vi.fn().mockResolvedValue(undefined);
  useAgentStore.setState({
    activeConversationId: 'conversation-1',
    assets: { records, total: records.length, page: 1, size: 20 },
    loadAssets,
  });
  render(
    <MemoryRouter>
      <AgentAssetsModal open onClose={vi.fn()} />
    </MemoryRouter>,
  );
  return { loadAssets };
}

/** 会话资产网格里的媒体格子（不是文档行）。 */
function mediaTiles(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>('.agent-asset-tile.is-media'));
}

describe('AgentAssetsPanel asset tiles', () => {
  afterEach(() => {
    cleanup();
    useAgentStore.setState({
      assets: null,
      activeConversationId: null,
      loadAssets: useAgentStore.getInitialState().loadAssets,
    });
    vi.restoreAllMocks();
  });

  it('no longer shows a 设为参考 button on tiles', () => {
    renderGrid([multiImageAsset]);

    expect(screen.queryByRole('button', { name: '设为参考' })).toBeNull();
    expect(screen.queryByText('设为参考')).toBeNull();
  });

  it('keeps the asset tiles as the grid children so the entrance animation still applies', () => {
    renderGrid([multiImageAsset]);

    const grid = document.querySelector('.agent-assets-grid')!;
    expect(Array.from(grid.children).every((child) => child.classList.contains('agent-asset-tile'))).toBe(true);
  });

  it('renders one tile per result url', () => {
    renderGrid([multiImageAsset]);
    expect(mediaTiles()).toHaveLength(2);
  });

  it('opens a five-item menu on right click', () => {
    renderGrid([multiImageAsset]);

    fireEvent.contextMenu(mediaTiles()[0], { clientX: 120, clientY: 200 });

    for (const label of ['下载', '复制', '设为参考', '去画布编辑', '删除']) {
      expect(screen.getByRole('button', { name: label })).toBeTruthy();
    }
  });

  it('omits 去画布编辑 for documents but keeps the rest of the menu', () => {
    renderGrid([documentAsset]);
    fireEvent.click(screen.getByRole('button', { name: /文档/ }));

    fireEvent.contextMenu(screen.getByRole('button', { name: /打开 quarterly.md/ }), {
      clientX: 120,
      clientY: 200,
    });

    expect(screen.getByRole('button', { name: '删除' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '去画布编辑' })).toBeNull();
  });

  it('opens the menu from the keyboard for media tiles', () => {
    renderGrid([multiImageAsset]);

    fireEvent.keyDown(mediaTiles()[0], { key: 'ContextMenu' });

    expect(screen.getByRole('button', { name: '删除' })).toBeTruthy();
  });

  it('renders video tiles under the video tab with the same menu', () => {
    renderGrid([videoAsset]);
    fireEvent.click(screen.getByRole('button', { name: /视频/ }));

    expect(mediaTiles()).toHaveLength(1);
    fireEvent.contextMenu(mediaTiles()[0], { clientX: 120, clientY: 200 });
    expect(screen.getByRole('button', { name: '去画布编辑' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '删除' })).toBeTruthy();
  });

  it('asks for confirmation before deleting and then deletes only the clicked result', async () => {
    const deleteAsset = vi
      .spyOn(agentApi, 'deleteAsset')
      .mockResolvedValue({ data: { code: 0, message: '', data: null } } as never);
    const { loadAssets } = renderGrid([multiImageAsset]);

    // 右键第二张：下标必须是 1，只删这一张，不能整条记录一起删。
    fireEvent.contextMenu(mediaTiles()[1], { clientX: 120, clientY: 200 });
    fireEvent.click(screen.getByRole('button', { name: '删除' }));

    expect(screen.getByRole('alertdialog')).toBeTruthy();
    expect(deleteAsset).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '删除' }));

    await waitFor(() => expect(deleteAsset).toHaveBeenCalledWith('image-record-1', 1));
    await waitFor(() => expect(loadAssets).toHaveBeenCalled());
  });

  it('deletes the whole record when it only holds one result', async () => {
    const deleteAsset = vi
      .spyOn(agentApi, 'deleteAsset')
      .mockResolvedValue({ data: { code: 0, message: '', data: null } } as never);
    renderGrid([singleImageAsset]);

    fireEvent.contextMenu(mediaTiles()[0], { clientX: 120, clientY: 200 });
    fireEvent.click(screen.getByRole('button', { name: '删除' }));
    fireEvent.click(screen.getByRole('button', { name: '删除' }));

    await waitFor(() => expect(deleteAsset).toHaveBeenCalledWith('image-record-2', undefined));
  });
});
