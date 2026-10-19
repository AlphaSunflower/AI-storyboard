import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { LibraryCommandPalette, type LibraryCommandAction } from './LibraryCommandPalette';
import type { LibraryAsset, LibrarySavedView } from '../../api/library';

const asset = {
  id: 'asset-1',
  ownerUserId: 'user-1',
  scope: 'PROJECT',
  ownerProjectId: 'p1',
  type: 'IMAGE',
  aiKind: null,
  name: '主角.png',
  description: null,
  aiDescription: '红衣主角',
  manualOverride: false,
  metadataState: 'NONE',
  status: 'ACTIVE',
  coverUrl: '/api/files/images/a.png',
  version: 1,
  tags: [],
  projects: [],
  folderId: null,
  folderPath: ['角色'],
  sourceType: 'upload',
  sourceId: null,
  createdAt: '',
  updatedAt: '',
} as LibraryAsset;

const view = {
  id: 'view-1',
  ownerUserId: 'user-1',
  scope: 'PROJECT',
  ownerProjectId: 'p1',
  name: '四星精选',
  filters: {},
  sort: {},
  viewMode: 'folders',
  pinned: false,
  createdAt: '',
  updatedAt: '',
} as LibrarySavedView;

describe('LibraryCommandPalette', () => {
  it('filters actions and runs the active item with Enter', () => {
    const run = vi.fn();
    const actions: LibraryCommandAction[] = [
      { id: 'upload', label: '上传素材', run },
      { id: 'trash', label: '去回收站', run: vi.fn() },
    ];
    render(
      <LibraryCommandPalette
        open
        onClose={vi.fn()}
        actions={actions}
        assets={[asset]}
        views={[view]}
        onOpenAsset={vi.fn()}
        onApplyView={vi.fn()}
      />,
    );

    const input = screen.getByRole('textbox', { name: '搜索素材、视图或动作' });
    fireEvent.change(input, { target: { value: '上传' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(run).toHaveBeenCalledTimes(1);
  });

  it('opens an asset result', () => {
    const onOpenAsset = vi.fn();
    render(
      <LibraryCommandPalette
        open
        onClose={vi.fn()}
        actions={[]}
        assets={[asset]}
        views={[]}
        onOpenAsset={onOpenAsset}
        onApplyView={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('option', { name: /主角\.png/ }));

    expect(onOpenAsset).toHaveBeenCalledWith(asset);
  });
});
