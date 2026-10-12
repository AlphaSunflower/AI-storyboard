import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { LibraryAsset, LibraryAssetDetail, LibraryMedia, LibraryTag } from '../../api/library';
import { LibraryInspector } from './LibraryInspector';

const asset: LibraryAsset = {
  id: 'a1',
  ownerUserId: 'u1',
  scope: 'PROJECT',
  ownerProjectId: 'p1',
  type: 'IMAGE',
  name: '视角 1',
  description: '测试素材',
  aiDescription: null,
  manualOverride: false,
  metadataState: 'AI_GENERATED',
  status: 'ACTIVE',
  coverUrl: null,
  version: 1,
  tags: [],
  projects: [],
  folderId: null,
  folderPath: [],
  sourceType: 'upload',
  sourceId: null,
  createdAt: '',
  updatedAt: '',
};

const tag: LibraryTag = { id: 't1', name: '3D建模' };

afterEach(cleanup);

function renderInspector(
  onToggleTag = vi.fn(),
  assetOverrides: Partial<LibraryAsset> = {},
  media: LibraryMedia[] = [],
) {
  const currentAsset = { ...asset, ...assetOverrides };
  const detail: LibraryAssetDetail = { asset: currentAsset, media, linkedProjects: [] };
  render(
    <LibraryInspector
      mode="asset"
      detail={detail}
      selectedAssets={[currentAsset]}
      tags={[tag]}
      onEdit={vi.fn()}
      onTrash={vi.fn()}
      onPromote={vi.fn()}
      onAddToGroup={vi.fn()}
      onUseForVideo={vi.fn()}
      onToggleTag={onToggleTag}
      onCreateSubfolder={vi.fn()}
      onRenameFolder={vi.fn()}
      onDeleteFolder={vi.fn()}
      onOpenFolder={vi.fn()}
      onBulkTrash={vi.fn()}
      onBulkPromote={vi.fn()}
      onBulkMove={vi.fn()}
      onBulkMerge={vi.fn()}
    />,
  );
  return onToggleTag;
}

describe('LibraryInspector tags', () => {
  it('keeps tags read-only until edit mode is enabled', () => {
    const onToggleTag = renderInspector();
    const tagButton = screen.getByRole('button', { name: '3D建模' });

    expect((tagButton as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(tagButton);
    expect(onToggleTag).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('switch'));
    expect((tagButton as HTMLButtonElement).disabled).toBe(false);

    fireEvent.click(tagButton);
    expect(onToggleTag).toHaveBeenCalledWith(asset, tag);
  });

  it('shows an explicit AI generation state while the description is pending', () => {
    renderInspector(vi.fn(), {
      description: null,
      aiDescription: null,
      metadataState: 'AI_GENERATING',
    });

    expect(screen.getByText('AI 生成中')).toBeTruthy();
    expect(screen.getByText('AI 正在分析图片，完成后会自动填入描述与标签。')).toBeTruthy();
  });

  it('marks manually edited metadata as edited', () => {
    renderInspector(vi.fn(), { metadataState: 'EDITED' });

    expect(screen.getByText('已编辑')).toBeTruthy();
  });

  it('shows the AI description instead of the generation placeholder', () => {
    renderInspector(vi.fn(), {
      description: 'Moon 生成图片',
      aiDescription: '弧形木格栅空间中的绿色螺旋玻璃装置。',
      metadataState: 'AI_GENERATED',
    });

    expect(screen.getByText('弧形木格栅空间中的绿色螺旋玻璃装置。')).toBeTruthy();
    expect(screen.queryByText('Moon 生成图片')).toBeNull();
  });

  it('keeps manually edited description ahead of the previous AI description', () => {
    renderInspector(vi.fn(), {
      description: '手动修正后的描述',
      aiDescription: '旧的 AI 描述',
      metadataState: 'EDITED',
    });

    expect(screen.getByText('手动修正后的描述')).toBeTruthy();
    expect(screen.queryByText('旧的 AI 描述')).toBeNull();
  });

  it('offers download and hides group actions for documents', () => {
    renderInspector(
      vi.fn(),
      { type: 'DOCUMENT', metadataState: 'NONE', description: null, aiDescription: null },
      [{
        id: 'm1',
        blobId: 'b1',
        type: 'DOCUMENT',
        url: '/api/files/documents/a.md',
        contentUrl: '/api/library/media/m1/content?token=t',
        objectKey: 'documents/a.md',
        fileName: '说明.md',
        relativePath: null,
        mimeType: 'text/markdown',
        sizeBytes: 12,
        width: null,
        height: null,
        durationSeconds: null,
        sortOrder: 0,
        role: 'REFERENCE',
        primary: true,
        constraintText: null,
      }],
    );

    expect(screen.queryByRole('button', { name: '加入资产组' })).toBeNull();
    expect(screen.getByRole('link', { name: '下载文件' })).toBeTruthy();
    expect(screen.getByText('暂无描述')).toBeTruthy();
  });
});
