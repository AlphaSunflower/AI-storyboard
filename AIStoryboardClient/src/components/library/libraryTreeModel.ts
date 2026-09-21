import type { LibraryAsset, LibraryFolderNode } from '../../api/library';

export interface LibraryFolderTreeNode extends LibraryFolderNode {
  children: LibraryFolderTreeNode[];
}

export function buildFolderTree(folders: LibraryFolderNode[]): LibraryFolderTreeNode[] {
  const byId = new Map<string, LibraryFolderTreeNode>();
  folders.forEach((folder) => byId.set(folder.id, { ...folder, children: [] }));
  const roots: LibraryFolderTreeNode[] = [];
  folders.forEach((folder) => {
    const node = byId.get(folder.id);
    if (!node) return;
    const parent = folder.parentId ? byId.get(folder.parentId) : undefined;
    if (parent) parent.children.push(node);
    else roots.push(node);
  });
  return roots;
}

export function selectionForContextMenu(selectedIds: string[], clickedId: string): string[] {
  return selectedIds.includes(clickedId) ? selectedIds : [clickedId];
}

export function canMergeIntoGroup(assets: LibraryAsset[]): boolean {
  if (assets.length === 0) return false;
  return assets.every((asset) => asset.status === 'ACTIVE'
    && (asset.type === 'IMAGE' || asset.type === 'VIDEO' || asset.type === 'AUDIO'));
}
