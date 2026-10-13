// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AssetDrawer, LIBRARY_ASSET_DRAG_MIME } from './AssetDrawer';
import { loadCanvasProjectLibrary } from '../services/canvasProjectLibrary';

vi.mock('../services/canvasProjectLibrary', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/canvasProjectLibrary')>();
  return { ...actual, loadCanvasProjectLibrary: vi.fn() };
});

const folder = {
  id: 'folder-1', parentId: null, name: '人物', scope: 'PROJECT' as const,
  ownerProjectId: 'p1', directAssetCount: 0, descendantAssetCount: 2, version: 1,
};

const asset = (id: string, name: string, sourceType: string, scope: 'PROJECT' | 'GLOBAL') => ({
  id,
  scope,
  ownerProjectId: scope === 'PROJECT' ? 'p1' : null,
  type: 'IMAGE' as const,
  aiKind: null,
  name,
  description: null,
  coverUrl: null,
  sourceType,
  sourceId: null,
  status: 'ACTIVE',
});

describe('AssetDrawer', () => {
  afterEach(() => {
    cleanup();
    vi.mocked(loadCanvasProjectLibrary).mockReset();
  });

  it('shows folders, canvas assets, global assets, and asset sets', async () => {
    vi.mocked(loadCanvasProjectLibrary).mockResolvedValue({
      projectTree: { scope: 'PROJECT', ownerProjectId: 'p1', folders: [folder], unfiledAssetCount: 1 },
      globalTree: { scope: 'GLOBAL', ownerProjectId: null, folders: [], unfiledAssetCount: 1 },
      assets: [
        asset('a1', '画布成品', 'canvas-generation', 'PROJECT'),
        asset('a2', '共享参考', 'upload', 'GLOBAL'),
      ],
      groups: [{
        id: 'g1', scope: 'PROJECT', ownerProjectId: 'p1', type: 'GROUP',
        aiKind: 'CHARACTER', name: '主角集', description: null, coverUrl: null,
        sourceType: null, sourceId: null, status: 'ACTIVE',
      }],
    });

    render(<AssetDrawer projectId="p1" canvasId="c1" onClose={() => undefined} />);

    expect((await screen.findAllByText('人物')).length).toBeGreaterThan(0);
    expect(screen.getByText('画布成品')).toBeTruthy();
    expect(screen.getByText('共享参考')).toBeTruthy();
    expect(screen.getByText('主角集')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '项目画布素材' }));
    expect(screen.queryByText('共享参考')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '资产集' }));
    expect(screen.getByTitle('双击打开文件夹')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '全部' }));
    fireEvent.doubleClick(screen.getByTitle('双击打开文件夹'));
    await waitFor(() => expect(loadCanvasProjectLibrary).toHaveBeenLastCalledWith(
      expect.objectContaining({ scope: 'PROJECT', folderId: 'folder-1' }),
    ));
  });

  it('multi-selects library assets and hands the chosen ids to the upload callback', async () => {
    vi.mocked(loadCanvasProjectLibrary).mockResolvedValue({
      projectTree: { scope: 'PROJECT', ownerProjectId: 'p1', folders: [], unfiledAssetCount: 3 },
      globalTree: { scope: 'GLOBAL', ownerProjectId: null, folders: [], unfiledAssetCount: 0 },
      assets: [
        asset('image-1', '参考图', 'upload', 'PROJECT'),
        { ...asset('video-1', '参考视频', 'upload', 'PROJECT'), type: 'VIDEO' as const },
        { ...asset('doc-1', '说明文档', 'upload', 'PROJECT'), type: 'DOCUMENT' as const },
      ],
      groups: [],
    });
    const onConfirmUpload = vi.fn();

    render(
      <AssetDrawer
        projectId="p1"
        canvasId="c1"
        selecting
        onConfirmUpload={onConfirmUpload}
        onClose={() => undefined}
      />,
    );

    // 非图片 / 视频素材在多选模式下不可选
    const document = await screen.findByRole('checkbox', { name: '选择素材 说明文档' });
    expect(document.getAttribute('aria-disabled')).toBe('true');
    expect(screen.getByRole('button', { name: '上传到画布' }).hasAttribute('disabled')).toBe(true);

    const image = screen.getByRole('checkbox', { name: '选择素材 参考图' });
    fireEvent.click(image);
    fireEvent.click(screen.getByRole('checkbox', { name: '选择素材 参考视频' }));
    fireEvent.click(document);

    expect(image.getAttribute('aria-checked')).toBe('true');
    expect(screen.getByText('已选 2 项')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '上传到画布' }));
    expect(onConfirmUpload).toHaveBeenCalledWith(['image-1', 'video-1']);
  });

  it('marks images, videos, and asset sets as draggable with their library type', async () => {
    vi.mocked(loadCanvasProjectLibrary).mockResolvedValue({
      projectTree: { scope: 'PROJECT', ownerProjectId: 'p1', folders: [], unfiledAssetCount: 3 },
      globalTree: { scope: 'GLOBAL', ownerProjectId: null, folders: [], unfiledAssetCount: 0 },
      assets: [
        asset('image-1', '参考图', 'upload', 'PROJECT'),
        { ...asset('video-1', '参考视频', 'upload', 'PROJECT'), type: 'VIDEO' as const },
      ],
      groups: [{
        id: 'group-1', scope: 'PROJECT', ownerProjectId: 'p1', type: 'GROUP',
        aiKind: 'CHARACTER', name: '主角集', description: null, coverUrl: null,
        sourceType: null, sourceId: null, status: 'ACTIVE',
      }],
    });

    render(<AssetDrawer projectId="p1" canvasId="c1" onClose={() => undefined} />);

    const imageCard = (await screen.findByText('参考图')).closest('[draggable="true"]');
    const videoCard = screen.getByText('参考视频').closest('[draggable="true"]');
    const groupCard = screen.getByText('主角集').closest('[draggable="true"]');
    expect(imageCard).not.toBeNull();
    expect(videoCard).not.toBeNull();
    expect(groupCard).not.toBeNull();

    const imageTransfer = { setData: vi.fn(), effectAllowed: '' };
    fireEvent.dragStart(imageCard!, { dataTransfer: imageTransfer });
    expect(imageTransfer.setData).toHaveBeenCalledWith(
      LIBRARY_ASSET_DRAG_MIME,
      JSON.stringify({ libraryAssetId: 'image-1', type: 'image', name: '参考图' }),
    );

    const groupTransfer = { setData: vi.fn(), effectAllowed: '' };
    fireEvent.dragStart(groupCard!, { dataTransfer: groupTransfer });
    expect(groupTransfer.setData).toHaveBeenCalledWith(
      LIBRARY_ASSET_DRAG_MIME,
      JSON.stringify({ libraryAssetId: 'group-1', type: 'group', name: '主角集' }),
    );
  });

  it('renders a video preview instead of treating the video URL as an image', async () => {
    vi.mocked(loadCanvasProjectLibrary).mockResolvedValue({
      projectTree: { scope: 'PROJECT', ownerProjectId: 'p1', folders: [], unfiledAssetCount: 1 },
      globalTree: { scope: 'GLOBAL', ownerProjectId: null, folders: [], unfiledAssetCount: 0 },
      assets: [{
        ...asset('video-1', '参考视频', 'canvas-generation', 'PROJECT'),
        type: 'VIDEO' as const,
        coverUrl: '/api/files/videos/reference.mp4',
      }],
      groups: [],
    });

    render(<AssetDrawer projectId="p1" canvasId="c1" onClose={() => undefined} />);

    const card = (await screen.findByText('参考视频')).closest('[draggable="true"]');
    const preview = card?.querySelector('video');
    expect(preview).not.toBeNull();
    expect(preview?.getAttribute('src')).toBe('/api/files/videos/reference.mp4#t=0.1');
  });
});
