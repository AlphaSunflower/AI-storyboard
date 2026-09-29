import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type DragEvent as ReactDragEvent,
  type FormEvent,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Boxes,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  CircleAlert,
  ClipboardPaste,
  Copy,
  Folder,
  FolderMinus,
  FolderPlus,
  Images,
  LayoutGrid,
  ListChecks,
  ListTree,
  Package,
  Pencil,
  Plus,
  RotateCcw,
  Scissors,
  Search,
  Sparkles,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import {
  libraryApi,
  type AssetGroupSnapshot,
  type AssetScope,
  type AssetType,
  type ImportEntry,
  type LibraryAsset,
  type LibraryAssetDetail,
  type LibraryFolderNode,
  type LibraryGroupMediaItem,
  type LibraryTag,
  type MetadataState,
} from '../api/library';
import { useProjectStore, getLastOpenedProjectId } from '../stores/projectStore';
import { useLibraryTaskStore } from '../stores/libraryTaskStore';
import {
  LibraryActionMenu,
  type LibraryActionItem,
} from '../components/library/LibraryActionMenu';
import {
  LibraryTree,
  type LibraryTreeNodeDatum,
} from '../components/library/LibraryTree';
import {
  AuthenticatedPreviewImage,
  LibraryInspector,
} from '../components/library/LibraryInspector';
import {
  buildFolderTree,
  canMergeIntoGroup,
  selectionForContextMenu,
  type LibraryFolderTreeNode,
} from '../components/library/libraryTreeModel';
import {
  buildLibraryQuery,
  resolveLibraryProjectId,
  scopeFromQuery,
} from '../lib/libraryFilters';
import { EMBEDDED_APP_READY_MESSAGE } from '../lib/embeddedAppMessages';
import {
  CHAT_MEDIA_DRAG_MIME,
  PROJECT_LIBRARY_DRAG_MIME,
  buildProjectLibraryDragPayload,
  parseChatMediaDragPayload,
  serializeDragPayload,
} from '../lib/projectLibraryDrag';
import { importMediaToProjectLibrary } from '../lib/projectLibraryImport';
import { assetUrl } from '../config';
import './LibraryWorkspacePage.css';

const PAGE_SIZE = 100;
const INTERNAL_LIBRARY_DRAG_TYPE = 'application/x-moon-library-assets';
const INTERNAL_FOLDER_DRAG_TYPE = 'application/x-moon-library-folder';
type ScopeView = 'ALL' | 'GLOBAL' | 'TRASH';
type LibraryViewMode = 'folders' | 'tree';
const METADATA_LABEL: Record<MetadataState, string> = {
  NONE: '未分析',
  AI_GENERATING: 'AI 生成中',
  AI_GENERATED: 'AI 生成',
  AI_FAILED: 'AI 分析失败',
  EDITED: '已编辑',
};

function editableAssetDescription(asset: LibraryAsset): string {
  return asset.metadataState === 'EDITED'
    ? asset.description || asset.aiDescription || ''
    : asset.aiDescription || asset.description || '';
}

type PromptState =
  | { kind: 'folder'; title: string; value: string; parentId?: string | null }
  | { kind: 'rename-folder'; title: string; value: string; folder: LibraryFolderNode }
  | { kind: 'edit-asset'; title: string; value: string; description: string; asset: LibraryAsset }
  | { kind: 'rename-asset'; title: string; value: string; asset: LibraryAsset }
  | {
    kind: 'edit-group-media';
    title: string;
    value: string;
    description: string;
    groupMedia: LibraryGroupMediaItem;
  }
  | { kind: 'create-group'; title: string; value: string }
  | { kind: 'merge-group'; title: string; value: string; assetIds: string[] };

interface MenuState {
  open: boolean;
  x: number;
  y: number;
  surface?: 'blank' | 'item';
  node?: LibraryTreeNodeDatum;
  groupMedia?: LibraryGroupMediaItem;
}

interface ExternalUploadItem {
  file: File;
  relativePath: string;
}

interface PendingExternalDrop {
  kind: 'files' | 'folder';
  items: ExternalUploadItem[];
}

interface LibraryConfirmation {
  title: string;
  message: string;
  confirmLabel: string;
  danger?: boolean;
  onConfirm: () => void | Promise<void>;
}

export function LibraryWorkspacePage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const currentProject = useProjectStore((state) => state.currentProject);
  const projects = useProjectStore((state) => state.projects);
  const loadProjects = useProjectStore((state) => state.loadProjects);
  const loadProject = useProjectStore((state) => state.loadProject);
  const [scopeView, setScopeView] = useState<ScopeView>(() => {
    const initial = scopeFromQuery(searchParams.get('scope'));
    return initial === 'PROJECT' || initial === 'GLOBAL' ? 'ALL' : initial;
  });
  const [viewMode, setViewMode] = useState<LibraryViewMode>('folders');
  const [selectedProjectId, setSelectedProjectId] = useState(searchParams.get('projectId') || '');
  const [type, setType] = useState<AssetType | ''>(() => parseAssetType(searchParams.get('type')));
  const [query, setQuery] = useState(searchParams.get('q') || '');
  const [tags, setTags] = useState<LibraryTag[]>([]);
  const [selectedTagIds, setSelectedTagIds] = useState<string[]>([]);
  const [tagsExpanded, setTagsExpanded] = useState(false);
  const [folderTree, setFolderTree] = useState<LibraryFolderNode[]>([]);
  const [unfiledCount, setUnfiledCount] = useState(0);
  const [groups, setGroups] = useState<LibraryAsset[]>([]);
  const [groupItemsByGroup, setGroupItemsByGroup] = useState<Record<string, LibraryGroupMediaItem[]>>({});
  const [loadingGroupIds, setLoadingGroupIds] = useState<Set<string>>(new Set());
  const [assetsByFolder, setAssetsByFolder] = useState<Record<string, LibraryAsset[]>>({});
  const [loadedFolders, setLoadedFolders] = useState<Set<string>>(new Set());
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(['root', 'folders', 'groups']));
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [selectedFolderIds, setSelectedFolderIds] = useState<string[]>([]);
  const [activeFolderId, setActiveFolderId] = useState<string | null>(null);
  const [detail, setDetail] = useState<LibraryAssetDetail | null>(null);
  const [activeGroup, setActiveGroup] = useState<LibraryAsset | null>(null);
  const [groupMediaItems, setGroupMediaItems] = useState<LibraryGroupMediaItem[]>([]);
  const [selectedGroupMediaId, setSelectedGroupMediaId] = useState<string | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [menu, setMenu] = useState<MenuState>({ open: false, x: 0, y: 0 });
  const [prompt, setPrompt] = useState<PromptState | null>(null);
  const [moveOpen, setMoveOpen] = useState(false);
  const [addToGroupAsset, setAddToGroupAsset] = useState<LibraryAsset | null>(null);
  const [trashAssets, setTrashAssets] = useState<LibraryAsset[]>([]);
  const [clipboardIds, setClipboardIds] = useState<string[]>([]);
  const [clipboardFolderIds, setClipboardFolderIds] = useState<string[]>([]);
  const [clipboardMode, setClipboardMode] = useState<'copy' | 'cut'>('copy');
  const [dragOverFolderId, setDragOverFolderId] = useState<string | null>(null);
  const [selectionBox, setSelectionBox] = useState<{
    x: number;
    y: number;
    width: number;
    height: number;
  } | null>(null);
  const [externalDragActive, setExternalDragActive] = useState(false);
  const [internalDragActive, setInternalDragActive] = useState(false);
  const [pendingExternalDrop, setPendingExternalDrop] = useState<PendingExternalDrop | null>(null);
  const [confirmation, setConfirmation] = useState<LibraryConfirmation | null>(null);
  const [inspectorDismissed, setInspectorDismissed] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const folderInput = useRef<HTMLInputElement>(null);
  const folderGridRef = useRef<HTMLElement>(null);
  const initializedProject = useRef(false);
  const selectionAnchor = useRef<string | null>(null);
  const selectionStart = useRef<{ x: number; y: number } | null>(null);
  const selectionAdditive = useRef(false);
  const selectionBaseAssets = useRef<string[]>([]);
  const selectionBaseFolders = useRef<string[]>([]);
  const dragIds = useRef<string[]>([]);
  const draggedFolderId = useRef<string | null>(null);
  const groupItemsRequest = useRef(new Map<string, Promise<LibraryGroupMediaItem[]>>());
  const upsertTask = useLibraryTaskStore((state) => state.upsertTask);
  const updateTask = useLibraryTaskStore((state) => state.updateTask);
  const addAnalysisTask = useLibraryTaskStore((state) => state.addAnalysisTask);
  const panelOpen = useLibraryTaskStore((state) => state.panelOpen);
  const setPanelOpen = useLibraryTaskStore((state) => state.setPanelOpen);
  const tasks = useLibraryTaskStore((state) => state.tasks);
  const isEmbedded = searchParams.get('embedded') === '1';

  useEffect(() => {
    if (!isEmbedded || window.parent === window) return;
    window.parent.postMessage({
      type: EMBEDDED_APP_READY_MESSAGE,
      key: 'library',
    }, window.location.origin);
  }, [isEmbedded]);

  useEffect(() => {
    if (projects.length === 0) void loadProjects().catch(() => undefined);
  }, [loadProjects, projects.length]);

  useEffect(() => {
    if (initializedProject.current || projects.length === 0) return;
    initializedProject.current = true;
    const target = resolveLibraryProjectId({
      requestedProjectId: selectedProjectId,
      currentProjectId: currentProject?.id,
      lastOpenedProjectId: getLastOpenedProjectId(),
      projects,
    });
    if (!target) return;
    setSelectedProjectId(target);
    if (currentProject?.id !== target) void loadProject(target).catch(() => undefined);
  }, [currentProject?.id, loadProject, projects, selectedProjectId]);

  const effectiveProjectId = selectedProjectId || currentProject?.id || '';
  const isTrash = scopeView === 'TRASH';
  const queryScope: AssetScope | undefined = scopeView === 'GLOBAL' ? 'GLOBAL' : undefined;
  const projectForTree = queryScope === 'GLOBAL' || isTrash ? undefined : effectiveProjectId;
  const effectiveType = type || undefined;

  const reloadTree = useCallback(async () => {
    if (isTrash) {
      const response = await libraryApi.listAssets({ deleted: true, page: 1, size: PAGE_SIZE });
      setTrashAssets(response.data.data.records || []);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [tagResponse, folderResponse, groupResponse] = await Promise.all([
        libraryApi.listTags(),
        libraryApi.getFolderTree({
          scope: queryScope || 'PROJECT',
          projectId: projectForTree,
        }),
        libraryApi.listAssets({
          scope: queryScope,
          projectId: projectForTree,
          type: 'GROUP',
          page: 1,
          size: PAGE_SIZE,
        }),
      ]);
      setTags(tagResponse.data.data || []);
      setTagsExpanded(false);
      setFolderTree(folderResponse.data.data.folders || []);
      setUnfiledCount(folderResponse.data.data.unfiledAssetCount || 0);
      setGroups(groupResponse.data.data.records || []);
      setAssetsByFolder({});
      setLoadedFolders(new Set());
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载项目库失败');
    } finally {
      setLoading(false);
    }
  }, [isTrash, projectForTree, queryScope]);

  useEffect(() => {
    void reloadTree();
  }, [reloadTree]);

  useEffect(() => {
    if (scopeView !== 'GLOBAL' && scopeView !== 'TRASH' && !effectiveProjectId) return;
    const params = buildLibraryQuery({
      scopeView,
      projectId: scopeView === 'GLOBAL' || scopeView === 'TRASH' ? undefined : effectiveProjectId,
      type,
      query,
      tagIds: selectedTagIds,
      tagMode: 'ALL',
    });
    if (isEmbedded) params.set('embedded', '1');
    setSearchParams(params, { replace: true });
  }, [effectiveProjectId, isEmbedded, query, scopeView, selectedTagIds, setSearchParams, type]);

  useEffect(() => {
    setSelectedIds([]);
    setSelectedFolderIds([]);
    setActiveFolderId(null);
    setDetail(null);
    setActiveGroup(null);
    setGroupMediaItems([]);
    setGroupItemsByGroup({});
    setLoadingGroupIds(new Set());
    groupItemsRequest.current.clear();
    setSelectedGroupMediaId(null);
    setInspectorDismissed(false);
  }, [effectiveProjectId, query, scopeView, selectedTagIds, type]);

  const filteredMode = Boolean(query.trim() || type || selectedTagIds.length);
  const visibleTags = tagsExpanded ? tags : tags.slice(0, 10);

  const loadFolder = useCallback(async (folderId: string, page = 1, force = false) => {
    if (!force && page === 1 && loadedFolders.has(folderId) && !filteredMode) return;
    setError('');
    const response = await libraryApi.listAssets({
      scope: queryScope || 'PROJECT',
      projectId: projectForTree,
      folderId,
      q: query.trim() || undefined,
      type: effectiveType,
      tagIds: selectedTagIds.length ? selectedTagIds : undefined,
      tagMode: 'ALL',
      page,
      size: PAGE_SIZE,
    });
    const records = response.data.data.records || [];
    setAssetsByFolder((current) => ({
      ...current,
      [folderId]: page === 1 ? records : dedupeAssets([...(current[folderId] || []), ...records]),
    }));
    setLoadedFolders((current) => new Set(current).add(folderId));
  }, [
    effectiveType,
    filteredMode,
    loadedFolders,
    projectForTree,
    query,
    queryScope,
    selectedTagIds,
  ]);

  const loadFilteredAssets = useCallback(async () => {
    if (!filteredMode || isTrash) return;
    const response = await libraryApi.listAssets({
      scope: queryScope,
      projectId: projectForTree,
      q: query.trim() || undefined,
      type: effectiveType,
      tagIds: selectedTagIds.length ? selectedTagIds : undefined,
      tagMode: 'ALL',
      page: 1,
      size: PAGE_SIZE,
    });
    const grouped: Record<string, LibraryAsset[]> = {};
    (response.data.data.records || []).forEach((asset) => {
      const key = asset.folderId || 'UNFILED';
      grouped[key] = [...(grouped[key] || []), asset];
    });
    setAssetsByFolder(grouped);
    setLoadedFolders(new Set(Object.keys(grouped)));
    setExpanded((current) => {
      const nextExpanded = new Set(current);
      nextExpanded.add('root');
      nextExpanded.add('folders');
      Object.values(grouped).flat().forEach((asset) => {
        asset.folderPath.forEach((_, index) => {
          const ancestor = folderAtPath(folderTree, asset.folderPath.slice(0, index + 1));
          if (ancestor) nextExpanded.add(`folder:${ancestor.id}`);
        });
      });
      return nextExpanded;
    });
  }, [
    effectiveType,
    filteredMode,
    folderTree,
    isTrash,
    projectForTree,
    query,
    queryScope,
    selectedTagIds,
  ]);

  useEffect(() => {
    if (filteredMode) void loadFilteredAssets();
  }, [filteredMode, loadFilteredAssets]);

  const allKnownAssets = useMemo(() => {
    const map = new Map<string, LibraryAsset>();
    Object.values(assetsByFolder).flat().forEach((asset) => map.set(asset.id, asset));
    groups.forEach((group) => map.set(group.id, group));
    return map;
  }, [assetsByFolder, groups]);

  const selectedAssets = useMemo(() => {
    if (isTrash) {
      return trashAssets.filter((asset) => selectedIds.includes(asset.id));
    }
    return selectedIds
      .map((id) => allKnownAssets.get(id))
      .filter((asset): asset is LibraryAsset => Boolean(asset));
  }, [allKnownAssets, isTrash, selectedIds, trashAssets]);

  const folderNodes = useMemo(
    () => buildFolderTree(folderTree).map((folder) => folderToTreeNode(
      folder,
      expanded,
      assetsByFolder,
      loadedFolders,
      filteredMode,
    )),
    [assetsByFolder, expanded, filteredMode, folderTree, loadedFolders],
  );

  const treeData = useMemo<LibraryTreeNodeDatum>(() => ({
    id: 'root',
    kind: 'project',
    name: '项目库',
    meta: '项目素材与共享素材',
    expanded: expanded.has('root'),
    hasChildren: true,
    children: expanded.has('root') ? [
      {
        id: 'folders',
        kind: 'system',
        name: '项目目录',
        meta: `${currentProject?.name || '选择项目'} · ${folderTree.length} 个目录`,
        expanded: expanded.has('folders'),
        hasChildren: true,
        children: expanded.has('folders') ? [
          ...folderNodes,
          {
            id: 'unfiled',
            kind: 'folder',
            name: '未归档',
            meta: `${unfiledCount} 项`,
            expanded: expanded.has('unfiled'),
            hasChildren: unfiledCount > 0,
            children: expanded.has('unfiled')
              ? folderAssetChildren(
                  'UNFILED',
                  assetsByFolder,
                  loadedFolders,
                  !filteredMode && (assetsByFolder.UNFILED?.length || 0) < unfiledCount,
                )
              : undefined,
          },
        ] : undefined,
      },
      {
        id: 'groups',
        kind: 'system',
        name: '资产组',
        meta: `${groups.length} 组`,
        expanded: expanded.has('groups'),
        hasChildren: groups.length > 0,
        children: expanded.has('groups') ? groups.map((group) => {
          const groupId = `group:${group.id}`;
          const groupExpanded = expanded.has(groupId);
          const items = groupItemsByGroup[group.id];
          const groupAssets = items ? groupAssetsForTree(items) : [];
          const loadingGroup = loadingGroupIds.has(group.id);
          return {
            id: groupId,
            kind: 'group',
            name: group.name,
            meta: [
              group.scope === 'GLOBAL' ? '全局资产组' : '项目资产组',
              loadingGroup ? '加载中' : items ? `${groupAssets.length} 项资产` : '',
            ].filter(Boolean).join(' · '),
            expanded: groupExpanded,
            hasChildren: items ? groupAssets.length > 0 : true,
            children: groupExpanded && items ? groupAssets.map(({ asset, mediaCount }) => ({
              id: `asset:${asset.id}`,
              kind: 'asset',
              name: asset.name,
              meta: mediaCount > 1 ? `${asset.type} · ${mediaCount} 个文件` : asset.type,
              asset,
            })) : undefined,
            asset: group,
          };
        }) : undefined,
      },
    ] : undefined,
  }), [
    assetsByFolder,
    currentProject?.name,
    expanded,
    folderNodes,
    folderTree.length,
    groupItemsByGroup,
    groups,
    loadingGroupIds,
    loadedFolders,
    scopeView,
    unfiledCount,
    filteredMode,
  ]);

  const treeRevision = useMemo(
    () => treeDataHash(treeData),
    [treeData],
  );

  const toggleTreeNode = (nodeId: string) => {
    if (nodeId.startsWith('group:')) {
      const groupId = nodeId.slice('group:'.length);
      const next = new Set(expanded);
      if (next.has(nodeId)) {
        next.delete(nodeId);
      } else {
        next.add(nodeId);
        const group = groups.find((candidate) => candidate.id === groupId);
        if (group && !groupItemsByGroup[groupId]) {
          void loadGroupItems(group).catch((err) => {
            setError(err instanceof Error ? err.message : '加载资产组失败');
          });
        }
      }
      setExpanded(next);
      return;
    }
    const folderId = nodeId.startsWith('folder:')
      ? nodeId.slice('folder:'.length)
      : nodeId === 'unfiled' ? 'UNFILED' : nodeId;
    const next = new Set(expanded);
    if (next.has(nodeId)) {
      next.delete(nodeId);
    } else {
      next.add(nodeId);
      if (folderId === 'UNFILED' || folderTree.some((folder) => folder.id === folderId)) {
        void loadFolder(folderId).catch((err) => {
          setError(err instanceof Error ? err.message : '加载目录失败');
        });
      }
    }
    setExpanded(next);
  };

  const loadGroupItems = async (
    asset: LibraryAsset,
    force = false,
  ): Promise<LibraryGroupMediaItem[]> => {
    const cached = groupItemsByGroup[asset.id];
    if (!force && cached) return cached;

    const pending = groupItemsRequest.current.get(asset.id);
    if (pending) return pending;

    setLoadingGroupIds((current) => new Set(current).add(asset.id));
    const request = libraryApi.getGroup(asset.id).then((response) => response.data.data.media);
    groupItemsRequest.current.set(asset.id, request);
    try {
      const mediaItems = await request;
      setGroupItemsByGroup((current) => ({ ...current, [asset.id]: mediaItems }));
      return mediaItems;
    } finally {
      if (groupItemsRequest.current.get(asset.id) === request) {
        groupItemsRequest.current.delete(asset.id);
      }
      setLoadingGroupIds((current) => {
        const next = new Set(current);
        next.delete(asset.id);
        return next;
      });
    }
  };

  const loadGroupDetail = async (asset: LibraryAsset, force = false) => {
    setDetailLoading(true);
    setError('');
    try {
      const mediaItems = await loadGroupItems(asset, force);
      setGroupMediaItems(mediaItems);
      setSelectedGroupMediaId((current) => (
        current && mediaItems.some((item) => item.media.id === current)
          ? current
          : mediaItems[0]?.media.id || null
      ));
      setDetail({
        asset,
        media: mediaItems.map((item) => item.media),
        linkedProjects: asset.projects,
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载资产组失败');
    } finally {
      setDetailLoading(false);
    }
  };

  const openNode = async (node: LibraryTreeNodeDatum) => {
    setInspectorDismissed(false);
    if (node.kind === 'folder') {
      const folderId = node.id.startsWith('folder:') ? node.id.slice('folder:'.length) : node.id;
      setActiveFolderId(folderId === 'unfiled' ? 'UNFILED' : folderId);
      setActiveGroup(null);
      setDetail(null);
      setSelectedFolderIds([]);
      setGroupMediaItems([]);
      setSelectedGroupMediaId(null);
      return;
    }
    if (!node.asset) return;
    if (node.asset.type === 'GROUP') {
      setActiveFolderId(null);
      setActiveGroup(node.asset);
      setSelectedIds([node.asset.id]);
      setSelectedFolderIds([]);
      await loadGroupDetail(node.asset);
      return;
    }
    setActiveFolderId(node.asset.folderId || null);
    setActiveGroup(null);
    setGroupMediaItems([]);
    setSelectedGroupMediaId(null);
    setSelectedFolderIds([]);
    if (node.kind === 'asset') setSelectedIds([node.asset.id]);
    setDetailLoading(true);
    try {
      const response = await libraryApi.getAsset(node.asset.id);
      setDetail(response.data.data);
    } finally {
      setDetailLoading(false);
    }
  };

  const activeFolder = activeFolderId && activeFolderId !== 'UNFILED'
    ? folderTree.find((folder) => folder.id === activeFolderId) || null
    : null;
  const selectedFolder = selectedFolderIds.length === 1
    ? folderTree.find((folder) => folder.id === selectedFolderIds[0]) || null
    : null;
  const inspectorFolder = selectedFolder || activeFolder;
  const currentFolderId = activeFolderId || 'UNFILED';

  useEffect(() => {
    if (viewMode !== 'folders' || isTrash || loading || filteredMode) return;
    if (!effectiveProjectId && queryScope !== 'GLOBAL') return;
    if (loadedFolders.has(currentFolderId)) return;
    void loadFolder(currentFolderId).catch((err) => {
      setError(err instanceof Error ? err.message : '加载文件夹失败');
    });
  }, [
    currentFolderId,
    effectiveProjectId,
    filteredMode,
    isTrash,
    loadFolder,
    loadedFolders,
    loading,
    queryScope,
    viewMode,
  ]);

  const visibleFolders = useMemo(
    () => filteredMode
      ? []
      : folderTree.filter((folder) => folder.parentId === (activeFolderId === 'UNFILED' ? null : activeFolderId)),
    [activeFolderId, filteredMode, folderTree],
  );
  const visibleGroups = activeFolderId || filteredMode ? [] : groups;

  const currentFolderAssets = useMemo(() => {
    if (filteredMode) return Object.values(assetsByFolder).flat();
    return (assetsByFolder[currentFolderId] || []).filter((asset) => asset.type !== 'GROUP');
  }, [assetsByFolder, currentFolderId, filteredMode]);

  const visibleSelectableIds = useMemo(
    () => isTrash
      ? trashAssets.map((asset) => asset.id)
      : activeGroup
        ? []
        : [...visibleGroups.map((group) => group.id), ...currentFolderAssets.map((asset) => asset.id)],
    [activeGroup, currentFolderAssets, isTrash, trashAssets, visibleGroups],
  );
  const visibleSelectableFolderIds = useMemo(
    () => activeGroup ? [] : visibleFolders.map((folder) => folder.id),
    [activeGroup, visibleFolders],
  );

  const currentFolderTrail = useMemo(
    () => folderTrail(folderTree, activeFolderId === 'UNFILED' ? null : activeFolderId),
    [activeFolderId, folderTree],
  );

  const currentFolderExpectedCount = activeFolder?.directAssetCount ?? unfiledCount;
  const currentFolderLoaded = filteredMode || loadedFolders.has(currentFolderId);

  const enterFolder = (folder: LibraryFolderNode) => {
    setInspectorDismissed(true);
    setActiveFolderId(folder.id);
    setActiveGroup(null);
    setDetail(null);
    setSelectedIds([]);
    setSelectedFolderIds([]);
    setGroupMediaItems([]);
    setSelectedGroupMediaId(null);
  };

  const goToFolder = (folderId: string | null) => {
    setActiveFolderId(folderId);
    setActiveGroup(null);
    setDetail(null);
    setSelectedIds([]);
    setSelectedFolderIds([]);
    setGroupMediaItems([]);
    setSelectedGroupMediaId(null);
  };

  const openAssetCard = (asset: LibraryAsset) => {
    setInspectorDismissed(false);
    void openNode({
      id: `asset:${asset.id}`,
      kind: 'asset',
      name: asset.name,
      meta: asset.type,
      asset,
    });
  };

  const applyClipboardSelection = (mode: 'copy' | 'cut') => {
    const folderIds = selectedFolderIds;
    const largeFolders = folderTree.filter((folder) => (
      folderIds.includes(folder.id) && Math.max(folder.directAssetCount, folder.descendantAssetCount) > 15
    ));
    const apply = () => {
      setClipboardIds(selectedIds);
      setClipboardFolderIds(folderIds);
      setClipboardMode(mode);
    };
    if (largeFolders.length > 0) {
      setConfirmation({
        title: mode === 'cut' ? '剪切大型文件夹' : '复制大型文件夹',
        message: `${largeFolders.map((folder) => `「${folder.name}」`).join('、')}包含超过 15 个素材，仍要继续${mode === 'cut' ? '剪切' : '复制'}吗？`,
        confirmLabel: '继续',
        danger: mode === 'cut',
        onConfirm: apply,
      });
      return;
    }
    apply();
  };

  const menuItems = useMemo<LibraryActionItem[]>(() => {
    const node = menu.node;
    if (menu.groupMedia) {
      return [
        { id: 'edit-group-media', label: '编辑文件信息', icon: <Pencil size={14} /> },
        {
          id: 'remove-group-media',
          label: '移出资产组',
          icon: <FolderMinus size={14} />,
          danger: true,
        },
      ];
    }
    if (menu.surface === 'blank' || !node) {
      if (activeGroup) {
        return [
          { id: 'upload-files', label: '上传文件', icon: <Upload size={14} /> },
          {
            id: 'paste',
            label: '粘贴',
            icon: <ClipboardPaste size={14} />,
            disabled: clipboardIds.length === 0 && clipboardFolderIds.length === 0,
          },
        ];
      }
      return [
        { id: 'new-folder', label: '新建文件夹', icon: <FolderPlus size={14} /> },
        { id: 'upload-files', label: '上传文件', icon: <Upload size={14} /> },
        { id: 'upload-folder', label: '上传文件夹', icon: <Upload size={14} /> },
        {
          id: 'create-group',
          label: '新建资产组',
          icon: <Boxes size={14} />,
          disabled: !effectiveProjectId && queryScope !== 'GLOBAL',
        },
        {
          id: 'paste',
          label: '粘贴',
          icon: <ClipboardPaste size={14} />,
          disabled: clipboardIds.length === 0 && clipboardFolderIds.length === 0,
        },
      ];
    }
    if (node.asset && node.kind === 'group') return groupMenuItems(node.asset);
    if (node.asset) return assetMenuItems(node.asset, selectedIds);
    if (node.kind === 'folder') {
      if (node.id === 'unfiled') {
        return [
          { id: 'upload-folder', label: '上传文件夹', icon: <Upload size={14} /> },
          { id: 'upload-files', label: '上传素材', icon: <Upload size={14} /> },
        ];
      }
      return [
        { id: 'new-subfolder', label: '新建子目录', icon: <FolderPlus size={14} /> },
        { id: 'rename-folder', label: '重命名目录', icon: <Pencil size={14} /> },
        { id: 'copy', label: '复制', icon: <Copy size={14} /> },
        { id: 'cut', label: '剪切', icon: <Scissors size={14} /> },
        { id: 'delete-folder', label: '删除文件夹', icon: <Trash2 size={14} />, danger: true },
      ];
    }
    return [
      { id: 'new-folder', label: '新建目录', icon: <FolderPlus size={14} /> },
      { id: 'upload-folder', label: '上传文件夹', icon: <Upload size={14} /> },
      { id: 'upload-files', label: '上传素材', icon: <Upload size={14} /> },
    ];
  }, [
    activeGroup,
    clipboardIds.length,
    clipboardFolderIds.length,
    effectiveProjectId,
    menu.node,
    menu.groupMedia,
    menu.surface,
    queryScope,
    selectedIds,
  ]);

  const closeMenu = useCallback(() => setMenu((current) => ({ ...current, open: false })), []);

  const handleMenuAction = async (action: string) => {
    const node = menu.node;
    closeMenu();
    if (action === 'edit-group-media' && menu.groupMedia) {
      setPrompt({
        kind: 'edit-group-media',
        title: '编辑文件信息',
        value: menu.groupMedia.assetName || menu.groupMedia.media.fileName || '',
        description: menu.groupMedia.assetDescription || '',
        groupMedia: menu.groupMedia,
      });
      return;
    }
    if (action === 'remove-group-media' && menu.groupMedia) {
      removeGroupMedia(menu.groupMedia);
      return;
    }
    if (action === 'new-folder') {
      setPrompt({
        kind: 'folder',
        title: '新建文件夹',
        value: '',
        parentId: activeFolderId && activeFolderId !== 'UNFILED' ? activeFolderId : null,
      });
      return;
    }
    if (action === 'upload-folder') {
      folderInput.current?.click();
      return;
    }
    if (action === 'upload-files') {
      fileInput.current?.click();
      return;
    }
    if (action === 'create-group') {
      setPrompt({ kind: 'create-group', title: '新建资产组', value: '' });
      return;
    }
    if (action === 'paste') {
      await pasteClipboard();
      return;
    }
    if ((action === 'copy' || action === 'cut') && node?.kind === 'folder') {
      const folderId = folderIdFromNode(node);
      if (!folderId) return;
      if (!selectedFolderIds.includes(folderId)) {
        setClipboardIds([]);
        setClipboardFolderIds([folderId]);
        setClipboardMode(action === 'cut' ? 'cut' : 'copy');
      } else {
        applyClipboardSelection(action === 'cut' ? 'cut' : 'copy');
      }
      return;
    }
    if (!node) return;
    if (action === 'new-subfolder' && node.kind === 'folder') {
      setPrompt({ kind: 'folder', title: '新建子目录', value: '', parentId: folderIdFromNode(node) });
      return;
    }
    if (action === 'rename-folder' && node.kind === 'folder') {
      const folder = folderTree.find((item) => item.id === folderIdFromNode(node));
      if (folder) setPrompt({ kind: 'rename-folder', title: '重命名目录', value: folder.name, folder });
      return;
    }
    if (action === 'delete-folder' && node.kind === 'folder') {
      const folder = folderTree.find((item) => item.id === folderIdFromNode(node));
      if (folder) void deleteFolder(folder);
      return;
    }
    if (!node.asset) return;
    if (action === 'copy' || action === 'cut') {
      if (!selectedIds.includes(node.asset.id)) {
        setClipboardIds([node.asset.id]);
        setClipboardFolderIds([]);
        setClipboardMode(action === 'cut' ? 'cut' : 'copy');
      } else {
        applyClipboardSelection(action === 'cut' ? 'cut' : 'copy');
      }
      return;
    }
    if (action === 'rename') {
      setPrompt({
        kind: 'rename-asset',
        title: '重命名',
        value: node.asset.name,
        asset: node.asset,
      });
      return;
    }
    if (action === 'preview') {
      await openNode(node);
      return;
    }
    if (action === 'edit') {
      setPrompt({
        kind: 'edit-asset',
        title: '编辑资产',
        value: node.asset.name,
        description: editableAssetDescription(node.asset),
        asset: node.asset,
      });
      return;
    }
    if (action === 'move') {
      setMoveOpen(true);
      return;
    }
    if (action === 'merge') {
      setPrompt({ kind: 'merge-group', title: '合为资产组', value: `${node.asset.name} 资产组`, assetIds: selectedIds });
      return;
    }
    if (action === 'promote') {
      await promoteSelection(node.asset);
      return;
    }
    if (action === 'trash') {
      await trashSelection(node.asset);
      return;
    }
    if (action === 'add-to-group') {
      setAddToGroupAsset(node.asset);
      return;
    }
    if (action === 'use-for-video') {
      await createVideoSnapshot(node.asset);
    }
  };

  const submitPrompt = async (event: FormEvent) => {
    event.preventDefault();
    if (!prompt) return;
    setBusy(true);
    setError('');
    setNotice('');
    let editedAssetId: string | null = null;
    try {
      if (prompt.kind === 'folder') {
        const requestedName = prompt.value.trim();
        const response = await libraryApi.createFolder({
          scope: queryScope || 'PROJECT',
          ownerProjectId: projectForTree,
          parentId: prompt.parentId || null,
          name: requestedName,
        });
        if (response.data.data.name !== requestedName) {
          setNotice(`已有同名目录，已创建为「${response.data.data.name}」`);
        }
      } else if (prompt.kind === 'rename-folder') {
        await libraryApi.renameFolder(prompt.folder.id, {
          name: prompt.value.trim(),
          expectedVersion: prompt.folder.version,
        });
      } else if (prompt.kind === 'edit-asset') {
        await libraryApi.updateAsset(prompt.asset.id, {
          name: prompt.value.trim(),
          description: prompt.description.trim(),
          expectedVersion: prompt.asset.version,
          manualOverride: true,
        });
        editedAssetId = prompt.asset.id;
      } else if (prompt.kind === 'rename-asset') {
        await libraryApi.updateAsset(prompt.asset.id, {
          name: prompt.value.trim(),
          expectedVersion: prompt.asset.version,
          manualOverride: true,
        });
        editedAssetId = prompt.asset.id;
      } else if (prompt.kind === 'edit-group-media') {
        if (!prompt.groupMedia.assetId || prompt.groupMedia.assetVersion <= 0) {
          throw new Error('该组内文件缺少可编辑的源资产信息');
        }
        await libraryApi.updateAsset(prompt.groupMedia.assetId, {
          name: prompt.value.trim(),
          description: prompt.description.trim(),
          expectedVersion: prompt.groupMedia.assetVersion,
          manualOverride: true,
        });
        if (activeGroup) await loadGroupDetail(activeGroup, true);
      } else if (prompt.kind === 'create-group') {
        await libraryApi.createGroup({
          scope: queryScope === 'GLOBAL' ? 'GLOBAL' : 'PROJECT',
          ownerProjectId: queryScope === 'GLOBAL' ? null : effectiveProjectId,
          name: prompt.value.trim(),
        });
      } else if (prompt.kind === 'merge-group') {
        await libraryApi.createGroupFromAssets({
          ownerProjectId: effectiveProjectId,
          name: prompt.value.trim(),
          assetIds: prompt.assetIds,
        });
        setSelectedIds([]);
        setSelectedFolderIds([]);
      }
      setPrompt(null);
      await reloadTree();
      if (editedAssetId) {
        const refreshed = await libraryApi.getAsset(editedAssetId);
        setDetail(refreshed.data.data);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败');
    } finally {
      setBusy(false);
    }
  };

  const executeDeleteFolder = async (folder: LibraryFolderNode) => {
    setBusy(true);
    try {
      await libraryApi.deleteFolder(folder.id, folder.version);
      if (activeFolderId === folder.id) setActiveFolderId(null);
      await reloadTree();
    } catch (err) {
      setError(err instanceof Error ? err.message : '目录删除失败');
    } finally {
      setBusy(false);
    }
  };

  const deleteFolder = (folder: LibraryFolderNode) => {
    const affected = Math.max(folder.directAssetCount, folder.descendantAssetCount);
    setConfirmation({
      title: '删除文件夹',
      message: affected > 0
        ? `将删除「${folder.name}」及其所有子文件夹，其中 ${affected} 个素材会先移入回收站。`
        : `将删除「${folder.name}」及其所有子文件夹。`,
      confirmLabel: '删除文件夹',
      danger: true,
      onConfirm: () => executeDeleteFolder(folder),
    });
  };

  const executeTrashSelection = async (asset: LibraryAsset) => {
    const selected = selectedIds.includes(asset.id) ? selectedIds : [asset.id];
    setBusy(true);
    try {
      if (selected.length === 1) await libraryApi.trash(asset.id, asset.version);
      else await libraryApi.bulkTrash(selected);
      setSelectedIds([]);
      setSelectedFolderIds([]);
      setDetail(null);
      setActiveGroup(null);
      await reloadTree();
    } catch (err) {
      setError(err instanceof Error ? err.message : '移入回收站失败');
    } finally {
      setBusy(false);
    }
  };

  const trashSelection = (asset: LibraryAsset) => {
    const selected = selectedIds.includes(asset.id) ? selectedIds : [asset.id];
    setConfirmation({
      title: '移入回收站',
      message: selected.length > 1
        ? `将选中的 ${selected.length} 项素材移入回收站？`
        : `将「${asset.name}」移入回收站？`,
      confirmLabel: '移入回收站',
      danger: true,
      onConfirm: () => executeTrashSelection(asset),
    });
  };

  const promoteSelection = async (asset: LibraryAsset) => {
    const selected = selectedIds.includes(asset.id) ? selectedIds : [asset.id];
    setBusy(true);
    try {
      if (selected.length === 1) await libraryApi.promote(asset.id, asset.version);
      else await libraryApi.bulkPromote(selected);
      setSelectedIds([]);
      setSelectedFolderIds([]);
      setDetail(null);
      setActiveGroup(null);
      await reloadTree();
    } catch (err) {
      setError(err instanceof Error ? err.message : '提升到全局失败');
    } finally {
      setBusy(false);
    }
  };

  const createVideoSnapshot = async (asset: LibraryAsset) => {
    setBusy(true);
    try {
      const response = await libraryApi.createSnapshot(asset.id);
      const snapshot: AssetGroupSnapshot = response.data.data;
      sessionStorage.setItem('moon.pendingGroupSnapshot', JSON.stringify(snapshot));
      navigate('/chat');
    } catch (err) {
      setError(err instanceof Error ? err.message : '创建资产组快照失败');
    } finally {
      setBusy(false);
    }
  };

  const appendAssetsToGroup = async (group: LibraryAsset, assetIds: string[]) => {
    if (assetIds.length === 0) return;
    setBusy(true);
    setError('');
    try {
      const groupResponse = await libraryApi.getGroup(group.id);
      const details = await Promise.all(assetIds.map(
        (assetId) => libraryApi.getAsset(assetId).then((response) => response.data.data),
      ));
      const media = details.flatMap((detail) => detail.media[0] ? [detail.media[0]] : []);
      if (media.length === 0) throw new Error('所选资产没有可加入资产组的文件');
      const existing = groupResponse.data.data.media.map((item) => item.media);
      const byMedia = new Map(existing.map((item) => [item.id, item]));
      media.forEach((item) => byMedia.set(item.id, item));
      const items = [...byMedia.values()].map((item, index) => ({
        mediaId: item.id,
        sortOrder: index,
        role: item.role,
      }));
      await libraryApi.replaceGroupItems(group.id, groupResponse.data.data.version, items);
      await loadGroupDetail(group, true);
      await reloadTree();
    } catch (err) {
      setError(err instanceof Error ? err.message : '加入资产组失败');
    } finally {
      setBusy(false);
    }
  };

  const executeRemoveGroupMedia = async (item: LibraryGroupMediaItem) => {
    if (!activeGroup) return;
    setBusy(true);
    setError('');
    try {
      const response = await libraryApi.getGroup(activeGroup.id);
      const remaining = response.data.data.media
        .filter((entry) => entry.media.id !== item.media.id)
        .map((entry, index) => ({
          mediaId: entry.media.id,
          sortOrder: index,
          role: entry.media.role,
        }));
      await libraryApi.replaceGroupItems(activeGroup.id, response.data.data.version, remaining);
      await loadGroupDetail(activeGroup, true);
      await reloadTree();
    } catch (err) {
      setError(err instanceof Error ? err.message : '移出资产组失败');
    } finally {
      setBusy(false);
    }
  };

  const removeGroupMedia = (item: LibraryGroupMediaItem) => {
    setConfirmation({
      title: '移出资产组',
      message: `将「${item.assetName || item.media.fileName || '该文件'}」移出当前资产组？源文件不会被删除。`,
      confirmLabel: '移出资产组',
      danger: true,
      onConfirm: () => executeRemoveGroupMedia(item),
    });
  };

  const moveAssetsToTarget = async (folderId: string, assetIds: string[]) => {
    if (assetIds.length === 0) return;
    setBusy(true);
    try {
      await libraryApi.moveAssetsToFolder(folderId, {
        scope: queryScope || 'PROJECT',
        ownerProjectId: projectForTree,
        assetIds,
      });
      setMoveOpen(false);
      setSelectedIds([]);
      setSelectedFolderIds([]);
      await reloadTree();
    } catch (err) {
      setError(err instanceof Error ? err.message : '移动素材失败');
    } finally {
      setBusy(false);
    }
  };

  const moveSelection = (folderId: string) => moveAssetsToTarget(folderId, selectedIds);

  const moveFoldersToTarget = async (folderIds: string[], parentId: string | null) => {
    const selectedSet = new Set(folderIds);
    const topLevel = folderTree
      .filter((folder) => selectedSet.has(folder.id))
      .filter((folder) => {
        let current = folder.parentId;
        while (current) {
          if (selectedSet.has(current)) return false;
          current = folderTree.find((candidate) => candidate.id === current)?.parentId || null;
        }
        return true;
      })
      .sort((a, b) => folderDepth(b, folderTree) - folderDepth(a, folderTree));

    for (const folder of topLevel) {
      if (folder.parentId === parentId) continue;
      await libraryApi.moveFolder(folder.id, {
        parentId,
        expectedVersion: folder.version,
      });
    }
  };

  const pasteClipboard = async () => {
    if (clipboardIds.length === 0 && clipboardFolderIds.length === 0) return;
    if (activeGroup) {
      if (clipboardFolderIds.length > 0) {
        setError('资产组只能包含文件，不能粘贴文件夹');
        return;
      }
      await appendAssetsToGroup(activeGroup, clipboardIds);
      return;
    }
    setBusy(true);
    setError('');
    try {
      const targetFolderId = activeFolderId && activeFolderId !== 'UNFILED' ? activeFolderId : 'UNFILED';
      if (clipboardMode === 'cut') {
        if (clipboardIds.length > 0) {
          await libraryApi.moveAssetsToFolder(targetFolderId, {
            scope: queryScope || 'PROJECT',
            ownerProjectId: projectForTree,
            assetIds: clipboardIds,
          });
        }
        if (clipboardFolderIds.length > 0) {
          await moveFoldersToTarget(
            clipboardFolderIds,
            targetFolderId === 'UNFILED' ? null : targetFolderId,
          );
        }
        setClipboardIds([]);
        setClipboardFolderIds([]);
      } else {
        const folderId = targetFolderId === 'UNFILED' ? null : targetFolderId;
        if (clipboardIds.length > 0) {
          const response = await libraryApi.duplicateAssets({ assetIds: clipboardIds, folderId });
          setSelectedIds(response.data.data.updatedIds);
        }
        if (clipboardFolderIds.length > 0) {
          const response = await libraryApi.copyFolders({
            folderIds: clipboardFolderIds,
            parentId: folderId,
          });
          setSelectedFolderIds(response.data.data.updatedIds);
        }
      }
      await reloadTree();
      if (activeFolderId) await loadFolder(activeFolderId, 1, true);
    } catch (err) {
      setError(err instanceof Error ? err.message : '粘贴失败');
    } finally {
      setBusy(false);
    }
  };

  const deleteSelected = () => {
    const first = selectedAssets[0];
    if (first) void trashSelection(first);
  };

  const handleSelectableMouseDown = (event: ReactMouseEvent, id: string) => {
    if (event.button === 2) {
      event.preventDefault();
      return;
    }
    if (event.button !== 0) return;
    const ids = visibleSelectableIds;

    if (event.shiftKey && selectionAnchor.current) {
      const start = ids.indexOf(selectionAnchor.current);
      const end = ids.indexOf(id);
      if (start >= 0 && end >= 0) {
        const [from, to] = start < end ? [start, end] : [end, start];
        setSelectedIds(ids.slice(from, to + 1));
        setSelectedFolderIds([]);
      }
      return;
    }
    if (event.ctrlKey || event.metaKey) {
      setSelectedIds((current) => (
        current.includes(id) ? current.filter((item) => item !== id) : [...current, id]
      ));
      selectionAnchor.current = id;
      return;
    }
    if (selectedIds.includes(id)) {
      selectionAnchor.current = id;
      return;
    }
    selectionAnchor.current = id;
    setSelectedIds([id]);
    setSelectedFolderIds([]);
  };

  const handleFolderMouseDown = (event: ReactMouseEvent, id: string) => {
    setDetail(null);
    setActiveGroup(null);
    setGroupMediaItems([]);
    setSelectedGroupMediaId(null);
    if (event.button === 2) {
      event.preventDefault();
      return;
    }
    if (event.button !== 0) return;
    if (event.ctrlKey || event.metaKey) {
      setSelectedFolderIds((current) => (
        current.includes(id) ? current.filter((item) => item !== id) : [...current, id]
      ));
      return;
    }
    if (selectedFolderIds.includes(id)) {
      selectionAnchor.current = null;
      return;
    }
    setSelectedIds([]);
    setSelectedFolderIds([id]);
    selectionAnchor.current = null;
  };

  const startGridSelection = (event: ReactPointerEvent<HTMLElement>) => {
    if (event.button !== 0) return;
    if ((event.target as HTMLElement).closest('button, [data-library-item-id]')) return;
    const rect = folderGridRef.current?.getBoundingClientRect();
    if (!rect) return;
    selectionStart.current = { x: event.clientX, y: event.clientY };
    event.preventDefault();
    selectionAdditive.current = event.ctrlKey || event.metaKey;
    selectionBaseAssets.current = selectedIds;
    selectionBaseFolders.current = selectedFolderIds;
    if (!selectionAdditive.current) {
      setSelectedIds([]);
      setSelectedFolderIds([]);
    }
    setSelectionBox({
      x: event.clientX - rect.left,
      y: event.clientY - rect.top,
      width: 0,
      height: 0,
    });
    event.currentTarget.setPointerCapture(event.pointerId);
  };

  const moveGridSelection = (event: ReactPointerEvent<HTMLElement>) => {
    const start = selectionStart.current;
    const rect = folderGridRef.current?.getBoundingClientRect();
    if (!start || !rect) return;
    event.preventDefault();
    const box = {
      x: Math.min(start.x, event.clientX) - rect.left,
      y: Math.min(start.y, event.clientY) - rect.top,
      width: Math.abs(event.clientX - start.x),
      height: Math.abs(event.clientY - start.y),
    };
    setSelectionBox(box);

    const left = Math.min(start.x, event.clientX);
    const right = Math.max(start.x, event.clientX);
    const top = Math.min(start.y, event.clientY);
    const bottom = Math.max(start.y, event.clientY);
    const selectedAssets = Array.from(
      folderGridRef.current?.querySelectorAll<HTMLElement>('[data-library-item-id]') || [],
    ).filter((item) => {
      const itemRect = item.getBoundingClientRect();
      return itemRect.right >= left && itemRect.left <= right
        && itemRect.bottom >= top && itemRect.top <= bottom;
    }).map((item) => item.dataset.libraryItemId || '').filter(Boolean);
    const selectedFolders = Array.from(
      folderGridRef.current?.querySelectorAll<HTMLElement>('[data-library-folder-id]') || [],
    ).filter((item) => {
      const itemRect = item.getBoundingClientRect();
      return itemRect.right >= left && itemRect.left <= right
        && itemRect.bottom >= top && itemRect.top <= bottom;
    }).map((item) => item.dataset.libraryFolderId || '').filter(Boolean);

    setSelectedIds(
      selectionAdditive.current
        ? [...new Set([...selectionBaseAssets.current, ...selectedAssets])]
        : selectedAssets,
    );
    setSelectedFolderIds(
      selectionAdditive.current
        ? [...new Set([...selectionBaseFolders.current, ...selectedFolders])]
        : selectedFolders,
    );
  };

  const endGridSelection = () => {
    selectionStart.current = null;
    setSelectionBox(null);
  };

  const startAssetDrag = (event: ReactDragEvent, asset: LibraryAsset) => {
    if (event.button !== 0) {
      event.preventDefault();
      return;
    }
    const ids = selectedIds.includes(asset.id) ? selectedIds : [asset.id];
    const folderIds = selectedIds.includes(asset.id) ? selectedFolderIds : [];
    dragIds.current = ids;
    setInternalDragActive(true);
    event.dataTransfer.setData(
      INTERNAL_LIBRARY_DRAG_TYPE,
      JSON.stringify({ assetIds: ids, folderIds }),
    );
    const externalPayload = buildProjectLibraryDragPayload(asset);
    if (externalPayload) {
      event.dataTransfer.setData(PROJECT_LIBRARY_DRAG_MIME, serializeDragPayload(externalPayload));
      event.dataTransfer.effectAllowed = 'copyMove';
    } else {
      event.dataTransfer.effectAllowed = 'move';
    }
  };

  const endFolderDrag = () => {
    draggedFolderId.current = null;
    setInternalDragActive(false);
    setDragOverFolderId(null);
  };

  const endAssetDrag = () => {
    if (dragIds.current.length > 0) setSelectedIds(dragIds.current);
    dragIds.current = [];
    setInternalDragActive(false);
    setDragOverFolderId(null);
  };

  const startFolderDrag = (event: ReactDragEvent, folder: LibraryFolderNode) => {
    if (event.button !== 0) {
      event.preventDefault();
      return;
    }
    setInternalDragActive(true);
    draggedFolderId.current = folder.id;
    const folderIds = selectedFolderIds.includes(folder.id) ? selectedFolderIds : [folder.id];
    const assetIds = selectedFolderIds.includes(folder.id) ? selectedIds : [];
    if (!selectedFolderIds.includes(folder.id)) {
      setSelectedFolderIds([folder.id]);
      setSelectedIds([]);
    }
    selectionAnchor.current = null;
    event.dataTransfer.setData(
      INTERNAL_FOLDER_DRAG_TYPE,
      JSON.stringify({ assetIds, folderIds, sourceFolderId: folder.id }),
    );
    event.dataTransfer.effectAllowed = 'move';
  };

  const dropIntoFolder = async (event: ReactDragEvent, folder: LibraryFolderNode) => {
    event.preventDefault();
    event.stopPropagation();
    setDragOverFolderId(null);
    setInternalDragActive(false);
    const chatMediaPayload = parseChatMediaDragPayload(
      event.dataTransfer.getData(CHAT_MEDIA_DRAG_MIME),
    );
    if (chatMediaPayload) {
      await importChatMediaToFolder(chatMediaPayload, folder.id);
      return;
    }
    const folderPayload = event.dataTransfer.getData(INTERNAL_FOLDER_DRAG_TYPE);
    const assetPayload = event.dataTransfer.getData(INTERNAL_LIBRARY_DRAG_TYPE);
    let assetIds: string[] = [];
    let folderIds: string[] = [];
    try {
      const parsed = JSON.parse(folderPayload || assetPayload || '{}') as {
        assetIds?: string[];
        folderIds?: string[];
      };
      assetIds = parsed.assetIds || [];
      folderIds = parsed.folderIds || [];
    } catch {
      assetIds = [];
      folderIds = [];
    }
    dragIds.current = [];
    if (folderIds.includes(folder.id)) folderIds = folderIds.filter((id) => id !== folder.id);
    if (assetIds.length === 0 && folderIds.length === 0) return;
    setBusy(true);
    try {
      if (assetIds.length > 0) await moveAssetsToTarget(folder.id, assetIds);
      if (folderIds.length > 0) await moveFoldersToTarget(folderIds, folder.id);
      await reloadTree();
    } catch (err) {
      setError(err instanceof Error ? err.message : '移动失败');
    } finally {
      setBusy(false);
    }
  };

  const dropIntoGroup = async (event: ReactDragEvent, group: LibraryAsset) => {
    event.preventDefault();
    event.stopPropagation();
    setDragOverFolderId(null);
    setInternalDragActive(false);
    if (event.dataTransfer.types.includes(INTERNAL_FOLDER_DRAG_TYPE)) {
      const folderPayload = event.dataTransfer.getData(INTERNAL_FOLDER_DRAG_TYPE);
      try {
        const parsed = JSON.parse(folderPayload) as { folderIds?: string[] };
        if ((parsed.folderIds || []).length > 0) {
          setError('资产组只能包含图片、视频或音频文件，不能包含文件夹');
          return;
        }
      } catch {
        setError('资产组只能包含图片、视频或音频文件，不能包含文件夹');
        return;
      }
    }
    const raw = event.dataTransfer.getData(INTERNAL_LIBRARY_DRAG_TYPE)
      || event.dataTransfer.getData(INTERNAL_FOLDER_DRAG_TYPE);
    let ids: string[] = [];
    try {
      const parsed = JSON.parse(raw || '{}') as { assetIds?: string[] };
      ids = parsed.assetIds || [];
    } catch {
      ids = [];
    }
    dragIds.current = [];
    if (ids.length > 0) await appendAssetsToGroup(group, ids);
  };

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (target?.closest('input, textarea, select, [contenteditable="true"]')) return;
      const key = event.key.toLowerCase();
      const withCommand = event.ctrlKey || event.metaKey;
      if (!withCommand && event.key !== 'Delete') return;

      if (withCommand && key === 'a' && (viewMode === 'folders' || isTrash)) {
        event.preventDefault();
        setSelectedIds(visibleSelectableIds);
        setSelectedFolderIds(visibleSelectableFolderIds);
        selectionAnchor.current = visibleSelectableIds[0] || null;
        return;
      }
      if (withCommand && key === 'c' && (selectedIds.length > 0 || selectedFolderIds.length > 0)) {
        event.preventDefault();
        applyClipboardSelection('copy');
        return;
      }
      if (withCommand && key === 'x' && (selectedIds.length > 0 || selectedFolderIds.length > 0)) {
        event.preventDefault();
        applyClipboardSelection('cut');
        return;
      }
      if (withCommand && key === 'v' && (clipboardIds.length > 0 || clipboardFolderIds.length > 0)) {
        event.preventDefault();
        void pasteClipboard();
        return;
      }
      if ((withCommand && key === 'd') || event.key === 'Delete') {
        if (selectedAssets.length === 0) return;
        event.preventDefault();
        deleteSelected();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  useEffect(() => {
    const resetDragState = () => {
      dragIds.current = [];
      draggedFolderId.current = null;
      selectionStart.current = null;
      setInternalDragActive(false);
      setExternalDragActive(false);
      setDragOverFolderId(null);
      setSelectionBox(null);
    };
    window.addEventListener('dragend', resetDragState);
    window.addEventListener('drop', resetDragState);
    window.addEventListener('blur', resetDragState);
    return () => {
      window.removeEventListener('dragend', resetDragState);
      window.removeEventListener('drop', resetDragState);
      window.removeEventListener('blur', resetDragState);
    };
  }, []);

  const addAssetToGroup = async (groupId: string) => {
    if (!addToGroupAsset) return;
    setBusy(true);
    try {
      const assetDetail = detail?.asset.id === addToGroupAsset.id
        ? detail
        : (await libraryApi.getAsset(addToGroupAsset.id)).data.data;
      const response = await libraryApi.getGroup(groupId);
      const existing = response.data.data.media.map((item) => item.media);
      const mediaId = assetDetail.media[0]?.id;
      if (!mediaId) throw new Error('素材缺少可用媒体文件');
      const items = [
        ...existing.map((item, index) => ({ mediaId: item.id, sortOrder: index, role: item.role })),
        { mediaId, sortOrder: existing.length, role: 'REFERENCE' as const },
      ];
      await libraryApi.replaceGroupItems(groupId, response.data.data.version, items);
      setAddToGroupAsset(null);
      await reloadTree();
    } catch (err) {
      setError(err instanceof Error ? err.message : '加入资产组失败');
    } finally {
      setBusy(false);
    }
  };

  const uploadDirectItems = async (items: ExternalUploadItem[]) => {
    if (items.length === 0) return;
    const uploadedIds: string[] = [];
    await Promise.all(items.map(async ({ file }) => {
      const taskId = `upload:${crypto.randomUUID()}`;
      upsertTask({ id: taskId, kind: 'upload', label: file.name, status: 'queued', progress: 0 });
      try {
        const response = await libraryApi.uploadDirect(file, {
          scope: queryScope || 'PROJECT',
          projectId: projectForTree,
          name: file.name.replace(/\.[^.]+$/, ''),
        }, (progress) => updateTask(taskId, { status: 'uploading', progress }));
        if (activeFolderId && projectForTree) {
          await libraryApi.moveAssetsToFolder(activeFolderId, {
            scope: 'PROJECT',
            ownerProjectId: projectForTree,
            assetIds: [response.data.data.id],
          });
        }
        addAnalysisTask(
          response.data.data.id,
          response.data.data.name,
          response.data.data.metadataState || 'AI_GENERATING',
        );
        updateTask(taskId, { status: 'completed', progress: 100, assetId: response.data.data.id });
        uploadedIds.push(response.data.data.id);
      } catch (err) {
        updateTask(taskId, { status: 'failed', error: err instanceof Error ? err.message : '上传失败' });
      }
    }));
    if (activeGroup && uploadedIds.length > 0) {
      await appendAssetsToGroup(activeGroup, uploadedIds);
    }
    await reloadTree();
    if (activeFolderId) await loadFolder(activeFolderId, 1, true);
  };

  const uploadImportItems = async (items: ExternalUploadItem[]) => {
    if (items.length === 0) return;
    if (!effectiveProjectId) {
      setError('请先选择项目');
      return;
    }
    const entries: ImportEntry[] = items.map(({ file, relativePath }) => ({
      itemKey: `${relativePath}:${file.size}:${file.lastModified}`,
      relativePath,
      fileName: file.name,
      sizeBytes: file.size,
      mimeType: file.type || mimeFromName(file.name),
    })).filter((entry) => entry.mimeType.startsWith('image/'));
    if (!entries.length) {
      setError('目录中没有可导入的图片文件');
      return;
    }
    setBusy(true);
    try {
      const scanResponse = await libraryApi.scanImport({
        projectId: effectiveProjectId,
        scope: 'PROJECT',
        mode: 'TREE',
        entries,
      });
      const scan = scanResponse.data.data;
      const filesByPath = new Map(items.map(({ file, relativePath }) => [relativePath, file]));
      await Promise.all(entries.map(async (entry) => {
        const file = filesByPath.get(entry.relativePath);
        if (!file) return;
        const taskId = `tree-import:${scan.jobId}:${entry.itemKey}`;
        upsertTask({ id: taskId, kind: 'import', label: entry.fileName, status: 'queued', progress: 0, jobId: scan.jobId });
        try {
          const response = await libraryApi.uploadImportItem(
            scan.jobId,
            entry.itemKey,
            scan.confirmationToken,
            file,
            (progress) => updateTask(taskId, { status: 'uploading', progress }),
          );
          if (response.data.data.asset) {
            addAnalysisTask(
              response.data.data.asset.id,
              response.data.data.asset.name,
              response.data.data.asset.metadataState || 'AI_GENERATING',
            );
          }
          updateTask(taskId, { status: 'completed', progress: 100, assetId: response.data.data.asset?.id });
        } catch (err) {
          updateTask(taskId, { status: 'failed', error: err instanceof Error ? err.message : '导入失败' });
        }
      }));
      await reloadTree();
    } catch (err) {
      setError(err instanceof Error ? err.message : '目录导入失败');
    } finally {
      setBusy(false);
    }
  };

  const handleDirectUpload = async (files: FileList | null) => {
    const items = Array.from(files || []).map((file) => ({
      file,
      relativePath: file.name,
    }));
    if (fileInput.current) fileInput.current.value = '';
    await uploadDirectItems(items);
  };

  const handleFolderUpload = async (files: FileList | null) => {
    const items = Array.from(files || []).map((file) => ({
      file,
      relativePath: file.webkitRelativePath || file.name,
    }));
    if (folderInput.current) folderInput.current.value = '';
    await uploadImportItems(items);
  };

  const confirmExternalDrop = async () => {
    if (!pendingExternalDrop) return;
    const pending = pendingExternalDrop;
    setPendingExternalDrop(null);
    setBusy(true);
    try {
      if (pending.kind === 'folder') await uploadImportItems(pending.items);
      else await uploadDirectItems(pending.items);
    } finally {
      setBusy(false);
    }
  };

  const handleExternalDragEnter = (event: ReactDragEvent<HTMLElement>) => {
    const hasFiles = event.dataTransfer.types.includes('Files');
    const hasChatMedia = event.dataTransfer.types.includes(CHAT_MEDIA_DRAG_MIME);
    const hasInternal = event.dataTransfer.types.includes(INTERNAL_LIBRARY_DRAG_TYPE)
      || event.dataTransfer.types.includes(INTERNAL_FOLDER_DRAG_TYPE);
    if (!hasFiles && !hasChatMedia && !hasInternal) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = hasFiles || hasChatMedia ? 'copy' : 'move';
    if (hasFiles || hasChatMedia) setExternalDragActive(true);
  };

  const handleExternalDragOver = (event: ReactDragEvent<HTMLElement>) => {
    const hasFiles = event.dataTransfer.types.includes('Files');
    const hasChatMedia = event.dataTransfer.types.includes(CHAT_MEDIA_DRAG_MIME);
    const hasInternal = event.dataTransfer.types.includes(INTERNAL_LIBRARY_DRAG_TYPE)
      || event.dataTransfer.types.includes(INTERNAL_FOLDER_DRAG_TYPE);
    if (!hasFiles && !hasChatMedia && !hasInternal) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = hasFiles || hasChatMedia ? 'copy' : 'move';
  };

  const handleExternalDragLeave = (event: ReactDragEvent<HTMLElement>) => {
    if (event.currentTarget.contains(event.relatedTarget as Node)) return;
    setExternalDragActive(false);
  };

  const handleExternalDrop = async (event: ReactDragEvent<HTMLElement>) => {
    const chatMediaPayload = parseChatMediaDragPayload(
      event.dataTransfer.getData(CHAT_MEDIA_DRAG_MIME),
    );
    if (chatMediaPayload) {
      event.preventDefault();
      event.stopPropagation();
      setExternalDragActive(false);
      await importChatMediaToFolder(
        chatMediaPayload,
        currentFolderId === 'UNFILED' ? null : currentFolderId,
      );
      return;
    }
    if (event.dataTransfer.types.includes(INTERNAL_LIBRARY_DRAG_TYPE)
        || event.dataTransfer.types.includes(INTERNAL_FOLDER_DRAG_TYPE)) {
      event.preventDefault();
      event.stopPropagation();
      setInternalDragActive(false);
      return;
    }
    if (!event.dataTransfer.types.includes('Files')) return;
    event.preventDefault();
    event.stopPropagation();
    setExternalDragActive(false);
    if (!effectiveProjectId) {
      setError('请先选择项目后再导入外部文件');
      return;
    }
    const dropped = await readExternalDrop(event.dataTransfer);
    if (!dropped || dropped.items.length === 0) {
      setError('没有识别到可导入的文件');
      return;
    }
    setPendingExternalDrop(dropped);
  };

  const importChatMediaToFolder = async (
    payload: NonNullable<ReturnType<typeof parseChatMediaDragPayload>>,
    folderId: string | null,
  ) => {
    if (!effectiveProjectId) {
      setError('请先选择项目后再导入对话素材');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const asset = await importMediaToProjectLibrary({
        projectId: effectiveProjectId,
        producer: 'moon-chat',
        url: payload.url,
        mediaType: payload.mediaType,
        label: payload.name,
      });
      if (folderId) {
        await libraryApi.moveAssetsToFolder(folderId, {
          scope: 'PROJECT',
          ownerProjectId: effectiveProjectId,
          assetIds: [asset.id],
        });
      }
      await reloadTree();
      if (activeFolderId) await loadFolder(activeFolderId, 1, true);
      setNotice(folderId
        ? `已将「${payload.name}」导入项目库并移动到目标文件夹`
        : `已将「${payload.name}」导入项目库未归档`);
    } catch (err) {
      setError(err instanceof Error ? err.message : '导入对话素材失败');
    } finally {
      setBusy(false);
    }
  };

  const restoreAsset = async (asset: LibraryAsset) => {
    await libraryApi.restore(asset.id);
    await reloadTree();
  };

  const executePurgeAssets = async (assets: LibraryAsset[]) => {
    if (assets.length === 0) return;
    setBusy(true);
    setError('');
    try {
      const results = await Promise.allSettled(assets.map((asset) => libraryApi.purge(asset.id)));
      const failed = results.filter((result) => result.status === 'rejected').length;
      setSelectedIds([]);
      await reloadTree();
      if (failed > 0) setError(`${failed} 项删除失败，可能存在项目或资产组引用`);
    } catch (err) {
      setError(err instanceof Error ? err.message : '彻底删除失败');
    } finally {
      setBusy(false);
    }
  };

  const requestPurgeAssets = (assets: LibraryAsset[]) => {
    if (assets.length === 0) return;
    setConfirmation({
      title: assets.length > 1 ? '批量彻底删除' : '彻底删除',
      message: assets.length > 1
        ? `将彻底删除选中的 ${assets.length} 项素材？此操作不可恢复。`
        : `将彻底删除「${assets[0].name}」？此操作不可恢复。`,
      confirmLabel: assets.length > 1 ? `删除 ${assets.length} 项` : '彻底删除',
      danger: true,
      onConfirm: () => executePurgeAssets(assets),
    });
  };

  const purgeAsset = (asset: LibraryAsset) => requestPurgeAssets([asset]);

  const applyConfirmation = async () => {
    const current = confirmation;
    setConfirmation(null);
    if (current) await current.onConfirm();
  };

  const selectedGroupMedia = groupMediaItems.find(
    (item) => item.media.id === selectedGroupMediaId,
  ) || null;

  const analysisAssetIds = useMemo(() => Array.from(new Set(
    tasks
      .filter((task) => task.kind === 'analysis'
        && task.status === 'processing'
        && Boolean(task.assetId))
      .map((task) => task.assetId as string),
  )), [tasks]);
  const analysisAssetKey = analysisAssetIds.join(',');

  useEffect(() => {
    if (!analysisAssetKey) return;
    let cancelled = false;
    const poll = async () => {
      const response = await libraryApi.getAnalysisTasks(analysisAssetIds);
      if (cancelled) return;
      let terminal = false;
      response.data.data.forEach((task) => {
        const metadataState: MetadataState = task.status === 'PENDING' || task.status === 'PROCESSING'
          ? 'AI_GENERATING'
          : task.status === 'DEAD' ? 'AI_FAILED' : 'AI_GENERATED';
        const taskStatus = metadataState === 'AI_GENERATING'
          ? 'processing'
          : metadataState === 'AI_FAILED' ? 'failed' : 'completed';
        terminal ||= taskStatus !== 'processing';
        updateTask(`analysis:${task.assetId}`, {
          status: taskStatus,
          progress: taskStatus === 'processing' ? 65 : 100,
          error: task.errorMessage || undefined,
        });
        setAssetsByFolder((current) => Object.fromEntries(
          Object.entries(current).map(([folderId, assets]) => [
            folderId,
            assets.map((asset) => asset.id === task.assetId
              ? { ...asset, metadataState }
              : asset),
          ]),
        ));
        setDetail((current) => current?.asset.id === task.assetId
          ? { ...current, asset: { ...current.asset, metadataState } }
          : current);
      });
      if (terminal) {
        await reloadTree();
        if (activeFolderId) await loadFolder(activeFolderId, 1, true);
      }
    };
    void poll().catch(() => undefined);
    const timer = window.setInterval(() => void poll().catch(() => undefined), 4000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [activeFolderId, analysisAssetKey, analysisAssetIds, loadFolder, reloadTree, updateTask]);

  const inspectorMode = activeGroup && selectedGroupMedia
    ? 'group-media'
    : selectedAssets.length > 1
      ? 'selection'
      : detail
        ? 'asset'
        : inspectorFolder
          ? 'folder'
          : 'empty';
  const showInspector = !detailLoading && inspectorMode !== 'empty' && !inspectorDismissed;
  const detailViewOpen = showInspector;
  const closeInspector = () => setInspectorDismissed(true);

  return (
    <div
      className={`library-workspace${internalDragActive ? ' is-dragging-assets' : ''}`}
      onDragEnter={handleExternalDragEnter}
      onDragOver={handleExternalDragOver}
      onDragLeave={handleExternalDragLeave}
      onDrop={(event) => void handleExternalDrop(event)}
    >
      <aside className="library-workspace__nav">
        {!isEmbedded && (
          <button className="library-workspace__back" onClick={() => navigate('/chat')}>
            <ChevronLeft size={16} /> 返回 Moon
          </button>
        )}
        <div className="library-workspace__brand">
          <span><Package size={18} /></span>
          <div><strong>项目库</strong><small>文件夹与资产工作台</small></div>
        </div>
        <nav>
          {NAV_ITEMS.map((item) => (
            <button
              key={item.id}
              className={scopeView === item.id ? 'is-active' : ''}
              onClick={() => {
                setScopeView(item.id);
                if (item.id === 'TRASH') setType('');
              }}
            >
              {item.icon}<span>{item.label}</span>
            </button>
          ))}
        </nav>
      </aside>

      <main className="library-workspace__main">
        {externalDragActive && (
          <div className="library-external-drop-overlay" aria-hidden="true">
            <Upload size={28} />
            <strong>松开后确认导入</strong>
            <span>{currentProject?.name || '当前项目'}</span>
          </div>
        )}
        <header className="library-workspace__header">
          <div>
            <span>MOON ASSET LIBRARY</span>
            <h1>{isTrash ? '回收站' : '项目库'}</h1>
          </div>
          <div className="library-workspace__actions">
            <button onClick={() => setPanelOpen(!panelOpen)}>
              <ListChecks size={15} /> 任务
              {tasks.some((task) => task.status !== 'completed' && task.status !== 'failed')
                ? ` ${tasks.filter((task) => task.status !== 'completed' && task.status !== 'failed').length}`
                : ''}
            </button>
            {!isTrash && activeGroup && (
              <button className="is-primary" onClick={() => fileInput.current?.click()}>
                <Upload size={15} /> 上传文件
              </button>
            )}
            {!isTrash && !activeGroup && (
              <>
                <button onClick={() => folderInput.current?.click()}><FolderPlus size={15} /> 上传文件夹</button>
                <button onClick={() => setPrompt({ kind: 'folder', title: '新建目录', value: '', parentId: null })}>
                  <Plus size={15} /> 新建目录
                </button>
                <button
                  disabled={!effectiveProjectId && queryScope !== 'GLOBAL'}
                  onClick={() => setPrompt({ kind: 'create-group', title: '新建资产组', value: '' })}
                >
                  <Boxes size={15} /> 新建资产组
                </button>
                <button className="is-primary" onClick={() => fileInput.current?.click()}><Upload size={15} /> 上传素材</button>
              </>
            )}
          </div>
        </header>

        {!isTrash && (
          <section className="library-workspace__toolbar">
            <label>
              <Search size={15} />
              <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索名称、标签、描述" />
              {query && <button onClick={() => setQuery('')}><X size={13} /></button>}
            </label>
            {scopeView !== 'GLOBAL' && (
              <select value={effectiveProjectId} onChange={(event) => {
                const projectId = event.target.value;
                setSelectedProjectId(projectId);
                if (projectId) void loadProject(projectId).catch(() => undefined);
              }}>
                {projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
              </select>
            )}
            <select value={type} onChange={(event) => setType(event.target.value as AssetType | '')}>
              <option value="">全部类型</option>
              <option value="IMAGE">图片</option>
              <option value="VIDEO">视频</option>
              <option value="AUDIO">音频</option>
              <option value="GROUP">资产组</option>
            </select>
            <div className="library-workspace__view-toggle" role="group" aria-label="显示方式">
              <button
                type="button"
                className={viewMode === 'folders' ? 'is-active' : ''}
                aria-pressed={viewMode === 'folders'}
                aria-label="文件夹视图"
                title="文件夹视图"
                onClick={() => setViewMode('folders')}
              >
                <LayoutGrid size={16} />
              </button>
              <button
                type="button"
                className={viewMode === 'tree' ? 'is-active' : ''}
                aria-pressed={viewMode === 'tree'}
                aria-label="树形视图"
                title="树形视图"
                onClick={() => setViewMode('tree')}
              >
                <ListTree size={16} />
              </button>
            </div>
          </section>
        )}

        {tags.length > 0 && !isTrash && (
          <section className="library-workspace__tags">
            <button className={selectedTagIds.length === 0 ? 'is-active' : ''} onClick={() => setSelectedTagIds([])}>全部标签</button>
            {visibleTags.map((tag) => (
              <button
                key={tag.id}
                className={selectedTagIds.includes(tag.id) ? 'is-active' : ''}
                onClick={() => setSelectedTagIds((current) => (
                  current.includes(tag.id) ? current.filter((id) => id !== tag.id) : [...current, tag.id]
                ))}
              >
                {tag.name}
              </button>
            ))}
            {tags.length > 10 && (
              <button
                type="button"
                aria-label={tagsExpanded ? '收起更多标签' : '展开更多标签'}
                title={tagsExpanded ? '收起标签' : '显示全部标签'}
                onClick={() => setTagsExpanded((current) => !current)}
              >
                …
              </button>
            )}
          </section>
        )}

        {error && (
          <div className="library-workspace__error" role="alert">
            <CircleAlert size={15} /><span>{error}</span>
            <button onClick={() => setError('')}><X size={13} /></button>
          </div>
        )}

        {notice && (
          <div className="library-workspace__notice" role="status">
            <CheckCircle2 size={15} /><span>{notice}</span>
            <button onClick={() => setNotice('')}><X size={13} /></button>
          </div>
        )}

        {isTrash ? (
          <section className="library-trash">
            <div className="library-trash__toolbar">
              <span>共 {trashAssets.length} 项，已选择 {selectedIds.length} 项</span>
              <button
                type="button"
                onClick={() => setSelectedIds(trashAssets.map((asset) => asset.id))}
                disabled={trashAssets.length === 0}
              >
                全选
              </button>
              <button
                type="button"
                className="is-danger"
                onClick={() => requestPurgeAssets(selectedAssets)}
                disabled={busy || selectedAssets.length === 0}
              >
                <Trash2 size={13} /> 删除选中
              </button>
              <button
                type="button"
                className="is-danger"
                onClick={() => requestPurgeAssets(trashAssets)}
                disabled={busy || trashAssets.length === 0}
              >
                清空回收站
              </button>
            </div>
            <section
              ref={folderGridRef}
              className="library-trash-grid"
              onPointerDown={startGridSelection}
              onPointerMove={moveGridSelection}
              onPointerUp={endGridSelection}
              onPointerCancel={() => {
                selectionStart.current = null;
                setSelectionBox(null);
              }}
            >
              {trashAssets.map((asset) => (
                <article
                  key={asset.id}
                  data-library-item-id={asset.id}
                  className={selectedIds.includes(asset.id) ? 'is-selected' : ''}
                  onMouseDown={(event) => handleSelectableMouseDown(event, asset.id)}
                >
                  <span><AssetPreview asset={asset} /></span>
                  <strong>{asset.name}</strong>
                  <small>{asset.type} · 版本 v{asset.version}</small>
                  <div className="library-trash-grid__actions">
                    <button onClick={() => void restoreAsset(asset)} disabled={busy}>
                      <RotateCcw size={13} /> 恢复
                    </button>
                    <button className="is-danger" onClick={() => void purgeAsset(asset)} disabled={busy}>
                      <Trash2 size={13} /> 彻底删除
                    </button>
                  </div>
                </article>
              ))}
              {selectionBox && (
                <div
                  className="library-folder-grid__selection-box"
                  style={{
                    left: selectionBox.x,
                    top: selectionBox.y,
                    width: selectionBox.width,
                    height: selectionBox.height,
                  }}
                />
              )}
              {!loading && trashAssets.length === 0 && <p className="library-workspace__empty"><Trash2 size={22} /> 回收站是空的</p>}
            </section>
          </section>
        ) : loading ? (
          <div className="library-workspace__loading"><Sparkles size={20} /> 正在读取目录树...</div>
        ) : !effectiveProjectId ? (
          <div className="library-workspace__empty"><Package size={24} /> 暂无项目，请先在 Moon 中创建项目</div>
        ) : (
          <section className={`library-workspace__content${showInspector ? '' : ' is-inspector-empty'}${detailViewOpen ? ' is-detail-view' : ''}`}>
            <div className={`library-workspace__tree${viewMode === 'folders' ? ' is-folders' : ''}`}>
              {viewMode === 'folders' ? (
                <div
                  className="library-folder-browser"
                  onContextMenu={(event) => {
                    if ((event.target as HTMLElement).closest('button')) return;
                    event.preventDefault();
                    setMenu({
                      open: true,
                      x: event.clientX,
                      y: event.clientY,
                      surface: 'blank',
                    });
                  }}
                >
                  {activeGroup ? (
                    <div className="library-group-browser">
                      <div className="library-folder-browser__bar">
                        <button type="button" onClick={() => goToFolder(null)}>
                          <ChevronLeft size={14} /> 返回资产组列表
                        </button>
                        <div className="library-folder-browser__breadcrumb">
                          <button type="button" onClick={() => goToFolder(null)}>
                            项目库
                          </button>
                          <span>
                            <ChevronRight size={12} />
                            <strong>{activeGroup.name}</strong>
                          </span>
                        </div>
                        <small>{groupMediaItems.length} 个关联素材</small>
                      </div>

                      {detailLoading ? (
                        <div className="library-folder-browser__loading">
                          <Sparkles size={18} /> 正在读取资产组...
                        </div>
                      ) : groupMediaItems.length > 0 ? (
                        <section className="library-group-media-grid">
                          {groupMediaItems.map((entry, index) => {
                            const { media: item, assetName, assetDescription, assetTags } = entry;
                            const label = assetName
                              || item.fileName
                              || `${item.type === 'IMAGE' ? '图片' : item.type === 'VIDEO' ? '视频' : '音频'} ${index + 1}`;
                            return (
                              <article
                                key={item.id}
                                className={selectedGroupMediaId === item.id ? 'is-selected' : ''}
                                onClick={() => setSelectedGroupMediaId(item.id)}
                                onContextMenu={(event) => {
                                  event.preventDefault();
                                  event.stopPropagation();
                                  setSelectedGroupMediaId(item.id);
                                  setMenu({
                                    open: true,
                                    x: event.clientX,
                                    y: event.clientY,
                                    surface: 'item',
                                    groupMedia: entry,
                                  });
                                }}
                              >
                                <div className="library-group-media-grid__preview">
                                  {item.type === 'IMAGE' ? (
                                    <AuthenticatedPreviewImage
                                      src={assetUrl(item.contentUrl)}
                                      alt={label}
                                      className="library-group-media-grid__preview-button"
                                      onActivate={() => setSelectedGroupMediaId(item.id)}
                                    />
                                  ) : item.type === 'VIDEO' ? (
                                    <video src={assetUrl(item.contentUrl)} controls preload="metadata" draggable={false} />
                                  ) : (
                                    <audio src={assetUrl(item.contentUrl)} controls preload="metadata" />
                                  )}
                                </div>
                                <strong title={label}>{label}</strong>
                                <span className="library-group-media-grid__filename">
                                  {item.fileName || '未命名素材'}
                                </span>
                                <small>
                                  {item.type}
                                  {item.width && item.height ? ` · ${item.width}×${item.height}` : ''}
                                </small>
                                <p>{assetDescription || item.constraintText || '暂无图片信息'}</p>
                                {assetTags.length > 0 && (
                                  <div className="library-group-media-grid__tags">
                                    {assetTags.slice(0, 4).map((tag) => <span key={tag.id}>{tag.name}</span>)}
                                  </div>
                                )}
                              </article>
                            );
                          })}
                        </section>
                      ) : (
                        <div className="library-folder-grid__empty">
                          <Boxes size={24} />
                          <span>这个资产组还没有关联素材</span>
                        </div>
                      )}
                    </div>
                  ) : (
                    <>
                      <div className="library-folder-browser__bar">
                        <button
                          type="button"
                          disabled={!activeFolderId || activeFolderId === 'UNFILED'}
                          onClick={() => {
                            const parentId = activeFolder?.parentId || null;
                            goToFolder(parentId);
                          }}
                        >
                          <ChevronLeft size={14} /> 上一级
                        </button>
                        <div className="library-folder-browser__breadcrumb">
                          <button type="button" onClick={() => goToFolder(null)}>
                            项目库
                          </button>
                          {currentFolderTrail.map((folder) => (
                            <span key={folder.id}>
                              <ChevronRight size={12} />
                              <button type="button" onClick={() => goToFolder(folder.id)}>
                                {folder.name}
                              </button>
                            </span>
                          ))}
                        </div>
                        <small>
                          {visibleFolders.length} 个文件夹 · {visibleGroups.length} 个资产组 · {currentFolderAssets.length} 项素材
                        </small>
                      </div>

                      {!currentFolderLoaded && currentFolderExpectedCount > 0 ? (
                        <div className="library-folder-browser__loading">
                          <Sparkles size={18} /> 正在读取文件夹...
                        </div>
                      ) : (
                        <section
                          ref={folderGridRef}
                          className="library-folder-grid"
                          onPointerDown={startGridSelection}
                          onPointerMove={moveGridSelection}
                          onPointerUp={endGridSelection}
                          onPointerCancel={() => {
                            selectionStart.current = null;
                            setSelectionBox(null);
                          }}
                        >
                          {visibleFolders.map((folder) => (
                            <button
                              key={folder.id}
                              type="button"
                              className={`library-folder-grid__item is-folder${selectedFolderIds.includes(folder.id) ? ' is-selected' : ''}${clipboardMode === 'cut' && clipboardFolderIds.includes(folder.id) ? ' is-cut' : ''}${dragOverFolderId === folder.id ? ' is-drop-target' : ''}`}
                              data-library-folder-id={folder.id}
                              draggable
                              title="单击查看信息，双击进入文件夹"
                              onDoubleClick={() => enterFolder(folder)}
                              onMouseDown={(event) => handleFolderMouseDown(event, folder.id)}
                              onDragStart={(event) => startFolderDrag(event, folder)}
                              onDragEnd={endFolderDrag}
                              onDragOver={(event) => {
                                if (draggedFolderId.current === folder.id) return;
                                event.preventDefault();
                                event.dataTransfer.dropEffect = event.dataTransfer.types.includes(CHAT_MEDIA_DRAG_MIME)
                                  ? 'copy'
                                  : 'move';
                                setDragOverFolderId(folder.id);
                              }}
                              onDragEnter={(event) => {
                                if (draggedFolderId.current === folder.id) return;
                                event.preventDefault();
                                event.dataTransfer.dropEffect = event.dataTransfer.types.includes(CHAT_MEDIA_DRAG_MIME)
                                  ? 'copy'
                                  : 'move';
                                setDragOverFolderId(folder.id);
                              }}
                              onDragLeave={(event) => {
                                if (event.currentTarget.contains(event.relatedTarget as Node)) return;
                                setDragOverFolderId((current) => current === folder.id ? null : current);
                              }}
                              onDrop={(event) => void dropIntoFolder(event, folder)}
                              onContextMenu={(event) => {
                                event.preventDefault();
                                if (!selectedFolderIds.includes(folder.id)) setSelectedIds([]);
                                setSelectedFolderIds((current) => (
                                  current.includes(folder.id) ? current : [folder.id]
                                ));
                                setMenu({
                                  open: true,
                                  x: event.clientX,
                                  y: event.clientY,
                                  surface: 'item',
                                  node: folderDatum(folder),
                                });
                              }}
                            >
                              <span className="library-folder-grid__folder-icon"><Folder size={48} /></span>
                              <strong title={folder.name}>{folder.name}</strong>
                              <small>{folder.directAssetCount} 项</small>
                            </button>
                          ))}
                          {visibleGroups.map((group) => (
                            <button
                              key={group.id}
                              type="button"
                              className={`library-folder-grid__item is-group${selectedIds.includes(group.id) ? ' is-selected' : ''}${clipboardMode === 'cut' && clipboardIds.includes(group.id) ? ' is-cut' : ''}${dragOverFolderId === group.id ? ' is-drop-target' : ''}`}
                              data-library-item-id={group.id}
                              draggable
                              onDragStart={(event) => startAssetDrag(event, group)}
                              onDragEnd={endAssetDrag}
                              onDragOver={(event) => {
                                if (event.dataTransfer.types.includes(INTERNAL_FOLDER_DRAG_TYPE)) {
                                  event.preventDefault();
                                  event.stopPropagation();
                                  event.dataTransfer.dropEffect = 'none';
                                  return;
                                }
                                if (!event.dataTransfer.types.includes(INTERNAL_LIBRARY_DRAG_TYPE)) return;
                                event.preventDefault();
                                event.stopPropagation();
                                event.dataTransfer.dropEffect = 'move';
                                setDragOverFolderId(group.id);
                              }}
                              onDragLeave={(event) => {
                                if (event.currentTarget.contains(event.relatedTarget as Node)) return;
                                setDragOverFolderId((current) => current === group.id ? null : current);
                              }}
                              onDrop={(event) => void dropIntoGroup(event, group)}
                              onClick={(event) => {
                                if (event.shiftKey || event.ctrlKey || event.metaKey) return;
                                setSelectedIds([group.id]);
                                void loadGroupDetail(group);
                              }}
                              onDoubleClick={(event) => {
                                event.stopPropagation();
                                void openNode({
                                  id: `group:${group.id}`,
                                  kind: 'group',
                                  name: group.name,
                                  meta: group.scope === 'GLOBAL' ? '全局资产组' : '项目资产组',
                                  asset: group,
                                });
                              }}
                              onMouseDown={(event) => handleSelectableMouseDown(event, group.id)}
                              onContextMenu={(event) => {
                                event.preventDefault();
                                setSelectedIds((current) => selectionForContextMenu(current, group.id));
                                setSelectedFolderIds([]);
                                setMenu({
                                  open: true,
                                  x: event.clientX,
                                  y: event.clientY,
                                  surface: 'item',
                                  node: {
                                    id: `group:${group.id}`,
                                    kind: 'group',
                                    name: group.name,
                                    asset: group,
                                  },
                                });
                              }}
                            >
                              <span className="library-folder-grid__group-icon"><Boxes size={44} /></span>
                              <strong title={group.name}>{group.name}</strong>
                              <small>资产组 · 单击查看，双击进入</small>
                            </button>
                          ))}
                          {currentFolderAssets.map((asset) => (
                            <button
                              key={asset.id}
                              type="button"
                              className={`library-folder-grid__item is-asset${selectedIds.includes(asset.id) ? ' is-selected' : ''}${clipboardMode === 'cut' && clipboardIds.includes(asset.id) ? ' is-cut' : ''}`}
                              data-library-item-id={asset.id}
                              draggable
                              onDragStart={(event) => startAssetDrag(event, asset)}
                              onDragEnd={endAssetDrag}
                              onClick={(event) => {
                                if (event.shiftKey || event.ctrlKey || event.metaKey) return;
                                openAssetCard(asset);
                              }}
                              onMouseDown={(event) => handleSelectableMouseDown(event, asset.id)}
                              onContextMenu={(event) => {
                                event.preventDefault();
                                setSelectedIds((current) => selectionForContextMenu(current, asset.id));
                                setSelectedFolderIds([]);
                                setMenu({
                                  open: true,
                                  x: event.clientX,
                                  y: event.clientY,
                                  surface: 'item',
                                  node: {
                                    id: `asset:${asset.id}`,
                                    kind: 'asset',
                                    name: asset.name,
                                    meta: asset.type,
                                    asset,
                                  },
                                });
                              }}
                            >
                              <span className="library-folder-grid__asset-preview"><AssetPreview asset={asset} /></span>
                              <strong title={asset.name}>{asset.name}</strong>
                              <small>
                                {asset.type}
                                {asset.metadataState === 'NONE' ? '' : ` · ${METADATA_LABEL[asset.metadataState]}`}
                                {' · '}v{asset.version}
                              </small>
                            </button>
                          ))}
                          {visibleFolders.length === 0 && visibleGroups.length === 0 && currentFolderAssets.length === 0 && (
                            <div className="library-folder-grid__empty">
                              <Folder size={24} />
                              <span>{filteredMode ? '没有符合条件的素材' : '这个文件夹还是空的'}</span>
                            </div>
                          )}
                          {selectionBox && (
                            <div
                              className="library-folder-grid__selection-box"
                              style={{
                                left: selectionBox.x,
                                top: selectionBox.y,
                                width: selectionBox.width,
                                height: selectionBox.height,
                              }}
                            />
                          )}
                        </section>
                      )}
                    </>
                  )}
                </div>
              ) : (
                <LibraryTree
                  data={treeData}
                  revision={treeRevision}
                  selectedIds={selectedIds}
                  onOpenNode={(node) => void openNode(node)}
                  onToggleAsset={(assetId) => setSelectedIds((current) => (
                    current.includes(assetId) ? current.filter((id) => id !== assetId) : [...current, assetId]
                  ))}
                  onSetSelection={setSelectedIds}
                  onToggleFolder={toggleTreeNode}
                  onLoadMore={(folderId) => void loadFolder(
                    folderId,
                    Math.floor((assetsByFolder[folderId]?.length || 0) / PAGE_SIZE) + 1,
                  )}
                  onOpenMenu={(point) => {
                    if (point.node.asset && point.node.kind === 'asset') {
                      setSelectedIds((current) => selectionForContextMenu(current, point.node.asset!.id));
                    }
                    setMenu({ open: true, x: point.x, y: point.y, surface: 'item', node: point.node });
                  }}
                />
              )}
            </div>
            {detailViewOpen && (
              <button
                type="button"
                className="library-workspace__detail-back"
                onClick={closeInspector}
              >
                <ChevronLeft size={16} /> 返回素材列表
              </button>
            )}
            {showInspector && <LibraryInspector
              mode={inspectorMode}
              folder={inspectorFolder}
              detail={detail}
              groupMedia={selectedGroupMedia}
              selectedAssets={selectedAssets}
              tags={tags}
              projectName={currentProject?.name}
              busy={busy}
              onEdit={(asset) => setPrompt({
                kind: 'edit-asset',
                title: '编辑资产',
                value: asset.name,
                description: editableAssetDescription(asset),
                asset,
              })}
              onTrash={(asset) => void trashSelection(asset)}
              onPromote={(asset) => void promoteSelection(asset)}
              onAddToGroup={setAddToGroupAsset}
              onUseForVideo={(asset) => void createVideoSnapshot(asset)}
              onOpenGroup={(asset) => void openNode({
                id: `group:${asset.id}`,
                kind: 'group',
                name: asset.name,
                asset,
              })}
              onEditGroupMedia={(item) => setPrompt({
                kind: 'edit-group-media',
                title: '编辑文件信息',
                value: item.assetName || item.media.fileName || '',
                description: item.assetDescription || '',
                groupMedia: item,
              })}
              onRemoveGroupMedia={removeGroupMedia}
              onToggleTag={async (asset, tag) => {
                const tagIds = asset.tags.some((item) => item.id === tag.id)
                  ? asset.tags.filter((item) => item.id !== tag.id).map((item) => item.id)
                  : [...asset.tags.map((item) => item.id), tag.id];
                await libraryApi.replaceTags(asset.id, tagIds);
                if (asset.type === 'GROUP') {
                  await loadGroupDetail(asset);
                } else {
                  const response = await libraryApi.getAsset(asset.id);
                  setDetail(response.data.data);
                }
                await reloadTree();
              }}
              onCreateSubfolder={(folder) => setPrompt({
                kind: 'folder',
                title: '新建子目录',
                value: '',
                parentId: folder.id,
              })}
              onRenameFolder={(folder) => setPrompt({
                kind: 'rename-folder',
                title: '重命名目录',
                value: folder.name,
                folder,
              })}
              onDeleteFolder={(folder) => void deleteFolder(folder)}
              onOpenFolder={enterFolder}
              onBulkTrash={() => void trashSelection(selectedAssets[0])}
              onBulkPromote={() => void promoteSelection(selectedAssets[0])}
              onBulkMove={() => setMoveOpen(true)}
              onBulkMerge={() => {
                if (canMergeIntoGroup(selectedAssets)) {
                  setPrompt({
                    kind: 'merge-group',
                    title: '合为资产组',
                    value: `${selectedAssets[0]?.name || '新建'} 资产组`,
                    assetIds: selectedIds,
                  });
                } else {
                  setError('只能将同一项目中的图片、视频或音频合为资产组');
                }
              }}
            />}
          </section>
        )}
      </main>

      <input
        ref={fileInput}
        hidden
        type="file"
        multiple
        accept="image/*,video/*,audio/*"
        onChange={(event) => void handleDirectUpload(event.target.files)}
      />
      <input
        ref={folderInput}
        hidden
        type="file"
        multiple
        {...({ webkitdirectory: '', directory: '' } as Record<string, string>)}
        onChange={(event) => void handleFolderUpload(event.target.files)}
      />

      <LibraryActionMenu
        open={menu.open}
        x={menu.x}
        y={menu.y}
        title={menu.groupMedia
          ? menu.groupMedia.assetName || menu.groupMedia.media.fileName || undefined
          : menu.node?.asset
            ? selectedIds.length > 1 && selectedIds.includes(menu.node.asset.id)
              ? `已选择 ${selectedIds.length} 项`
              : menu.node.asset.name
            : menu.node?.name}
        items={menuItems}
        onAction={(action) => void handleMenuAction(action)}
        onClose={closeMenu}
      />

      {confirmation && (
        <Modal title={confirmation.title} onClose={() => !busy && setConfirmation(null)}>
          <div className="library-confirm-dialog">
            <p>{confirmation.message}</p>
            <footer>
              <button type="button" onClick={() => setConfirmation(null)} disabled={busy}>取消</button>
              <button
                type="button"
                className={confirmation.danger ? 'is-danger' : 'is-primary'}
                onClick={() => void applyConfirmation()}
                disabled={busy}
              >
                {confirmation.confirmLabel}
              </button>
            </footer>
          </div>
        </Modal>
      )}

      {prompt && (
        <Modal title={prompt.title} onClose={() => !busy && setPrompt(null)}>
          <form className="library-modal-form" onSubmit={(event) => void submitPrompt(event)}>
            <label>
              <span>名称</span>
              <input
                autoFocus
                value={prompt.value}
                onChange={(event) => setPrompt({ ...prompt, value: event.target.value })}
                required
              />
            </label>
            {(prompt.kind === 'edit-asset' || prompt.kind === 'edit-group-media') && (
              <label>
                <span>描述 / 文字约束</span>
                <textarea
                  rows={5}
                  value={prompt.description}
                  onChange={(event) => setPrompt({ ...prompt, description: event.target.value })}
                />
              </label>
            )}
            <footer>
              <button type="button" onClick={() => setPrompt(null)} disabled={busy}>取消</button>
              <button type="submit" className="is-primary" disabled={busy || !prompt.value.trim()}>确定</button>
            </footer>
          </form>
        </Modal>
      )}

      {moveOpen && (
        <Modal title="移动到目录" onClose={() => !busy && setMoveOpen(false)}>
          <div className="library-folder-picker">
            <button onClick={() => void moveSelection('UNFILED')} disabled={busy}>未归档</button>
            {folderTree.map((folder) => (
              <button key={folder.id} onClick={() => void moveSelection(folder.id)} disabled={busy}>
                {'　'.repeat(folderDepth(folder, folderTree))}{folder.name}
              </button>
            ))}
          </div>
        </Modal>
      )}

      {pendingExternalDrop && (
        <Modal title="确认导入" onClose={() => !busy && setPendingExternalDrop(null)}>
          <div className="library-import-confirm">
            <p>
              将 {pendingExternalDrop.items.length} 个文件
              {pendingExternalDrop.kind === 'folder' ? '及其文件夹结构' : ''}
              导入到
              <strong>{currentProject?.name || '当前项目'}</strong>？
            </p>
            <small>确认后将开始上传并加入素材分析队列。</small>
            <footer>
              <button type="button" onClick={() => setPendingExternalDrop(null)} disabled={busy}>取消</button>
              <button type="button" className="is-primary" onClick={() => void confirmExternalDrop()} disabled={busy}>
                确认导入
              </button>
            </footer>
          </div>
        </Modal>
      )}

      {addToGroupAsset && (
        <Modal title="加入资产组" onClose={() => !busy && setAddToGroupAsset(null)}>
          <div className="library-folder-picker">
            {groups.map((group) => (
              <button key={group.id} onClick={() => void addAssetToGroup(group.id)} disabled={busy}>
                <Boxes size={14} /> {group.name}
              </button>
            ))}
            {groups.length === 0 && <p>当前项目暂无资产组</p>}
          </div>
        </Modal>
      )}

    </div>
  );
}

const NAV_ITEMS: Array<{ id: ScopeView; label: string; icon: ReactNode }> = [
  { id: 'ALL', label: '项目库', icon: <Boxes size={16} /> },
  { id: 'TRASH', label: '回收站', icon: <Trash2 size={16} /> },
];

function folderToTreeNode(
  folder: LibraryFolderTreeNode,
  expanded: Set<string>,
  assetsByFolder: Record<string, LibraryAsset[]>,
  loadedFolders: Set<string>,
  filteredMode: boolean,
): LibraryTreeNodeDatum {
  const nodeId = `folder:${folder.id}`;
  return {
    id: nodeId,
    kind: 'folder',
    name: folder.name,
    meta: `${folder.directAssetCount} 项`,
    folder,
    expanded: expanded.has(nodeId),
    hasChildren: folder.descendantAssetCount > 0 || folder.children.length > 0,
    children: expanded.has(nodeId)
      ? [
        ...folder.children.map((child) => folderToTreeNode(
          child,
          expanded,
          assetsByFolder,
          loadedFolders,
          filteredMode,
        )),
        ...folderAssetChildren(
          folder.id,
          assetsByFolder,
          loadedFolders,
          !filteredMode && (assetsByFolder[folder.id]?.length || 0) < folder.directAssetCount,
        ),
      ]
      : undefined,
  };
}

function folderAssetChildren(
  folderId: string,
  assetsByFolder: Record<string, LibraryAsset[]>,
  loadedFolders: Set<string>,
  moreAvailable: boolean,
): LibraryTreeNodeDatum[] {
  if (!loadedFolders.has(folderId)) return [];
  const assets = assetsByFolder[folderId] || [];
  const children: LibraryTreeNodeDatum[] = assets.map((asset) => ({
    id: `asset:${asset.id}`,
    kind: 'asset',
    name: asset.name,
    meta: asset.type,
    asset,
  }));
  if (moreAvailable) {
    children.push({
      id: `more:${folderId}`,
      kind: 'more',
      name: '加载更多',
      loadMoreFor: folderId,
      expanded: false,
    });
  }
  return children;
}

function assetMenuItems(asset: LibraryAsset, selectedIds: string[]): LibraryActionItem[] {
  const multiple = selectedIds.length > 1 && selectedIds.includes(asset.id);
  return [
    ...(!multiple ? [{ id: 'preview', label: '预览详情', icon: <Images size={14} /> }] : []),
    ...(!multiple ? [{ id: 'rename', label: '重命名', icon: <Pencil size={14} /> }] : []),
    { id: 'copy', label: '复制', icon: <Copy size={14} /> },
    { id: 'cut', label: '剪切', icon: <Scissors size={14} /> },
    ...(!multiple ? [{ id: 'edit', label: '编辑信息', icon: <Pencil size={14} /> }] : []),
    { id: 'move', label: '移动到目录', icon: <FolderPlus size={14} /> },
    { id: 'merge', label: '合为资产组', icon: <Boxes size={14} /> },
    {
      id: 'promote',
      label: '提升到全局',
      icon: <Sparkles size={14} />,
      disabled: asset.scope === 'GLOBAL',
    },
    { id: 'trash', label: '移入回收站', icon: <Trash2 size={14} />, danger: true },
  ];
}

function groupMenuItems(asset: LibraryAsset): LibraryActionItem[] {
  return [
    { id: 'preview', label: '查看资产组', icon: <Images size={14} /> },
    { id: 'rename', label: '重命名', icon: <Pencil size={14} /> },
    { id: 'copy', label: '复制', icon: <Copy size={14} /> },
    { id: 'cut', label: '剪切', icon: <Scissors size={14} /> },
    { id: 'use-for-video', label: '用此组生视频', icon: <Sparkles size={14} /> },
    { id: 'edit', label: '编辑信息', icon: <Pencil size={14} /> },
    {
      id: 'promote',
      label: '提升到全局',
      icon: <Sparkles size={14} />,
      disabled: asset.scope === 'GLOBAL',
    },
    { id: 'trash', label: '移入回收站', icon: <Trash2 size={14} />, danger: true },
  ];
}

function Modal({ title, children, onClose }: { title: string; children: ReactNode; onClose: () => void }) {
  return (
    <div className="library-modal-backdrop" onMouseDown={onClose}>
      <section className="library-modal" role="dialog" aria-modal="true" aria-label={title} onMouseDown={(event) => event.stopPropagation()}>
        <header><h2>{title}</h2><button onClick={onClose} aria-label="关闭"><X size={16} /></button></header>
        {children}
      </section>
    </div>
  );
}

function AssetPreview({ asset }: { asset: LibraryAsset }) {
  if (asset.coverUrl && asset.type === 'VIDEO') {
    return <video src={assetUrl(asset.coverUrl)} muted preload="metadata" draggable={false} />;
  }
  if (asset.coverUrl) {
    return <img src={assetUrl(asset.coverUrl)} alt="" loading="lazy" draggable={false} />;
  }
  return <span><Package size={20} /></span>;
}

function parseAssetType(value: string | null): AssetType | '' {
  if (value === 'AI_ASSET') return 'GROUP';
  return value === 'IMAGE' || value === 'VIDEO' || value === 'AUDIO'
    || value === 'GROUP'
    ? value
    : '';
}

function dedupeAssets(assets: LibraryAsset[]): LibraryAsset[] {
  const seen = new Set<string>();
  return assets.filter((asset) => {
    if (seen.has(asset.id)) return false;
    seen.add(asset.id);
    return true;
  });
}

function groupAssetsForTree(
  items: LibraryGroupMediaItem[],
): Array<{ asset: LibraryAsset; mediaCount: number }> {
  const byAsset = new Map<string, { asset: LibraryAsset; mediaCount: number }>();
  items.forEach((item) => {
    if (!item.asset) return;
    const current = byAsset.get(item.asset.id);
    if (current) {
      current.mediaCount += 1;
    } else {
      byAsset.set(item.asset.id, { asset: item.asset, mediaCount: 1 });
    }
  });
  return [...byAsset.values()];
}

function folderAtPath(
  folders: LibraryFolderNode[],
  path: string[],
): LibraryFolderNode | undefined {
  let parentId: string | null = null;
  let current: LibraryFolderNode | undefined;
  for (const name of path) {
    current = folders.find((folder) => folder.parentId === parentId && folder.name === name);
    if (!current) return undefined;
    parentId = current.id;
  }
  return current;
}

function folderDepth(folder: LibraryFolderNode, folders: LibraryFolderNode[]): number {
  let depth = 0;
  let parentId = folder.parentId;
  while (parentId) {
    depth += 1;
    parentId = folders.find((candidate) => candidate.id === parentId)?.parentId || null;
  }
  return depth;
}

function folderDatum(folder: LibraryFolderNode): LibraryTreeNodeDatum {
  return {
    id: `folder:${folder.id}`,
    kind: 'folder',
    name: folder.name,
    meta: `${folder.directAssetCount} 项`,
    folder,
  };
}

function folderTrail(
  folders: LibraryFolderNode[],
  activeFolderId: string | null,
): LibraryFolderNode[] {
  const trail: LibraryFolderNode[] = [];
  let current = activeFolderId
    ? folders.find((folder) => folder.id === activeFolderId)
    : undefined;
  while (current) {
    trail.unshift(current);
    current = current.parentId
      ? folders.find((folder) => folder.id === current?.parentId)
      : undefined;
  }
  return trail;
}

function folderIdFromNode(node: LibraryTreeNodeDatum): string | null {
  if (node.id === 'unfiled') return null;
  const id = node.folder?.id || (node.id.startsWith('folder:') ? node.id.slice(7) : null);
  return id === 'UNFILED' ? null : id;
}

function treeDataHash(node: LibraryTreeNodeDatum): number {
  const text = JSON.stringify({
    id: node.id,
    expanded: node.expanded,
    children: node.children?.map((child) => ({
      id: child.id,
      name: child.name,
      meta: child.meta,
      expanded: child.expanded,
      children: child.children?.map((item) => item.id),
    })),
  });
  let hash = 0;
  for (let index = 0; index < text.length; index += 1) {
    hash = ((hash << 5) - hash + text.charCodeAt(index)) | 0;
  }
  return hash;
}

function readFileEntry(
  entry: FileSystemFileEntry,
  basePath = '',
): Promise<ExternalUploadItem[]> {
  return new Promise((resolve, reject) => {
    entry.file(
      (file) => resolve([{
        file,
        relativePath: basePath ? `${basePath}/${file.name}` : file.name,
      }]),
      reject,
    );
  });
}

function readDirectoryEntries(reader: FileSystemDirectoryReader): Promise<FileSystemEntry[]> {
  return new Promise((resolve, reject) => reader.readEntries(resolve, reject));
}

async function readDroppedEntry(
  entry: FileSystemEntry,
  basePath = '',
): Promise<ExternalUploadItem[]> {
  const entryPath = basePath ? `${basePath}/${entry.name}` : entry.name;
  if (entry.isFile) return readFileEntry(entry as FileSystemFileEntry, basePath);
  if (!entry.isDirectory) return [];
  const reader = (entry as FileSystemDirectoryEntry).createReader();
  const items: ExternalUploadItem[] = [];
  while (true) {
    const batch = await readDirectoryEntries(reader);
    if (batch.length === 0) break;
    for (const child of batch) {
      items.push(...await readDroppedEntry(child, entryPath));
    }
  }
  return items;
}

async function readExternalDrop(
  dataTransfer: DataTransfer,
): Promise<PendingExternalDrop | null> {
  const entries = Array.from(dataTransfer.items)
    .map((item) => (
      item as DataTransferItem & {
        webkitGetAsEntry?: () => FileSystemEntry | null;
      }
    ).webkitGetAsEntry?.() ?? null)
    .filter((entry): entry is FileSystemEntry => Boolean(entry));

  if (entries.length > 0) {
    const items = (await Promise.all(entries.map((entry) => readDroppedEntry(entry)))).flat();
    return {
      kind: entries.some((entry) => entry.isDirectory) ? 'folder' : 'files',
      items,
    };
  }

  const items = Array.from(dataTransfer.files).map((file) => ({
    file,
    relativePath: file.name,
  }));
  return items.length > 0 ? { kind: 'files', items } : null;
}

function mimeFromName(name: string): string {
  const extension = name.split('.').pop()?.toLowerCase();
  if (extension === 'jpg' || extension === 'jpeg') return 'image/jpeg';
  if (extension === 'png') return 'image/png';
  if (extension === 'webp') return 'image/webp';
  if (extension === 'gif') return 'image/gif';
  return '';
}
