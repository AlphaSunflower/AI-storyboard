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
  Globe2,
  Images,
  LayoutGrid,
  ListChecks,
  ListTree,
  MessageSquare,
  Package,
  Pencil,
  Plus,
  RotateCcw,
  Scissors,
  Search,
  Sparkles,
  SquareArrowOutUpRight,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import {
  libraryApi,
  type AnalysisRequestItem,
  type AssetGroupSnapshot,
  type AssetScope,
  type AssetType,
  type AssetColorLabel,
  type AssetFlag,
  type AssetUsage,
  type DuplicateGroup,
  type ImportEntry,
  type LibraryAsset,
  type LibraryAssetDetail,
  type LibraryFolderNode,
  type LibraryGroupMediaItem,
  type LibraryGroupInsight,
  type LibrarySavedView,
  type LibraryTag,
  type MetadataState,
  type OutputConversationSummary,
} from '../api/library';
import { agentApi, type ProjectAssetGroupBinding } from '../api/agent';
import { useProjectStore, getLastOpenedProjectId } from '../stores/projectStore';
import { useAgentStore } from '../stores/agentStore';
import { useLibraryTaskStore, type LibraryTask } from '../stores/libraryTaskStore';
import { notify } from '../stores/toastStore';
import { Tooltip } from '../components/common/Tooltip';
import { LibraryTaskQueue } from '../components/library/LibraryTaskQueue';
import { VirtualAssetGrid } from '../components/library/VirtualAssetGrid';
import {
  LibraryCommandPalette,
  type LibraryCommandAction,
} from '../components/library/LibraryCommandPalette';
import {
  LibraryActionMenu,
  type LibraryActionItem,
} from '../components/library/LibraryActionMenu';
import {
  AssetIcon,
  LibraryTree,
  type LibraryTreeNodeDatum,
} from '../components/library/LibraryTree';
import {
  AuthenticatedPreviewImage,
  GroupUsagePanel,
  LibraryInspector,
} from '../components/library/LibraryInspector';
import {
  DocumentPreviewModal,
  type DocumentPreviewTarget,
} from '../components/library/DocumentPreviewModal';
import {
  buildFolderTree,
  canMergeIntoGroup,
  selectionForContextMenu,
  type LibraryFolderTreeNode,
} from '../components/library/libraryTreeModel';
import {
  buildLibraryQuery,
  formatBytes,
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
import { libraryThumbnailUrl } from '../lib/libraryMedia';
import { useIsMobile } from '../hooks/useIsMobile';
import { MobileBottomNav } from '../components/layout/MobileBottomNav';
import './LibraryWorkspacePage.css';

const PAGE_SIZE = 100;
/** 目录内素材超过该数量时，AI 完善前先确认，避免一次误触触发大量模型调用。 */
const AI_ENRICH_CONFIRM_THRESHOLD = 10;
const INTERNAL_LIBRARY_DRAG_TYPE = 'application/x-moon-library-assets';
const INTERNAL_FOLDER_DRAG_TYPE = 'application/x-moon-library-folder';
const SMART_COLORS = [
  { hex: '#e05b4b', label: '红' },
  { hex: '#e08a3c', label: '橙' },
  { hex: '#e3b341', label: '黄' },
  { hex: '#4f9d69', label: '绿' },
  { hex: '#3f9c9c', label: '青' },
  { hex: '#4b7fd1', label: '蓝' },
  { hex: '#8d6bc9', label: '紫' },
  { hex: '#d46a9e', label: '粉' },
  { hex: '#2b3036', label: '黑' },
  { hex: '#c9c3ba', label: '灰' },
];
/** 仅当前页面会话可用的重试文件；刷新后不保留，任务面板会提示重新选择。 */
const libraryUploadRetryFiles = new Map<string, File>();
type ScopeView = 'PROJECT' | 'GLOBAL' | 'OUTPUT' | 'DUPLICATES' | 'UNUSED' | 'TRASH';
type LibraryViewMode = 'folders' | 'tree';
const METADATA_LABEL: Record<MetadataState, string> = {
  NONE: '未分析',
  AI_GENERATING: 'AI 生成中',
  AI_GENERATED: 'AI 生成',
  AI_FAILED: 'AI 分析失败',
  EDITED: '已编辑',
};

/** 后端约定的「未归属任何对话」伪 conversationId。 */
const UNASSIGNED_OUTPUT = 'UNASSIGNED';

/** 产出对话卡上的时间：越近越口语，超过一周给具体日期。 */
function formatOutputTime(value?: string | null): string {
  if (!value) return '还没有生成时间';
  const timestamp = new Date(value).getTime();
  if (Number.isNaN(timestamp)) return '';
  const diffMinutes = Math.floor((Date.now() - timestamp) / 60_000);
  if (diffMinutes < 1) return '刚刚';
  if (diffMinutes < 60) return `${diffMinutes} 分钟前`;
  const diffHours = Math.floor(diffMinutes / 60);
  if (diffHours < 24) return `${diffHours} 小时前`;
  const diffDays = Math.floor(diffHours / 24);
  if (diffDays < 7) return `${diffDays} 天前`;
  return new Date(timestamp).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' });
}

/** 对话标题来自 MoonAgent；对话已删除或还没加载到时给出可辨认的兜底名。 */
function outputConversationTitle(
  group: OutputConversationSummary,
  titles: Record<string, { title: string; updatedAt?: string }>,
): string {
  if (!group.conversationId) return '未归属产出';
  const known = titles[group.conversationId]?.title?.trim();
  if (known) return known;
  return `对话 · ${group.conversationId.slice(0, 8)}`;
}

function outputCountsLabel(group: OutputConversationSummary): string {
  const parts: string[] = [`共 ${group.assetCount} 项`];
  if (group.imageCount > 0) parts.push(`图片 ${group.imageCount}`);
  if (group.videoCount > 0) parts.push(`视频 ${group.videoCount}`);
  if (group.documentCount > 0) parts.push(`文档 ${group.documentCount}`);
  return parts.join(' · ');
}

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
  | { kind: 'merge-group'; title: string; value: string; assetIds: string[] }
  | { kind: 'save-view'; title: string; value: string };

/** 弹窗提交成功后的通知标题：按操作类型给一句中文确认 */
function promptSuccessTitle(kind: PromptState['kind']): string {
  switch (kind) {
    case 'folder': return '已新建目录';
    case 'rename-folder': return '已重命名目录';
    case 'create-group':
    case 'merge-group': return '已新建资产组';
    case 'save-view': return '已保存视图';
    case 'rename-asset': return '已重命名素材';
    default: return '已保存素材信息';
  }
}

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
  const isMobile = useIsMobile();
  const [searchParams, setSearchParams] = useSearchParams();
  const currentProject = useProjectStore((state) => state.currentProject);
  const projects = useProjectStore((state) => state.projects);
  const loadProjects = useProjectStore((state) => state.loadProjects);
  const loadProject = useProjectStore((state) => state.loadProject);
  const [scopeView, setScopeView] = useState<ScopeView>(() => {
    const initial = scopeFromQuery(searchParams.get('scope'));
    return initial === 'ALL' ? 'PROJECT' : initial;
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
  /** 资产组使用侧信息：groupId → 成员数 / 快照次数 / 最近快照时间。 */
  const [groupInsights, setGroupInsights] = useState<Record<string, LibraryGroupInsight>>({});
  /** 资产组被哪些对话绑定：groupId → 绑定行。 */
  const [groupBindings, setGroupBindings] = useState<Record<string, ProjectAssetGroupBinding[]>>({});
  const [groupBindingBusy, setGroupBindingBusy] = useState(false);
  /** 资产组「绑定到所选对话」的目标；默认跟随 Moon 当前对话，也可在面板里改选。 */
  const [bindingTargetConversationId, setBindingTargetConversationId] = useState('');
  const activeConversationId = useAgentStore((state) => state.activeConversationId);
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
  const [outputAssets, setOutputAssets] = useState<LibraryAsset[]>([]);
  const [outputTotal, setOutputTotal] = useState(0);
  const [outputLoading, setOutputLoading] = useState(false);
  /** 产出素材根视图：一个对话一张卡片（虚拟文件夹）。 */
  const [outputConversations, setOutputConversations] = useState<OutputConversationSummary[]>([]);
  const [outputConversationsLoading, setOutputConversationsLoading] = useState(false);
  /** 当前打开的产出对话；UNASSIGNED 表示未归属对话的生成结果。 */
  const [outputConversationId, setOutputConversationId] = useState<string | null>(
    () => searchParams.get('conversationId') || null,
  );
  const [outputConversationTitles, setOutputConversationTitles] = useState<
    Record<string, { title: string; updatedAt?: string }>
  >({});
  /** 重复素材：同一文件被多个素材引用的分组。 */
  const [duplicateGroups, setDuplicateGroups] = useState<DuplicateGroup[]>([]);
  const [duplicateTotal, setDuplicateTotal] = useState(0);
  const [duplicatesLoading, setDuplicatesLoading] = useState(false);
  const [activeDuplicateGroup, setActiveDuplicateGroup] = useState<DuplicateGroup | null>(null);
  const [duplicateSurvivorId, setDuplicateSurvivorId] = useState('');
  const [duplicateSelectedIds, setDuplicateSelectedIds] = useState<string[]>([]);
  const [duplicatesMerging, setDuplicatesMerging] = useState(false);
  /** 未被分镜、资产组、画布引用的素材。 */
  const [unusedAssets, setUnusedAssets] = useState<LibraryAsset[]>([]);
  const [unusedTotal, setUnusedTotal] = useState(0);
  const [unusedLoading, setUnusedLoading] = useState(false);
  /** 产出素材的镜头版本组：同一对话 + 同一镜头的多次生成归为一组。 */
  const [activeVersionStack, setActiveVersionStack] = useState<{
    sceneId: string;
    assets: LibraryAsset[];
  } | null>(null);
  const [stackSelectedIds, setStackSelectedIds] = useState<string[]>([]);
  /** 挑片筛选。 */
  const [minRating, setMinRating] = useState(0);
  const [flagFilter, setFlagFilter] = useState<'' | AssetFlag>('');
  const [colorFilter, setColorFilter] = useState<'' | AssetColorLabel>('');
  const [savedViews, setSavedViews] = useState<LibrarySavedView[]>([]);
  const [activeViewId, setActiveViewId] = useState('');
  const [assetUsage, setAssetUsage] = useState<AssetUsage | null>(null);
  const [usageLoading, setUsageLoading] = useState(false);
  const [commandOpen, setCommandOpen] = useState(false);
  const [smartMode, setSmartMode] = useState(false);
  const [smartResults, setSmartResults] = useState<LibraryAsset[]>([]);
  const [smartLoading, setSmartLoading] = useState(false);
  const [smartColor, setSmartColor] = useState('');
  const [similarAsset, setSimilarAsset] = useState<LibraryAsset | null>(null);
  const [globalTotal, setGlobalTotal] = useState(0);
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
  const [documentPreview, setDocumentPreview] = useState<DocumentPreviewTarget | null>(null);
  const [inspectorDismissed, setInspectorDismissed] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const folderInput = useRef<HTMLInputElement>(null);
  const folderGridRef = useRef<HTMLElement>(null);
  const treeScrollRef = useRef<HTMLDivElement>(null);
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
  const isGlobal = scopeView === 'GLOBAL';
  const isOutput = scopeView === 'OUTPUT';
  const isOutputRoot = isOutput && !outputConversationId;
  const isDuplicates = scopeView === 'DUPLICATES';
  const isUnused = scopeView === 'UNUSED';
  const queryScope: AssetScope | undefined = isTrash ? undefined : isGlobal ? 'GLOBAL' : 'PROJECT';
  const includeGlobal = scopeView === 'PROJECT';
  const origin: 'GENERATED' | undefined = isOutput ? 'GENERATED' : undefined;
  const projectForTree = isGlobal || isTrash ? undefined : effectiveProjectId;
  const effectiveType = type || undefined;
  const showFolders = viewMode === 'folders' || isOutput;

  const reloadTree = useCallback(async () => {
    if (isTrash) {
      const response = await libraryApi.listAssets({ deleted: true, page: 1, size: PAGE_SIZE });
      setTrashAssets(response.data.data.records || []);
      setLoading(false);
      return;
    }
    if (isDuplicates) {
      setLoading(false);
      setError('');
      setFolderTree([]);
      setUnfiledCount(0);
      setGroups([]);
      setAssetsByFolder({});
      setLoadedFolders(new Set());
      return;
    }
    if (isUnused) {
      setLoading(false);
      setError('');
      setFolderTree([]);
      setUnfiledCount(0);
      setGroups([]);
      setAssetsByFolder({});
      setLoadedFolders(new Set());
      return;
    }
    if (isOutput) {
      setLoading(false);
      setError('');
      try {
        const tagResponse = await libraryApi.listTags();
        setTags(tagResponse.data.data || []);
        setTagsExpanded(false);
      } catch (err) {
        setError(err instanceof Error ? err.message : '加载标签失败');
      }
      setFolderTree([]);
      setUnfiledCount(0);
      setGroups([]);
      setAssetsByFolder({});
      setLoadedFolders(new Set());
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
          includeGlobal,
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
  }, [includeGlobal, isDuplicates, isOutput, isTrash, isUnused, projectForTree, queryScope]);

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
      includeGlobal,
      origin,
    });
    if (isEmbedded) params.set('embedded', '1');
    if (isOutput && outputConversationId) params.set('conversationId', outputConversationId);
    setSearchParams(params, { replace: true });
  }, [
    effectiveProjectId,
    includeGlobal,
    isEmbedded,
    isOutput,
    origin,
    outputConversationId,
    query,
    scopeView,
    selectedTagIds,
    setSearchParams,
    type,
  ]);

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
    setDocumentPreview(null);
    setActiveDuplicateGroup(null);
    setDuplicateSurvivorId('');
    setDuplicateSelectedIds([]);
  }, [effectiveProjectId, query, scopeView, selectedTagIds, type]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setCommandOpen((current) => !current);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  const filteredMode = Boolean(
    query.trim() || type || selectedTagIds.length || minRating > 0 || flagFilter || colorFilter,
  );
  const visibleTags = tagsExpanded ? tags : tags.slice(0, 10);
  const visibleDuplicateGroups = useMemo(() => {
    const term = query.trim().toLowerCase();
    if (!term) return duplicateGroups;
    return duplicateGroups.filter((group) => group.assets.some((asset) => (
      asset.name.toLowerCase().includes(term)
      || (asset.description || '').toLowerCase().includes(term)
    )));
  }, [duplicateGroups, query]);
  const duplicateReclaimableBytes = useMemo(
    () => duplicateGroups.reduce(
      (sum, group) => sum + group.sizeBytes * Math.max(0, group.assets.length - 1),
      0,
    ),
    [duplicateGroups],
  );

  const loadFolder = useCallback(async (folderId: string, page = 1, force = false) => {
    if (!force && page === 1 && loadedFolders.has(folderId) && !filteredMode) return;
    setError('');
    const globalBucket = folderId === 'GLOBAL';
    const response = await libraryApi.listAssets({
      scope: globalBucket ? 'GLOBAL' : (queryScope || 'PROJECT'),
      projectId: globalBucket ? undefined : projectForTree,
      folderId: globalBucket ? undefined : folderId,
      q: query.trim() || undefined,
      type: effectiveType,
      tagIds: selectedTagIds.length ? selectedTagIds : undefined,
      tagMode: 'ALL',
      minRating: minRating > 0 ? minRating : undefined,
      flag: flagFilter || undefined,
      colorLabel: colorFilter || undefined,
      includeGlobal: globalBucket ? undefined : includeGlobal,
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
    colorFilter,
    effectiveType,
    filteredMode,
    flagFilter,
    includeGlobal,
    loadedFolders,
    minRating,
    projectForTree,
    query,
    queryScope,
    selectedTagIds,
  ]);

  const loadFilteredAssets = useCallback(async () => {
    // 产出素材走自己的对话聚合接口，不参与项目库的平铺筛选。
    if (!filteredMode || isTrash || isOutput || isDuplicates || isUnused) return;
    const response = await libraryApi.listAssets({
      scope: queryScope,
      projectId: projectForTree,
      q: query.trim() || undefined,
      type: effectiveType,
      tagIds: selectedTagIds.length ? selectedTagIds : undefined,
      tagMode: 'ALL',
      minRating: minRating > 0 ? minRating : undefined,
      flag: flagFilter || undefined,
      colorLabel: colorFilter || undefined,
      includeGlobal,
      page: 1,
      size: PAGE_SIZE,
    });
    const grouped: Record<string, LibraryAsset[]> = {};
    (response.data.data.records || []).forEach((asset) => {
      const key = asset.scope === 'GLOBAL' ? 'GLOBAL' : (asset.folderId || 'UNFILED');
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
    colorFilter,
    effectiveType,
    filteredMode,
    flagFilter,
    folderTree,
    includeGlobal,
    isDuplicates,
    isOutput,
    isTrash,
    isUnused,
    minRating,
    projectForTree,
    query,
    queryScope,
    selectedTagIds,
  ]);

  useEffect(() => {
    if (filteredMode) void loadFilteredAssets();
  }, [filteredMode, loadFilteredAssets]);

  const loadOutputAssets = useCallback(async (page = 1, append = false) => {
    if (!effectiveProjectId || !outputConversationId) {
      setOutputAssets([]);
      setOutputTotal(0);
      return;
    }
    setOutputLoading(true);
    setError('');
    try {
      const response = await libraryApi.listAssets({
        scope: 'PROJECT',
        projectId: effectiveProjectId,
        origin: 'GENERATED',
        conversationId: outputConversationId,
        q: query.trim() || undefined,
        type: effectiveType,
        tagIds: selectedTagIds.length ? selectedTagIds : undefined,
        tagMode: 'ALL',
        minRating: minRating > 0 ? minRating : undefined,
        flag: flagFilter || undefined,
        colorLabel: colorFilter || undefined,
        page,
        size: PAGE_SIZE,
      });
      const records = response.data.data.records || [];
      setOutputAssets((current) => (append ? dedupeAssets([...current, ...records]) : records));
      setOutputTotal(response.data.data.total || 0);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载产出素材失败');
    } finally {
      setOutputLoading(false);
    }
  }, [colorFilter, effectiveProjectId, effectiveType, flagFilter, minRating, outputConversationId, query, selectedTagIds]);

  /** 产出素材根视图：按对话聚合；搜索/筛选同时作用于卡片里的素材。 */
  const loadOutputConversations = useCallback(async () => {
    if (!effectiveProjectId) {
      setOutputConversations([]);
      return;
    }
    setOutputConversationsLoading(true);
    setError('');
    try {
      const response = await libraryApi.listOutputConversations({
        projectId: effectiveProjectId,
        q: query.trim() || undefined,
        type: effectiveType,
        tagIds: selectedTagIds.length ? selectedTagIds : undefined,
        tagMode: 'ALL',
        minRating: minRating > 0 ? minRating : undefined,
        flag: flagFilter || undefined,
        colorLabel: colorFilter || undefined,
      });
      setOutputConversations(response.data.data || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载产出对话失败');
      setOutputConversations([]);
    } finally {
      setOutputConversationsLoading(false);
    }
  }, [colorFilter, effectiveProjectId, effectiveType, flagFilter, minRating, query, selectedTagIds]);

  useEffect(() => {
    if (!isOutput) return;
    if (outputConversationId) {
      void loadOutputAssets(1, false);
    } else {
      void loadOutputConversations();
    }
  }, [isOutput, outputConversationId, loadOutputAssets, loadOutputConversations]);

  // 对话标题真源在 MoonAgent：项目库只按 conversationId 聚合，标题在渲染时合并。
  // 不限于「产出素材」视图：主项目库的素材详情同样需要显示来源对话。
  useEffect(() => {
    if (!effectiveProjectId) return;
    let cancelled = false;
    void agentApi.listConversations(effectiveProjectId)
      .then((response) => {
        if (cancelled) return;
        const next: Record<string, { title: string; updatedAt?: string }> = {};
        (response.data.data || []).forEach((conversation) => {
          next[conversation.id] = { title: conversation.title, updatedAt: conversation.updatedAt };
        });
        setOutputConversationTitles(next);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [effectiveProjectId]);

  const loadDuplicates = useCallback(async (page = 1, append = false) => {
    if (!effectiveProjectId) {
      setDuplicateGroups([]);
      setDuplicateTotal(0);
      return;
    }
    setDuplicatesLoading(true);
    setError('');
    try {
      const response = await libraryApi.listDuplicates({
        projectId: effectiveProjectId,
        page,
        size: 20,
      });
      const records = response.data.data.records || [];
      setDuplicateGroups((current) => {
        if (!append) return records;
        const known = new Set(current.map((group) => group.blobId));
        return [...current, ...records.filter((group) => !known.has(group.blobId))];
      });
      setDuplicateTotal(response.data.data.total || 0);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载重复素材失败');
      setDuplicateGroups([]);
    } finally {
      setDuplicatesLoading(false);
    }
  }, [effectiveProjectId]);

  useEffect(() => {
    if (isDuplicates) void loadDuplicates(1, false);
  }, [isDuplicates, loadDuplicates]);

  const loadUnusedAssets = useCallback(async (page = 1, append = false) => {
    if (!effectiveProjectId) {
      setUnusedAssets([]);
      setUnusedTotal(0);
      return;
    }
    setUnusedLoading(true);
    setError('');
    try {
      const response = await libraryApi.listUnusedAssets({
        projectId: effectiveProjectId,
        page,
        size: 40,
      });
      const records = response.data.data.records || [];
      setUnusedAssets((current) => {
        if (!append) return records;
        const known = new Set(current.map((asset) => asset.id));
        return [...current, ...records.filter((asset) => !known.has(asset.id))];
      });
      setUnusedTotal(response.data.data.total || 0);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载未使用素材失败');
      setUnusedAssets([]);
    } finally {
      setUnusedLoading(false);
    }
  }, [effectiveProjectId]);

  useEffect(() => {
    if (isUnused) void loadUnusedAssets(1, false);
  }, [isUnused, loadUnusedAssets]);

  const trashUnusedSelection = async () => {
    if (selectedIds.length === 0) return;
    setBusy(true);
    try {
      await libraryApi.bulkTrash(selectedIds);
      notify({
        title: '已移入回收站',
        description: `${selectedIds.length} 项未使用素材可在 3 天内恢复`,
        variant: 'success',
      });
      setSelectedIds([]);
      await loadUnusedAssets(1, false);
    } catch (err) {
      setError(err instanceof Error ? err.message : '清理未使用素材失败');
    } finally {
      setBusy(false);
    }
  };

  const openDuplicateGroup = (group: DuplicateGroup) => {
    setActiveDuplicateGroup(group);
    setDuplicateSurvivorId(group.assets[0]?.id || '');
    setDuplicateSelectedIds(group.assets.slice(1).map((asset) => asset.id));
  };

  const openVersionStack = (stack: { sceneId: string; assets: LibraryAsset[] }) => {
    setActiveVersionStack(stack);
    setStackSelectedIds(stack.assets.slice(0, 2).map((asset) => asset.id));
  };

  const toggleStackVariant = (assetId: string) => {
    setStackSelectedIds((current) => {
      if (current.includes(assetId)) return current.filter((id) => id !== assetId);
      if (current.length >= 2) return [current[current.length - 1], assetId];
      return [...current, assetId];
    });
  };

  const mergeActiveDuplicateGroup = async () => {
    if (!activeDuplicateGroup || !duplicateSurvivorId) return;
    const duplicateIds = duplicateSelectedIds.filter((id) => id !== duplicateSurvivorId);
    if (duplicateIds.length === 0) {
      setError('请至少勾选一份要合并的重复素材');
      return;
    }
    setDuplicatesMerging(true);
    setError('');
    try {
      await libraryApi.mergeDuplicates({
        survivorAssetId: duplicateSurvivorId,
        duplicateAssetIds: duplicateIds,
      });
      notify({
        title: '已合并重复素材',
        description: `保留 1 份，${duplicateIds.length} 份重复素材已移入回收站`,
        variant: 'success',
      });
      setActiveDuplicateGroup(null);
      setDuplicateSurvivorId('');
      setDuplicateSelectedIds([]);
      await loadDuplicates(1, false);
    } catch (err) {
      setError(err instanceof Error ? err.message : '合并重复素材失败');
    } finally {
      setDuplicatesMerging(false);
    }
  };

  useEffect(() => {
    if (!includeGlobal) {
      setGlobalTotal(0);
      return;
    }
    void libraryApi.listAssets({ scope: 'GLOBAL', page: 1, size: 1 })
      .then((response) => setGlobalTotal(response.data.data.total || 0))
      .catch(() => setGlobalTotal(0));
  }, [includeGlobal]);

  const allKnownAssets = useMemo(() => {
    const map = new Map<string, LibraryAsset>();
    Object.values(assetsByFolder).flat().forEach((asset) => map.set(asset.id, asset));
    outputAssets.forEach((asset) => map.set(asset.id, asset));
    groups.forEach((group) => map.set(group.id, group));
    return map;
  }, [assetsByFolder, groups, outputAssets]);

  const selectedAssets = useMemo(() => {
    if (isTrash) {
      return trashAssets.filter((asset) => selectedIds.includes(asset.id));
    }
    return selectedIds
      .map((id) => allKnownAssets.get(id))
      .filter((asset): asset is LibraryAsset => Boolean(asset));
  }, [allKnownAssets, isTrash, selectedIds, trashAssets]);

  const paletteAssets = useMemo(
    () => Array.from(allKnownAssets.values()).filter((asset) => asset.type !== 'GROUP'),
    [allKnownAssets],
  );

  const commandActions = useMemo<LibraryCommandAction[]>(() => [
    { id: 'project', label: '去项目库', hint: '导航', run: () => setScopeView('PROJECT') },
    { id: 'global', label: '去全局库', hint: '导航', run: () => setScopeView('GLOBAL') },
    { id: 'output', label: '去产出素材', hint: '导航', run: () => setScopeView('OUTPUT') },
    { id: 'duplicates', label: '去重复项', hint: '导航', run: () => setScopeView('DUPLICATES') },
    { id: 'unused', label: '去未使用素材', hint: '导航', run: () => setScopeView('UNUSED') },
    { id: 'trash', label: '去回收站', hint: '导航', run: () => setScopeView('TRASH') },
    {
      id: 'upload',
      label: '上传素材',
      hint: '动作',
      run: () => fileInput.current?.click(),
    },
    {
      id: 'new-folder',
      label: '新建目录',
      hint: '动作',
      run: () => setPrompt({ kind: 'folder', title: '新建目录', value: '', parentId: null }),
    },
    {
      id: 'new-group',
      label: '新建资产组',
      hint: '动作',
      run: () => setPrompt({ kind: 'create-group', title: '新建资产组', value: '' }),
    },
    {
      id: 'save-view',
      label: '保存当前筛选为视图',
      hint: '动作',
      run: () => setPrompt({ kind: 'save-view', title: '保存当前筛选', value: '' }),
    },
    {
      id: 'refresh',
      label: '刷新项目库',
      hint: '动作',
      run: () => void reloadTree(),
    },
    {
      id: 'open-chat',
      label: '打开 Moon 对话',
      hint: '跳转',
      run: () => navigate('/chat'),
    },
  ], [fileInput, navigate, reloadTree]);

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
    name: isGlobal ? '全局库' : '项目库',
    meta: isGlobal ? '可跨项目复用的全局素材' : '项目素材与全局素材',
    expanded: expanded.has('root'),
    hasChildren: true,
    children: expanded.has('root') ? [
      {
        id: 'folders',
        kind: 'system',
        name: isGlobal ? '全局目录' : '项目目录',
        meta: isGlobal
          ? `${folderTree.length} 个目录`
          : `${currentProject?.name || '选择项目'} · ${folderTree.length} 个目录`,
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
      ...(includeGlobal ? [{
        id: 'global',
        kind: 'system' as const,
        name: '全局素材',
        meta: `${globalTotal} 项`,
        expanded: expanded.has('global'),
        hasChildren: globalTotal > 0,
        children: expanded.has('global')
          ? folderAssetChildren(
              'GLOBAL',
              assetsByFolder,
              loadedFolders,
              (assetsByFolder.GLOBAL?.length || 0) < globalTotal,
            )
          : undefined,
      }] : []),
    ] : undefined,
  }), [
    assetsByFolder,
    currentProject?.name,
    expanded,
    folderNodes,
    folderTree.length,
    globalTotal,
    groupItemsByGroup,
    groups,
    includeGlobal,
    isGlobal,
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
      : nodeId === 'unfiled' ? 'UNFILED' : nodeId === 'global' ? 'GLOBAL' : nodeId;
    const next = new Set(expanded);
    if (next.has(nodeId)) {
      next.delete(nodeId);
    } else {
      next.add(nodeId);
      if (folderId === 'UNFILED' || folderId === 'GLOBAL'
        || folderTree.some((folder) => folder.id === folderId)) {
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
    setSmartMode(false);
    setSmartResults([]);
    setSmartColor('');
    setSimilarAsset(null);
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
    if (viewMode !== 'folders' || isTrash || isOutput || loading || filteredMode) return;
    if (!effectiveProjectId && queryScope !== 'GLOBAL') return;
    if (loadedFolders.has(currentFolderId)) return;
    void loadFolder(currentFolderId).catch((err) => {
      setError(err instanceof Error ? err.message : '加载文件夹失败');
    });
  }, [
    currentFolderId,
    effectiveProjectId,
    filteredMode,
    isOutput,
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
  const visibleGroups = activeFolderId || filteredMode || isOutput ? [] : groups;

  const currentFolderAssets = useMemo(() => {
    if (isOutput) return outputAssets;
    if (filteredMode) return Object.values(assetsByFolder).flat();
    return (assetsByFolder[currentFolderId] || []).filter((asset) => asset.type !== 'GROUP');
  }, [assetsByFolder, currentFolderId, filteredMode, isOutput, outputAssets]);

  /** 产出素材按来源镜头堆叠：同一对话 + 同一镜头的多次生成收成一张「N 版」卡片。 */
  const outputSceneStacks = useMemo(() => {
    if (!isOutput) return [];
    const byScene = new Map<string, LibraryAsset[]>();
    outputAssets.forEach((asset) => {
      if (!asset.sceneId) return;
      const group = byScene.get(asset.sceneId);
      if (group) group.push(asset);
      else byScene.set(asset.sceneId, [asset]);
    });
    return [...byScene.entries()]
      .filter(([, assets]) => assets.length > 1)
      .map(([sceneId, assets]) => ({
        sceneId,
        assets: [...assets].sort((first, second) => second.createdAt.localeCompare(first.createdAt)),
      }))
      .sort((first, second) => (
        (second.assets[0]?.createdAt || '').localeCompare(first.assets[0]?.createdAt || '')
      ));
  }, [isOutput, outputAssets]);
  const stackedAssetIds = useMemo(
    () => new Set(outputSceneStacks.flatMap((stack) => stack.assets.map((asset) => asset.id))),
    [outputSceneStacks],
  );

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

  const currentFolderExpectedCount = activeFolderId === 'GLOBAL'
    ? globalTotal
    : activeFolder?.directAssetCount ?? unfiledCount;
  const currentFolderLoaded = isOutput || filteredMode || loadedFolders.has(currentFolderId);

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

  /** 进入某个对话的产出文件夹：一个对话一个文件夹，后续列表只显示该对话的产出。 */
  const enterOutputConversation = (conversationId: string) => {
    setOutputConversationId(conversationId);
    setDetail(null);
    setSelectedIds([]);
    setSelectedFolderIds([]);
    setInspectorDismissed(true);
  };

  const leaveOutputConversation = () => {
    setOutputConversationId(null);
    setOutputAssets([]);
    setOutputTotal(0);
    setDetail(null);
    setSelectedIds([]);
    setSelectedFolderIds([]);
  };

  /** 从产出文件夹跳回 Moon 的对应对话。 */
  const openConversationInChat = (conversationId: string) => {
    if (!conversationId || conversationId === UNASSIGNED_OUTPUT) return;
    navigate('/chat', { state: { conversationId } });
  };

  const currentOutputConversation = useMemo(
    () => outputConversations.find(
      (item) => (item.conversationId || UNASSIGNED_OUTPUT) === outputConversationId,
    ) || null,
    [outputConversationId, outputConversations],
  );

  const currentOutputConversationTitle = outputConversationId
    ? (currentOutputConversation
      ? outputConversationTitle(currentOutputConversation, outputConversationTitles)
      : outputConversationId === UNASSIGNED_OUTPUT
        ? '未归属产出'
        : outputConversationTitles[outputConversationId]?.title || '产出素材')
    : '产出素材';

  /** 项目内可绑定的对话，按最近更新排序；Moon 里当前打开的对话排在选择器里标记「当前」。 */
  const conversationOptions = useMemo(
    () => Object.entries(outputConversationTitles)
      .map(([id, value]) => ({
        id,
        title: value.title?.trim() || `对话 · ${id.slice(0, 8)}`,
        updatedAt: value.updatedAt || '',
      }))
      .sort((first, second) => second.updatedAt.localeCompare(first.updatedAt))
      .map(({ id, title }) => ({ id, title })),
    [outputConversationTitles],
  );
  /** 目标对话失效（切项目 / 对话被删 / 还没加载完）时回落到当前对话或列表第一个。 */
  useEffect(() => {
    if (bindingTargetConversationId && outputConversationTitles[bindingTargetConversationId]) return;
    const fallback = activeConversationId && outputConversationTitles[activeConversationId]
      ? activeConversationId
      : conversationOptions[0]?.id || '';
    setBindingTargetConversationId(fallback);
  }, [activeConversationId, bindingTargetConversationId, conversationOptions, outputConversationTitles]);
  const canBindToSelectedConversation = Boolean(
    bindingTargetConversationId && outputConversationTitles[bindingTargetConversationId],
  );

  /** 资产组使用情况（被哪些对话绑定 + 快照信息）是增强信息，读取失败不影响素材浏览。 */
  const loadGroupUsage = useCallback(async () => {
    if (!effectiveProjectId || isTrash || isOutput) {
      setGroupInsights({});
      setGroupBindings({});
      return;
    }
    try {
      const [insightResponse, bindingResponse] = await Promise.all([
        libraryApi.listGroupInsights(effectiveProjectId),
        agentApi.listProjectAssetGroupBindings(effectiveProjectId).catch(() => null),
      ]);
      const nextInsights: Record<string, LibraryGroupInsight> = {};
      (insightResponse.data.data || []).forEach((item) => {
        nextInsights[item.groupId] = item;
      });
      setGroupInsights(nextInsights);
      const nextBindings: Record<string, ProjectAssetGroupBinding[]> = {};
      (bindingResponse?.data.data || []).forEach((binding) => {
        (nextBindings[binding.groupId] ||= []).push(binding);
      });
      setGroupBindings(nextBindings);
    } catch {
      // 使用情况读不到时保留空态，不打断项目库主流程
    }
  }, [effectiveProjectId, isOutput, isTrash]);

  useEffect(() => {
    void loadGroupUsage();
  }, [loadGroupUsage]);

  // 保存视图只在项目库/全局库使用；产出/重复项/回收站不显示。
  useEffect(() => {
    if (!effectiveProjectId || isTrash || isOutput || isDuplicates || isUnused) {
      setSavedViews([]);
      setActiveViewId('');
      return;
    }
    let cancelled = false;
    void libraryApi.listSavedViews(effectiveProjectId)
      .then((response) => {
        if (!cancelled) setSavedViews(response.data.data || []);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [effectiveProjectId, isDuplicates, isOutput, isTrash, isUnused]);

  // 素材详情打开时统计使用位置；切走或关闭详情即清空。
  useEffect(() => {
    const assetId = detail?.asset.id;
    if (!assetId) {
      setAssetUsage(null);
      setUsageLoading(false);
      return;
    }
    let cancelled = false;
    setUsageLoading(true);
    void libraryApi.getAssetUsage(assetId)
      .then((response) => {
        if (!cancelled) setAssetUsage(response.data.data);
      })
      .catch(() => {
        if (!cancelled) setAssetUsage(null);
      })
      .finally(() => {
        if (!cancelled) setUsageLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [detail?.asset.id]);

  const runSmartSearch = useCallback(async (override?: {
    q?: string;
    color?: string;
    similarTo?: string;
  }) => {
    if (!effectiveProjectId) return;
    const text = (override?.q ?? query).trim();
    const color = override?.color ?? smartColor;
    const similarTo = override?.similarTo ?? similarAsset?.id;
    if (!text && !color && !similarTo) {
      setSmartResults([]);
      return;
    }
    setSmartLoading(true);
    setError('');
    try {
      const response = await libraryApi.searchAssets({
        projectId: effectiveProjectId,
        q: text || undefined,
        color: color || undefined,
        similarTo,
        limit: 30,
      });
      setSmartResults(response.data.data || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : '智能搜索失败');
      setSmartResults([]);
    } finally {
      setSmartLoading(false);
    }
  }, [effectiveProjectId, query, similarAsset?.id, smartColor]);

  useEffect(() => {
    if (!smartMode) return;
    const timer = window.setTimeout(() => void runSmartSearch(), 320);
    return () => window.clearTimeout(timer);
  }, [runSmartSearch, smartMode]);

  useEffect(() => {
    if (scopeView === 'PROJECT') return;
    setSmartMode(false);
    setSmartResults([]);
    setSmartColor('');
    setSimilarAsset(null);
  }, [scopeView]);

  const exitSmartSearch = () => {
    setSmartMode(false);
    setSmartResults([]);
    setSmartColor('');
    setSimilarAsset(null);
  };

  const currentViewFilters = useCallback((): Record<string, unknown> => {    const filters: Record<string, unknown> = {};
    if (query.trim()) filters.q = query.trim();
    if (type) filters.type = type;
    if (selectedTagIds.length > 0) filters.tagIds = selectedTagIds;
    if (minRating > 0) filters.minRating = minRating;
    if (flagFilter) filters.flag = flagFilter;
    if (colorFilter) filters.colorLabel = colorFilter;
    return filters;
  }, [colorFilter, flagFilter, minRating, query, selectedTagIds, type]);

  const clearAllFilters = () => {
    setQuery('');
    setType('');
    setSelectedTagIds([]);
    setMinRating(0);
    setFlagFilter('');
    setColorFilter('');
    setActiveViewId('');
    setActiveFolderId(null);
    setActiveGroup(null);
    setDetail(null);
    setSelectedIds([]);
  };

  const applySavedView = (view: LibrarySavedView) => {
    const filters = view.filters || {};
    setQuery(typeof filters.q === 'string' ? filters.q : '');
    setType(typeof filters.type === 'string' ? filters.type as AssetType : '');
    setSelectedTagIds(Array.isArray(filters.tagIds)
      ? filters.tagIds.map(String) : []);
    setMinRating(typeof filters.minRating === 'number' ? filters.minRating : 0);
    setFlagFilter(filters.flag === 'pick' || filters.flag === 'reject' ? filters.flag : '');
    setColorFilter(typeof filters.colorLabel === 'string'
      ? filters.colorLabel as AssetColorLabel : '');
    setViewMode(view.viewMode === 'tree' ? 'tree' : 'folders');
    setActiveViewId(view.id);
  };

  const toggleSavedViewPin = async (view: LibrarySavedView) => {
    try {
      const response = await libraryApi.updateSavedView(view.id, { pinned: !view.pinned });
      setSavedViews((current) => current
        .map((item) => (item.id === view.id ? response.data.data : item))
        .sort((first, second) => Number(second.pinned) - Number(first.pinned)));
    } catch (err) {
      setError(err instanceof Error ? err.message : '更新视图失败');
    }
  };

  const deleteSavedView = async (view: LibrarySavedView) => {
    try {
      await libraryApi.deleteSavedView(view.id);
      setSavedViews((current) => current.filter((item) => item.id !== view.id));
      if (activeViewId === view.id) setActiveViewId('');
      notify({ title: '已删除视图', description: `「${view.name}」` });
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除视图失败');
    }
  };

  const patchAssetEverywhere = useCallback((updated: LibraryAsset) => {
    const patch = (list: LibraryAsset[]) => list.map((asset) => (
      asset.id === updated.id ? { ...asset, ...updated } : asset
    ));
    setAssetsByFolder((current) => Object.fromEntries(
      Object.entries(current).map(([key, list]) => [key, patch(list)]),
    ));
    setOutputAssets(patch);
    setTrashAssets(patch);
    setSmartResults(patch);
    setDetail((current) => (
      current && current.asset.id === updated.id
        ? { ...current, asset: updated }
        : current
    ));
  }, []);

  const markAsset = async (asset: LibraryAsset, marks: {
    rating?: number;
    flag?: AssetFlag | null;
    colorLabel?: AssetColorLabel | null;
  }) => {
    try {
      const response = await libraryApi.markAsset(asset.id, marks);
      patchAssetEverywhere(response.data.data);
    } catch (err) {
      setError(err instanceof Error ? err.message : '标记素材失败');
    }
  };

  /** 键盘挑片：对当前选中的素材逐张保留各自的其他标记，只改目标字段。 */
  const markSelectedAssets = useCallback(async (patch: {
    rating?: number;
    flag?: AssetFlag | null;
  }) => {
    if (selectedAssets.length === 0) return;
    try {
      await Promise.all(selectedAssets.map(async (asset) => {
        const response = await libraryApi.markAsset(asset.id, {
          rating: patch.rating ?? asset.rating ?? 0,
          flag: patch.flag === undefined ? asset.flag ?? null : patch.flag,
          colorLabel: asset.colorLabel ?? null,
        });
        patchAssetEverywhere(response.data.data);
      }));
    } catch (err) {
      setError(err instanceof Error ? err.message : '批量标记失败');
    }
  }, [patchAssetEverywhere, selectedAssets]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (!target) return;
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) || target.isContentEditable) return;
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if (selectedIds.length === 0) return;
      const key = event.key.toLowerCase();
      if (/^[0-5]$/.test(key)) {
        event.preventDefault();
        void markSelectedAssets({ rating: Number(key) });
        return;
      }
      if (key === 'p') {
        event.preventDefault();
        void markSelectedAssets({ flag: 'pick' });
      } else if (key === 'x') {
        event.preventDefault();
        void markSelectedAssets({ flag: 'reject' });
      } else if (key === 'u') {
        event.preventDefault();
        void markSelectedAssets({ flag: null });
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [markSelectedAssets, selectedIds.length]);

  const bindGroupToConversation = async (group: LibraryAsset) => {
    const conversationId = bindingTargetConversationId;
    const title = conversationId ? outputConversationTitles[conversationId]?.title : undefined;
    if (!canBindToSelectedConversation || !conversationId || !title) return;
    setGroupBindingBusy(true);
    try {
      await agentApi.attachConversationAssetGroup(conversationId, group.id);
      await loadGroupUsage();
      notify({
        title: '已绑定资产组',
        description: `「${group.name}」已绑定到「${title}」`,
        variant: 'success',
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : '绑定资产组失败');
    } finally {
      setGroupBindingBusy(false);
    }
  };

  const unbindGroupFromConversation = async (
    group: LibraryAsset,
    conversationId: string,
    title: string,
  ) => {
    setGroupBindingBusy(true);
    try {
      await agentApi.removeConversationAssetGroup(conversationId, group.id);
      await loadGroupUsage();
      notify({
        title: '已解除绑定',
        description: `「${group.name}」不再绑定「${title}」`,
        variant: 'success',
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : '解除资产组绑定失败');
    } finally {
      setGroupBindingBusy(false);
    }
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

  /** 文档类素材双击卡片直接放大预览：先取详情拿到可鉴权的 contentUrl。 */
  const openDocumentPreview = async (asset: LibraryAsset) => {
    try {
      const response = await libraryApi.getAsset(asset.id);
      const media = response.data.data.media || [];
      const primary = media.find((item) => item.primary) || media[0];
      if (!primary) return;
      setDocumentPreview({
        url: primary.contentUrl,
        name: primary.fileName || asset.name,
        mimeType: primary.mimeType,
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : '打开文档预览失败');
    }
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
    let skipToast = false;
    try {
      if (prompt.kind === 'save-view') {
        if (!effectiveProjectId) throw new Error('请先选择项目');
        const response = await libraryApi.createSavedView({
          projectId: effectiveProjectId,
          name: prompt.value.trim(),
          filters: currentViewFilters(),
          sort: {},
          viewMode,
          pinned: false,
        });
        setSavedViews((current) => [response.data.data, ...current]);
        setActiveViewId(response.data.data.id);
        notify({ title: '已保存视图', description: `「${prompt.value.trim()}」` });
        setPrompt(null);
        return;
      }
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
          skipToast = true;
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
      if (!skipToast) {
        notify({ title: promptSuccessTitle(prompt.kind), description: `「${prompt.value.trim()}」` });
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
      notify({ title: '已删除目录', description: `「${folder.name}」` });
    } catch (err) {
      const message = err instanceof Error ? err.message : '目录删除失败';
      setError(message);
      notify({ title: '删除目录失败', description: message, variant: 'error' });
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
      notify({
        title: selected.length === 1 ? '已移入回收站' : `已将 ${selected.length} 项移入回收站`,
        description: selected.length === 1 ? `「${asset.name}」` : undefined,
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : '移入回收站失败';
      setError(message);
      notify({ title: '移入回收站失败', description: message, variant: 'error' });
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
      notify({
        title: folderId === 'UNFILED'
          ? `已将 ${assetIds.length} 项移出归档`
          : `已移动 ${assetIds.length} 项素材`,
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : '移动素材失败';
      setError(message);
      notify({ title: '移动素材失败', description: message, variant: 'error' });
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
    if (topLevel.length > 0) notify({ title: `已移动 ${topLevel.length} 个目录` });
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
      if (target instanceof HTMLElement && target.closest('input, textarea, select, [contenteditable="true"]')) return;
      const key = event.key.toLowerCase();
      const withCommand = event.ctrlKey || event.metaKey;
      if (!withCommand && event.key !== 'Delete') return;

      if (withCommand && key === 'a' && (showFolders || isTrash)) {
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

  const runDirectUpload = async (file: File, taskId: string, uploadedIds: string[]) => {
    libraryUploadRetryFiles.set(taskId, file);
    upsertTask({
      id: taskId,
      kind: 'upload',
      label: file.name,
      status: 'queued',
      progress: 0,
      retryProjectId: effectiveProjectId,
    });
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
      updateTask(taskId, { status: 'completed', progress: 100, assetId: response.data.data.id });
      libraryUploadRetryFiles.delete(taskId);
      if (response.data.data.type === 'IMAGE' || response.data.data.type === 'VIDEO'
        || response.data.data.type === 'AUDIO') {
        uploadedIds.push(response.data.data.id);
      }
    } catch (err) {
      updateTask(taskId, { status: 'failed', error: err instanceof Error ? err.message : '上传失败' });
    }
  };

  const uploadDirectItems = async (items: ExternalUploadItem[]) => {
    if (items.length === 0) return;
    const uploadedIds: string[] = [];
    await Promise.all(items.map(async ({ file }) => {
      const taskId = `upload:${crypto.randomUUID()}`;
      await runDirectUpload(file, taskId, uploadedIds);
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
    }));
    if (!entries.length) {
      setError('目录中没有可导入的文件');
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
        upsertTask({
          id: taskId,
          kind: 'import',
          label: entry.fileName,
          status: 'queued',
          progress: 0,
          jobId: scan.jobId,
          retryProjectId: effectiveProjectId,
        });
        try {
          const response = await libraryApi.uploadImportItem(
            scan.jobId,
            entry.itemKey,
            scan.confirmationToken,
            file,
            (progress) => updateTask(taskId, { status: 'uploading', progress }),
          );
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

  const retryLibraryTask = async (task: LibraryTask) => {
    if (task.retryProjectId && task.retryProjectId !== effectiveProjectId) {
      updateTask(task.id, {
        status: 'failed',
        error: '该任务属于另一个项目，请切换到原项目后重试',
      });
      return;
    }
    if (task.kind === 'analysis') {
      if (!task.assetId) return;
      updateTask(task.id, { status: 'processing', error: undefined, progress: 20 });
      try {
        await libraryApi.requestAnalysis([task.assetId]);
        notify({ title: '已重新提交分析', description: task.label });
      } catch (err) {
        updateTask(task.id, {
          status: 'failed',
          error: err instanceof Error ? err.message : '重新提交分析失败',
        });
      }
      return;
    }
    if (task.kind === 'import' && task.retryUrl && effectiveProjectId) {
      updateTask(task.id, { status: 'processing', error: undefined, progress: 20 });
      try {
        const response = await libraryApi.registerGeneratedOutput({
          producer: task.retryProducer ?? 'moon-chat',
          projectId: task.retryProjectId ?? effectiveProjectId,
          generationRecordId: `retry:${task.id}`,
          resultIndex: 0,
          resultUrl: task.retryUrl,
          mediaType: task.retryMediaType ?? 'IMAGE',
        });
        updateTask(task.id, {
          status: 'completed',
          progress: 100,
          assetId: response.data.data.id,
          error: undefined,
        });
        await reloadTree();
        notify({ title: '已重新导入', description: task.label });
      } catch (err) {
        updateTask(task.id, {
          status: 'failed',
          error: err instanceof Error ? err.message : '重新导入失败',
        });
      }
      return;
    }
    if (task.kind === 'upload') {
      const file = libraryUploadRetryFiles.get(task.id);
      if (!file) {
        updateTask(task.id, {
          status: 'failed',
          error: '刷新后原始文件已丢失，请重新选择文件上传',
        });
        return;
      }
      const uploadedIds: string[] = [];
      await runDirectUpload(file, task.id, uploadedIds);
      await reloadTree();
      if (activeFolderId) await loadFolder(activeFolderId, 1, true);
      return;
    }
    updateTask(task.id, {
      status: 'failed',
      error: '该任务需要重新选择文件或重新发起',
    });
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
    // 产出素材是生成结果的只读视图：素材归属由对话决定，不接受外部拖入。
    if (isOutput) return;
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
    if (isOutput) return;
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
    if (isOutput) return;
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
        conversationId: payload.conversationId,
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
    try {
      await libraryApi.restore(asset.id);
      await reloadTree();
      notify({ title: '已从回收站恢复', description: `「${asset.name}」` });
    } catch (err) {
      const message = err instanceof Error ? err.message : '恢复失败';
      setError(message);
      notify({ title: '恢复素材失败', description: message, variant: 'error' });
    }
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
      if (failed > 0) {
        setError(`${failed} 项删除失败，可能存在项目或资产组引用`);
        notify({
          title: `已彻底删除 ${assets.length - failed} 项，${failed} 项失败`,
          description: '失败项可能被项目或资产组引用',
          variant: 'error',
        });
      } else {
        notify({ title: `已彻底删除 ${assets.length} 项素材` });
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : '彻底删除失败';
      setError(message);
      notify({ title: '彻底删除失败', description: message, variant: 'error' });
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

  /** 手动触发 AI 完善后，任务面板、卡片与详情同步进入「AI 生成中」。 */
  const markAnalysisQueued = (items: AnalysisRequestItem[]) => {
    items.forEach((item) => addAnalysisTask(item.assetId, item.name, 'AI_GENERATING'));
    const queued = new Set(items.map((item) => item.assetId));
    setAssetsByFolder((current) => Object.fromEntries(
      Object.entries(current).map(([folderId, assets]) => [
        folderId,
        assets.map((asset) => queued.has(asset.id)
          ? { ...asset, metadataState: 'AI_GENERATING' as MetadataState }
          : asset),
      ]),
    ));
    setDetail((current) => current && queued.has(current.asset.id)
      ? { ...current, asset: { ...current.asset, metadataState: 'AI_GENERATING' as MetadataState } }
      : current);
  };

  const requestAssetAiEnrichment = async (asset: LibraryAsset) => {
    setError('');
    try {
      const response = await libraryApi.requestAnalysis([asset.id]);
      const result = response.data.data;
      if (!result.items.length) {
        setError('该素材暂时不支持 AI 完善（目前仅支持图片）');
        return;
      }
      markAnalysisQueued(result.items);
    } catch (err) {
      setError(err instanceof Error ? err.message : '触发 AI 完善失败');
    }
  };

  const runFolderAiEnrichment = async (folder: LibraryFolderNode) => {
    setError('');
    try {
      const response = await libraryApi.requestFolderAnalysis(folder.id, {
        scope: queryScope === 'GLOBAL' ? 'GLOBAL' : 'PROJECT',
        projectId: projectForTree,
      });
      const result = response.data.data;
      if (!result.items.length) {
        setError('该目录下没有可完善的图片素材');
        return;
      }
      markAnalysisQueued(result.items);
    } catch (err) {
      setError(err instanceof Error ? err.message : '触发 AI 完善失败');
    }
  };

  const requestFolderAiEnrichment = (folder: LibraryFolderNode) => {
    if (folder.descendantAssetCount <= AI_ENRICH_CONFIRM_THRESHOLD) {
      void runFolderAiEnrichment(folder);
      return;
    }
    setConfirmation({
      title: 'AI 完善目录信息',
      message: `「${folder.name}」及子目录共有 ${folder.descendantAssetCount} 项素材，将对其中所有图片逐张调用视觉模型生成描述与标签。`,
      confirmLabel: '开始完善',
      onConfirm: () => runFolderAiEnrichment(folder),
    });
  };

  const inspectorMode = activeGroup && selectedGroupMedia
    ? 'group-media'
    : selectedAssets.length > 1
      ? 'selection'
      : detail
        ? 'asset'
        : inspectorFolder
          ? 'folder'
          : 'empty';
  const activeGroupBindings = activeGroup ? groupBindings[activeGroup.id] || [] : [];
  const activeGroupInsight = activeGroup ? groupInsights[activeGroup.id] : undefined;
  const detailGroupAsset = detail?.asset.type === 'GROUP' ? detail.asset : null;
  const detailGroupInsight = detailGroupAsset ? groupInsights[detailGroupAsset.id] : undefined;
  const stackSelectedAssets = activeVersionStack
    ? stackSelectedIds
      .map((id) => activeVersionStack.assets.find((asset) => asset.id === id))
      .filter((asset): asset is LibraryAsset => Boolean(asset))
    : [];
  const detailGroupUsage = detailGroupAsset ? {
    insight: detailGroupInsight,
    bindings: groupBindings[detailGroupAsset.id] || [],
    lastSnapshotLabel: detailGroupInsight?.lastSnapshotAt
      ? formatOutputTime(detailGroupInsight.lastSnapshotAt)
      : undefined,
    conversations: conversationOptions,
    selectedConversationId: bindingTargetConversationId,
    currentConversationId: activeConversationId || undefined,
    busy: groupBindingBusy,
    onSelectConversation: setBindingTargetConversationId,
    onBind: () => void bindGroupToConversation(detailGroupAsset),
    onUnbind: (conversationId: string, title: string) => void unbindGroupFromConversation(
      detailGroupAsset,
      conversationId,
      title,
    ),
    onOpenConversation: openConversationInChat,
  } : undefined;
  const showInspector = !detailLoading && inspectorMode !== 'empty' && !inspectorDismissed;
  const detailViewOpen = showInspector;
  const closeInspector = () => setInspectorDismissed(true);

  const renderAssetCard = (asset: LibraryAsset) => (
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
      onDoubleClick={() => {
        if (asset.type === 'DOCUMENT') void openDocumentPreview(asset);
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
      <span className="library-folder-grid__asset-preview">
        <AssetPreview asset={asset} />
        {((asset.rating ?? 0) > 0 || asset.flag || asset.colorLabel) && (
          <span className="library-asset-marks">
            {asset.colorLabel && (
              <i className={`is-${asset.colorLabel}`} aria-label={`色标 ${asset.colorLabel}`} />
            )}
            {asset.flag === 'pick' && <CheckCircle2 size={11} aria-label="已选" />}
            {asset.flag === 'reject' && <X size={11} aria-label="已弃" />}
            {(asset.rating ?? 0) > 0 && <em>{asset.rating}</em>}
          </span>
        )}
      </span>
      <strong title={asset.name}>{asset.name}</strong>
      <small>
        {asset.type}
        {asset.metadataState === 'NONE' ? '' : ` · ${METADATA_LABEL[asset.metadataState]}`}
        {' · '}v{asset.version}
      </small>
    </button>
  );

  return (
    <div
      className={`library-workspace${internalDragActive ? ' is-dragging-assets' : ''}${isMobile && !isEmbedded ? ' has-mobile-nav' : ''}`}
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
                setOutputConversationId(null);
                if (item.id === 'TRASH') setType('');
                if (item.id === 'OUTPUT' && type === 'GROUP') setType('');
                if (item.id === 'DUPLICATES') setType('');
                if (item.id === 'UNUSED') setType('');
                if (item.id === 'UNUSED') {
                  setQuery('');
                  setSelectedTagIds([]);
                  setMinRating(0);
                  setFlagFilter('');
                  setColorFilter('');
                }
              }}
            >
              {item.icon}<span>{item.label}</span>
            </button>
          ))}
        </nav>
        {!isTrash && !isOutput && !isDuplicates && !isUnused && (
          <section className="library-saved-views" aria-label="我的视图">
            <header>
              <span>我的视图</span>
              <Tooltip side="bottom" label="保存当前筛选">
                <button
                  type="button"
                  aria-label="保存当前筛选"
                  disabled={!effectiveProjectId}
                  onClick={() => setPrompt({
                    kind: 'save-view',
                    title: '保存当前筛选',
                    value: '',
                  })}
                >
                  ＋
                </button>
              </Tooltip>
            </header>
            <button
              type="button"
              className={!activeViewId ? 'is-active' : ''}
              onClick={clearAllFilters}
            >
              全部素材
            </button>
            {savedViews.map((view) => (
              <div key={view.id} className="library-saved-views__row">
                <button
                  type="button"
                  className={activeViewId === view.id ? 'is-active' : ''}
                  title={view.name}
                  onClick={() => applySavedView(view)}
                >
                  {view.pinned ? '★ ' : ''}{view.name}
                </button>
                <button
                  type="button"
                  className="is-icon"
                  aria-label={`${view.pinned ? '取消固定' : '固定'} ${view.name}`}
                  onClick={() => void toggleSavedViewPin(view)}
                >
                  {view.pinned ? '★' : '☆'}
                </button>
                <button
                  type="button"
                  className="is-icon"
                  aria-label={`删除视图 ${view.name}`}
                  onClick={() => void deleteSavedView(view)}
                >
                  ×
                </button>
              </div>
            ))}
            {savedViews.length === 0 && (
              <small>保存当前筛选，下次一键恢复</small>
            )}
          </section>
        )}
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
            <h1>{isTrash ? '回收站' : isOutput ? '产出素材' : isDuplicates ? '重复项' : isUnused ? '未使用' : isGlobal ? '全局库' : '项目库'}</h1>
          </div>
          <div className="library-workspace__actions">
            <button onClick={() => setPanelOpen(!panelOpen)}>
              <ListChecks size={15} /> 任务
              {tasks.some((task) => task.status !== 'completed' && task.status !== 'failed')
                ? ` ${tasks.filter((task) => task.status !== 'completed' && task.status !== 'failed').length}`
                : ''}
            </button>
            {!isTrash && !isOutput && !isDuplicates && !isUnused && activeGroup && (
              <button className="is-primary" onClick={() => fileInput.current?.click()}>
                <Upload size={15} /> 上传文件
              </button>
            )}
            {!isTrash && !isOutput && !isDuplicates && !isUnused && !activeGroup && (
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
            {!isUnused && (
              <label>
                <Search size={15} />
                <input
                  value={query}
                  onChange={(event) => {
                    setQuery(event.target.value);
                    if (similarAsset) setSimilarAsset(null);
                  }}
                  placeholder={isOutput ? '搜索产出素材（名称/标签/描述）' : '搜索名称、标签、描述'}
                />
                {query && <button onClick={() => setQuery('')}><X size={13} /></button>}
              </label>
            )}
            {!isUnused && scopeView === 'PROJECT' && (
              <Tooltip side="bottom" label="智能搜索：AI 描述模糊匹配 / 找相似">
                <button
                  type="button"
                  className={`library-smart-toggle${smartMode ? ' is-active' : ''}`}
                  aria-pressed={smartMode}
                  onClick={() => {
                    if (smartMode) {
                      exitSmartSearch();
                    } else {
                      setSmartMode(true);
                      setSimilarAsset(null);
                    }
                  }}
                >
                  <Sparkles size={14} /> 智能
                </button>
              </Tooltip>
            )}
            {scopeView !== 'GLOBAL' && (
              <select value={effectiveProjectId} onChange={(event) => {
                const projectId = event.target.value;
                setSelectedProjectId(projectId);
                setOutputConversationId(null);
                if (projectId) void loadProject(projectId).catch(() => undefined);
              }}>
                {projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
              </select>
            )}
            {!isDuplicates && !isUnused && (
              <select value={type} onChange={(event) => setType(event.target.value as AssetType | '')}>
                <option value="">全部类型</option>
                <option value="IMAGE">图片</option>
                <option value="VIDEO">视频</option>
                <option value="AUDIO">音频</option>
                <option value="DOCUMENT">文档</option>
                <option value="OTHER">其他</option>
                {!isOutput && <option value="GROUP">资产组</option>}
              </select>
            )}
            {!isDuplicates && !isUnused && (
              <select
                aria-label="按评分筛选"
                value={minRating}
                onChange={(event) => setMinRating(Number(event.target.value))}
              >
                <option value={0}>全部评分</option>
                {[5, 4, 3, 2, 1].map((value) => (
                  <option key={value} value={value}>{value} 星及以上</option>
                ))}
              </select>
            )}
            {!isDuplicates && !isUnused && (
              <select
                aria-label="按旗标筛选"
                value={flagFilter}
                onChange={(event) => setFlagFilter(event.target.value as '' | AssetFlag)}
              >
                <option value="">全部标记</option>
                <option value="pick">已选</option>
                <option value="reject">已弃</option>
              </select>
            )}
            {!isDuplicates && !isUnused && (
              <select
                aria-label="按色标筛选"
                value={colorFilter}
                onChange={(event) => setColorFilter(event.target.value as '' | AssetColorLabel)}
              >
                <option value="">全部色标</option>
                <option value="red">红色</option>
                <option value="yellow">黄色</option>
                <option value="green">绿色</option>
                <option value="blue">蓝色</option>
                <option value="purple">紫色</option>
              </select>
            )}
            {!isOutput && !isDuplicates && !isUnused && (
              <div className="library-workspace__view-toggle" role="group" aria-label="显示方式">
                <Tooltip side="bottom" label="文件夹视图">
                <button
                  type="button"
                  className={viewMode === 'folders' ? 'is-active' : ''}
                  aria-pressed={viewMode === 'folders'}
                  aria-label="文件夹视图"
                  onClick={() => setViewMode('folders')}
                >
                  <LayoutGrid size={16} />
                </button>
                </Tooltip>
                <Tooltip side="bottom" label="树形视图">
                <button
                  type="button"
                  className={viewMode === 'tree' ? 'is-active' : ''}
                  aria-pressed={viewMode === 'tree'}
                  aria-label="树形视图"
                  onClick={() => setViewMode('tree')}
                >
                  <ListTree size={16} />
                </button>
                </Tooltip>
              </div>
            )}
          </section>
        )}

        {smartMode && (
          <section className="library-smart-bar" aria-label="智能搜索条件">
            <div className="library-smart-bar__colors">
              {SMART_COLORS.map((color) => (
                <button
                  key={color.hex}
                  type="button"
                  className={smartColor === color.hex ? 'is-active' : ''}
                  style={{ background: color.hex }}
                  aria-label={`主色 ${color.label}`}
                  title={color.label}
                  onClick={() => {
                    setSimilarAsset(null);
                    setSmartColor(smartColor === color.hex ? '' : color.hex);
                  }}
                />
              ))}
            </div>
            <span>{similarAsset ? `与「${similarAsset.name}」相似` : smartColor ? '按主色接近度排序' : '按名称 / AI 描述模糊匹配'}</span>
            {similarAsset && (
              <button type="button" onClick={() => setSimilarAsset(null)}>取消相似</button>
            )}
            <button
              type="button"
              onClick={() => {
                setQuery('');
                setSmartColor('');
                setSimilarAsset(null);
              }}
            >
              清空条件
            </button>
          </section>
        )}

        {tags.length > 0 && !isTrash && !isDuplicates && !isUnused && (
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
              <Tooltip side="bottom" label={tagsExpanded ? '收起标签' : '显示全部标签'}>
              <button
                type="button"
                aria-label={tagsExpanded ? '收起更多标签' : '展开更多标签'}
                onClick={() => setTagsExpanded((current) => !current)}
              >
                …
              </button>
              </Tooltip>
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
        ) : isDuplicates ? (
          <section className="library-duplicates">
            <header className="library-duplicates__header">
              <div>
                <h1>重复项</h1>
                <p>同一份文件被多个素材引用。合并会保留一份，并把标签、目录和资产组引用迁移过去。</p>
              </div>
              <div className="library-duplicates__summary">
                <span><b>{duplicateTotal}</b> 组重复</span>
                <span>约可回收 <b>{formatBytes(duplicateReclaimableBytes)}</b></span>
                <button
                  type="button"
                  onClick={() => void loadDuplicates(1, false)}
                  disabled={duplicatesLoading}
                >
                  {duplicatesLoading ? '刷新中…' : '刷新'}
                </button>
              </div>
            </header>

            {duplicatesLoading ? (
              <div className="library-workspace__loading"><Sparkles size={20} /> 正在查找重复素材…</div>
            ) : visibleDuplicateGroups.length === 0 ? (
              <div className="library-duplicates__empty">
                <span><Copy size={24} /></span>
                <strong>{query.trim() ? '没有匹配的重复项' : '没有发现重复素材'}</strong>
                <p>同一份文件被上传或生成多次时会自动归组；合并后重复项进入回收站保留 1 天。</p>
              </div>
            ) : (
              <div className="library-duplicates__grid">
                {visibleDuplicateGroups.map((group) => {
                  const first = group.assets[0];
                  return (
                    <article
                      key={group.blobId}
                      className="library-duplicate-card"
                      role="button"
                      tabIndex={0}
                      aria-label={`${group.assets.length} 份重复，合并`}
                      onClick={() => openDuplicateGroup(group)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault();
                          openDuplicateGroup(group);
                        }
                      }}
                    >
                      <div className="library-duplicate-card__preview">
                        {first?.coverUrl
                          ? <img src={assetUrl(libraryThumbnailUrl(first.coverUrl))} alt="" loading="lazy" />
                          : <Copy size={28} />}
                        <span className="library-duplicate-card__count">{group.assets.length} 份</span>
                      </div>
                      <div className="library-duplicate-card__body">
                        <strong>{group.assets.length} 份重复 · {formatBytes(group.sizeBytes)}</strong>
                        <span>
                          {group.assets.slice(0, 3).map((asset) => asset.name).join(' · ')}
                          {group.assets.length > 3 ? ` 等 ${group.assets.length} 份` : ''}
                        </span>
                        <small>
                          {group.assets[0]?.folderPath?.join(' / ') || '未归档'}
                          {' · '}{group.mimeType || '文件'}
                        </small>
                      </div>
                      <span className="library-duplicate-card__action">合并</span>
                    </article>
                  );
                })}
              </div>
            )}
            {duplicateGroups.length < duplicateTotal && (
              <div className="library-folder-browser__load-more">
                <button
                  type="button"
                  disabled={duplicatesLoading}
                  onClick={() => void loadDuplicates(Math.floor(duplicateGroups.length / 20) + 1, true)}
                >
                  {duplicatesLoading ? '加载中…' : `加载更多（${duplicateGroups.length}/${duplicateTotal}）`}
                </button>
              </div>
            )}
          </section>
        ) : isUnused ? (
          <section className="library-unused">
            <header className="library-unused__header">
              <div>
                <h1>未使用素材</h1>
                <p>没有被分镜、资产组或画布引用。清理后进入回收站，保留 1 天可恢复。</p>
              </div>
              <div className="library-unused__summary">
                <span><b>{unusedTotal}</b> 项未使用</span>
                <button
                  type="button"
                  className="is-danger"
                  disabled={selectedIds.length === 0 || busy}
                  onClick={() => void trashUnusedSelection()}
                >
                  <Trash2 size={14} /> 清理选中（{selectedIds.length}）
                </button>
                <button
                  type="button"
                  disabled={unusedLoading}
                  onClick={() => void loadUnusedAssets(1, false)}
                >
                  {unusedLoading ? '刷新中…' : '刷新'}
                </button>
              </div>
            </header>
            {unusedLoading ? (
              <div className="library-workspace__loading"><Sparkles size={20} /> 正在统计未使用素材…</div>
            ) : unusedAssets.length === 0 ? (
              <div className="library-unused__empty">
                <span><CheckCircle2 size={24} /></span>
                <strong>没有未使用的素材</strong>
                <p>所有素材都已经在分镜、资产组或画布中使用。</p>
              </div>
            ) : (
              <>
                <div className="library-unused__grid">
                  {unusedAssets.map((asset) => {
                    const selected = selectedIds.includes(asset.id);
                    return (
                      <article
                        key={asset.id}
                        className={`library-unused-card${selected ? ' is-selected' : ''}`}
                        role="button"
                        tabIndex={0}
                        aria-pressed={selected}
                        onClick={() => setSelectedIds((current) => (
                          current.includes(asset.id)
                            ? current.filter((id) => id !== asset.id)
                            : [...current, asset.id]
                        ))}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            setSelectedIds((current) => (
                              current.includes(asset.id)
                                ? current.filter((id) => id !== asset.id)
                                : [...current, asset.id]
                            ));
                          }
                        }}
                      >
                        <div className="library-unused-card__preview">
                          <AssetPreview asset={asset} />
                          <span className="library-unused-card__check">
                            {selected ? <CheckCircle2 size={14} /> : null}
                          </span>
                        </div>
                        <strong title={asset.name}>{asset.name}</strong>
                        <small>
                          {asset.type} · {formatOutputTime(asset.updatedAt)}
                        </small>
                      </article>
                    );
                  })}
                </div>
                {unusedAssets.length < unusedTotal && (
                  <div className="library-folder-browser__load-more">
                    <button
                      type="button"
                      disabled={unusedLoading}
                      onClick={() => void loadUnusedAssets(Math.floor(unusedAssets.length / 40) + 1, true)}
                    >
                      {unusedLoading ? '加载中…' : `加载更多（${unusedAssets.length}/${unusedTotal}）`}
                    </button>
                  </div>
                )}
              </>
            )}
          </section>
        ) : smartMode ? (
          <section className="library-smart">
            <header>
              <div>
                <h2>
                  {similarAsset
                    ? `与「${similarAsset.name}」相似`
                    : smartColor ? '按主色搜索' : '智能搜索'}
                </h2>
                <span>{smartLoading ? '搜索中…' : `${smartResults.length} 项`}</span>
              </div>
              <button type="button" onClick={exitSmartSearch}>退出智能搜索</button>
            </header>
            {smartLoading ? (
              <div className="library-workspace__loading"><Sparkles size={20} /> 正在搜索…</div>
            ) : smartResults.length === 0 ? (
              <div className="library-smart__empty">
                <Sparkles size={24} />
                <strong>{smartColor ? '还没有足够的图片主色' : '没有匹配的素材'}</strong>
                <p>
                  {smartColor
                    ? '主色由后台逐步补齐；先浏览一遍素材或稍后重试。'
                    : '换一个关键词，或从素材检查器点「找相似」。'}
                </p>
              </div>
            ) : (
              <div className="library-folder-grid">
                {smartResults.map(renderAssetCard)}
              </div>
            )}
          </section>
        ) : loading ? (
          <div className="library-workspace__loading"><Sparkles size={20} /> 正在读取目录树...</div>
        ) : !effectiveProjectId ? (
          <div className="library-workspace__empty"><Package size={24} /> 暂无项目，请先在 Moon 中创建项目</div>
        ) : (
          <section className={`library-workspace__content${showInspector ? '' : ' is-inspector-empty'}${detailViewOpen ? ' is-detail-view' : ''}`}>
            <div
              ref={treeScrollRef}
              className={`library-workspace__tree${showFolders ? ' is-folders' : ''}`}
            >
              {showFolders ? (
                <div
                  className="library-folder-browser"
                  onContextMenu={(event) => {
                    if (isOutput) return;
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

                      {/* 资产组使用情况：被哪些对话绑定、快照是否过期、直接绑定当前对话 */}
                      <GroupUsagePanel
                        insight={activeGroupInsight}
                        bindings={activeGroupBindings}
                        lastSnapshotLabel={activeGroupInsight?.lastSnapshotAt
                          ? formatOutputTime(activeGroupInsight.lastSnapshotAt)
                          : undefined}
                        conversations={conversationOptions}
                        selectedConversationId={bindingTargetConversationId}
                        currentConversationId={activeConversationId || undefined}
                        busy={groupBindingBusy}
                        onSelectConversation={setBindingTargetConversationId}
                        onBind={() => void bindGroupToConversation(activeGroup)}
                        onUnbind={(conversationId, title) => void unbindGroupFromConversation(
                          activeGroup,
                          conversationId,
                          title,
                        )}
                        onOpenConversation={openConversationInChat}
                      />

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
                  ) : isOutputRoot ? (
                    <div className="library-output-browser">
                      <div className="library-folder-browser__bar">
                        <div className="library-folder-browser__breadcrumb">
                          <button type="button" onClick={() => setScopeView('OUTPUT')}>产出素材</button>
                          <span>
                            <ChevronRight size={12} />
                            <strong>按对话归档</strong>
                          </span>
                        </div>
                        <small>
                          {outputConversationsLoading
                            ? '正在整理产出…'
                            : `${outputConversations.length} 个对话 · ${outputConversations.reduce((sum, item) => sum + item.assetCount, 0)} 项素材`}
                        </small>
                      </div>

                      {outputConversationsLoading ? (
                        <div className="library-folder-browser__loading">
                          <Sparkles size={18} /> 正在整理产出素材…
                        </div>
                      ) : outputConversations.length === 0 ? (
                        filteredMode ? (
                          <div className="library-output-empty">
                            <span><Search size={24} /></span>
                            <strong>没有符合条件的产出</strong>
                            <p>当前搜索、类型或标签筛选下没有生成结果。</p>
                            <button
                              type="button"
                              onClick={() => {
                                setQuery('');
                                setType('');
                                setSelectedTagIds([]);
                              }}
                            >
                              <X size={14} /> 清空筛选
                            </button>
                          </div>
                        ) : (
                          <div className="library-output-empty">
                            <span><Sparkles size={26} /></span>
                            <strong>当前项目还没有产出素材</strong>
                            <p>在 Moon 对话里生成的图片、视频和文件会自动归到该对话的文件夹。</p>
                            <button type="button" onClick={() => navigate('/chat')}>
                              <MessageSquare size={14} /> 去 Moon 生成
                            </button>
                          </div>
                        )
                      ) : (
                        <section className="library-output-grid" aria-label="产出素材对话列表">
                          {outputConversations.map((group) => {
                            const conversationKey = group.conversationId || UNASSIGNED_OUTPUT;
                            const title = outputConversationTitle(group, outputConversationTitles);
                            const previews = (group.previewUrls.length > 0
                              ? group.previewUrls
                              : group.coverUrl ? [group.coverUrl] : []).slice(0, 4);
                            return (
                              <article
                                key={conversationKey}
                                className="library-output-card"
                                role="button"
                                tabIndex={0}
                                aria-label={`${title}，${outputCountsLabel(group)}`}
                                onClick={() => enterOutputConversation(conversationKey)}
                                onKeyDown={(event) => {
                                  if (event.key === 'Enter' || event.key === ' ') {
                                    event.preventDefault();
                                    enterOutputConversation(conversationKey);
                                  }
                                }}
                              >
                                <div className={`library-output-card__previews is-${Math.min(previews.length, 4)}`}>
                                  {previews.length > 0 ? previews.map((url, index) => (
                                    <span className="library-output-card__preview" key={`${conversationKey}-${index}`}>
                                      <img
                                        src={assetUrl(libraryThumbnailUrl(url))}
                                        alt=""
                                        loading="lazy"
                                        draggable={false}
                                      />
                                    </span>
                                  )) : (
                                    <span className="library-output-card__placeholder"><Sparkles size={22} /></span>
                                  )}
                                </div>
                                <div className="library-output-card__body">
                                  <strong title={title}>{title}</strong>
                                  <span>{outputCountsLabel(group)}</span>
                                  <small>
                                    {formatOutputTime(group.lastGeneratedAt)}
                                    {group.lastAssetName ? ` · ${group.lastAssetName}` : ''}
                                  </small>
                                </div>
                                {group.conversationId && (
                                  <button
                                    type="button"
                                    className="library-output-card__open"
                                    title="在 Moon 中打开该对话"
                                    aria-label={`打开对话：${title}`}
                                    onClick={(event) => {
                                      event.stopPropagation();
                                      openConversationInChat(group.conversationId!);
                                    }}
                                  >
                                    <MessageSquare size={13} /> 打开对话
                                  </button>
                                )}
                                <SquareArrowOutUpRight size={14} className="library-output-card__enter" aria-hidden="true" />
                              </article>
                            );
                          })}
                        </section>
                      )}
                    </div>
                  ) : (
                    <>
                      <div className="library-folder-browser__bar">
                        <button
                          type="button"
                          disabled={isOutput ? !outputConversationId : (!activeFolderId || activeFolderId === 'UNFILED')}
                          onClick={() => {
                            if (isOutput) {
                              leaveOutputConversation();
                              return;
                            }
                            const parentId = activeFolder?.parentId || null;
                            goToFolder(parentId);
                          }}
                        >
                          <ChevronLeft size={14} /> {isOutput ? '全部对话' : '上一级'}
                        </button>
                        <div className="library-folder-browser__breadcrumb">
                          <button
                            type="button"
                            onClick={() => (isOutput ? leaveOutputConversation() : goToFolder(null))}
                          >
                            {isGlobal ? '全局库' : isOutput ? '产出素材' : '项目库'}
                          </button>
                          {currentFolderTrail.map((folder) => (
                            <span key={folder.id}>
                              <ChevronRight size={12} />
                              <button type="button" onClick={() => goToFolder(folder.id)}>
                                {folder.name}
                              </button>
                            </span>
                          ))}
                          {activeFolderId === 'GLOBAL' && (
                            <span>
                              <ChevronRight size={12} />
                              <strong>全局素材</strong>
                            </span>
                          )}
                          {isOutput && outputConversationId && (
                            <span>
                              <ChevronRight size={12} />
                              <strong title={currentOutputConversationTitle}>{currentOutputConversationTitle}</strong>
                            </span>
                          )}
                        </div>
                        {isOutput && outputConversationId && outputConversationId !== UNASSIGNED_OUTPUT && (
                          <button
                            type="button"
                            className="library-folder-browser__chat"
                            onClick={() => openConversationInChat(outputConversationId)}
                          >
                            <MessageSquare size={13} /> 打开对话
                          </button>
                        )}
                        <small>
                          {activeFolderId === 'GLOBAL' || isOutput
                            ? `${currentFolderAssets.length} 项素材`
                            : `${visibleFolders.length} 个文件夹 · ${visibleGroups.length} 个资产组 · ${currentFolderAssets.length} 项素材`}
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
                          {includeGlobal && !activeFolderId && !filteredMode && (
                            <button
                              type="button"
                              className="library-folder-grid__item is-folder"
                              title="查看全局素材"
                              onClick={() => goToFolder('GLOBAL')}
                            >
                              <span className="library-folder-grid__folder-icon"><Globe2 size={48} /></span>
                              <strong>全局素材</strong>
                              <small>{globalTotal} 项</small>
                            </button>
                          )}
                          {visibleGroups.map((group) => {
                            const bindings = groupBindings[group.id] || [];
                            const insight = groupInsights[group.id];
                            return (
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
                              <small>
                                {bindings.length > 0 ? `${bindings.length} 个对话使用` : '未绑定对话'}
                                {insight ? ` · ${insight.mediaCount} 项素材` : ''}
                              </small>
                            </button>
                            );
                          })}
                          {isOutput && outputSceneStacks.map((stack) => {
                            const newest = stack.assets[0];
                            if (!newest) return null;
                            return (
                              <button
                                key={`stack:${stack.sceneId}`}
                                type="button"
                                className="library-folder-grid__item is-asset is-version-stack"
                                title="同一镜头的多个生成版本，点击对比"
                                onClick={() => openVersionStack(stack)}
                              >
                                <span className="library-folder-grid__asset-preview">
                                  <AssetPreview asset={newest} />
                                  <span className="library-version-stack__badge">
                                    {stack.assets.length} 版
                                  </span>
                                </span>
                                <strong>镜头组 · {stack.assets.length} 版</strong>
                                <small>{formatOutputTime(newest.createdAt)} · {newest.name}</small>
                              </button>
                            );
                          })}
                          {(() => {
                            const gridAssets = currentFolderAssets
                              .filter((asset) => !stackedAssetIds.has(asset.id));
                            const virtualize = gridAssets.length > 40
                              && visibleFolders.length === 0
                              && visibleGroups.length === 0
                              && outputSceneStacks.length === 0;
                            return virtualize ? (
                              <VirtualAssetGrid
                                items={gridAssets}
                                scrollRef={treeScrollRef}
                                renderItem={renderAssetCard}
                              />
                            ) : gridAssets.map(renderAssetCard);
                          })()}
                          {visibleFolders.length === 0 && visibleGroups.length === 0 && currentFolderAssets.length === 0 && (
                            <div className="library-folder-grid__empty">
                              {isOutput ? <Sparkles size={24} /> : <Folder size={24} />}
                              <span>
                                {isOutput
                                  ? (outputLoading
                                    ? '正在读取产出素材…'
                                    : outputConversationId === UNASSIGNED_OUTPUT
                                      ? '没有未归属对话的历史产出'
                                      : '这个对话还没有产出素材，回到对话里生成试试')
                                  : filteredMode ? '没有符合条件的素材' : '这个文件夹还是空的'}
                              </span>
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
                      {isOutput && outputAssets.length > 0 && outputAssets.length < outputTotal && (
                        <div className="library-folder-browser__load-more">
                          <button
                            type="button"
                            disabled={outputLoading}
                            onClick={() => void loadOutputAssets(
                              Math.floor(outputAssets.length / PAGE_SIZE) + 1,
                              true,
                            )}
                          >
                            {outputLoading ? '加载中…' : `加载更多（${outputAssets.length}/${outputTotal}）`}
                          </button>
                        </div>
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
              onPreviewDocument={setDocumentPreview}
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
              onRequestAiEnrichment={(asset) => void requestAssetAiEnrichment(asset)}
              onRequestFolderAiEnrichment={requestFolderAiEnrichment}
              conversationTitle={detail?.asset.conversationId
                ? outputConversationTitles[detail.asset.conversationId]?.title
                : undefined}
              onOpenConversation={openConversationInChat}
              groupUsage={activeGroup ? undefined : detailGroupUsage}
              onMark={(asset, marks) => void markAsset(asset, marks)}
              usage={assetUsage}
              usageLoading={usageLoading}
              onFindSimilar={(asset) => {
                setQuery('');
                setSmartColor('');
                setSimilarAsset(asset);
                setSmartMode(true);
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

      {activeVersionStack && (
        <Modal
          title={`镜头版本 · ${activeVersionStack.assets.length} 版`}
          onClose={() => setActiveVersionStack(null)}
        >
          <div className="library-version-dialog">
            <p>同一镜头由多次生成得到的版本。点选 1–2 个版本查看或并排对比。</p>
            <div className="library-version-dialog__strip">
              {activeVersionStack.assets.map((asset, index) => {
                const selected = stackSelectedIds.includes(asset.id);
                return (
                  <button
                    key={asset.id}
                    type="button"
                    className={`library-version-dialog__variant${selected ? ' is-selected' : ''}`}
                    title="最多同时选择两个版本进行对比"
                    onClick={() => toggleStackVariant(asset.id)}
                  >
                    <span className="library-version-dialog__thumb">
                      {asset.type === 'VIDEO'
                        ? <video src={assetUrl(asset.coverUrl || '')} muted preload="metadata" />
                        : asset.coverUrl
                          ? <img src={assetUrl(libraryThumbnailUrl(asset.coverUrl))} alt="" loading="lazy" />
                          : <Copy size={16} />}
                    </span>
                    <strong>v{activeVersionStack.assets.length - index}</strong>
                    <small>{formatOutputTime(asset.createdAt)}</small>
                  </button>
                );
              })}
            </div>
            <div className={`library-version-dialog__compare${stackSelectedIds.length > 1 ? ' is-split' : ''}`}>
              {stackSelectedAssets.length === 0 ? (
                <p className="library-version-dialog__hint">选择 1–2 个版本查看</p>
              ) : stackSelectedAssets.map((asset) => (
                <VersionAssetPane key={asset.id} asset={asset} />
              ))}
            </div>
            <footer>
              <small>已选 {stackSelectedIds.length} / 2 · 再点一次取消选择</small>
              <button type="button" onClick={() => setActiveVersionStack(null)}>关闭</button>
            </footer>
          </div>
        </Modal>
      )}

      {activeDuplicateGroup && (
        <Modal
          title="合并重复素材"
          onClose={() => {
            if (!duplicatesMerging) setActiveDuplicateGroup(null);
          }}
        >
          <div className="library-duplicate-dialog">
            <p>
              保留一份作为主素材；其余素材的标签、目录和资产组引用会迁移过去，然后进入回收站。
            </p>
            <div className="library-duplicate-dialog__list">
              {activeDuplicateGroup.assets.map((asset) => {
                const isSurvivor = duplicateSurvivorId === asset.id;
                const selected = duplicateSelectedIds.includes(asset.id);
                return (
                  <label
                    key={asset.id}
                    className={`library-duplicate-dialog__row${isSurvivor ? ' is-survivor' : ''}`}
                  >
                    <input
                      type="radio"
                      name="duplicate-survivor"
                      checked={isSurvivor}
                      aria-label={`保留 ${asset.name}`}
                      onChange={() => {
                        setDuplicateSurvivorId(asset.id);
                        setDuplicateSelectedIds((current) => current.filter((id) => id !== asset.id));
                      }}
                    />
                    <span className="library-duplicate-dialog__preview">
                      {asset.coverUrl
                        ? <img src={assetUrl(libraryThumbnailUrl(asset.coverUrl))} alt="" loading="lazy" />
                        : <Copy size={16} />}
                    </span>
                    <div className="library-duplicate-dialog__info">
                      <strong title={asset.name}>{asset.name}</strong>
                      <small>
                        {asset.folderPath?.join(' / ') || '未归档'}
                        {' · '}{asset.type}
                        {' · '}v{asset.version}
                      </small>
                    </div>
                    <span className="library-duplicate-dialog__tag">
                      {isSurvivor ? '保留这份' : '合并'}
                    </span>
                    <input
                      type="checkbox"
                      checked={selected}
                      disabled={isSurvivor}
                      aria-label={`合并 ${asset.name}`}
                      onChange={(event) => {
                        setDuplicateSelectedIds((current) => (
                          event.target.checked
                            ? [...current, asset.id]
                            : current.filter((id) => id !== asset.id)
                        ));
                      }}
                    />
                  </label>
                );
              })}
            </div>
            <footer>
              <small>
                将合并 {duplicateSelectedIds.filter((id) => id !== duplicateSurvivorId).length} 份，
                其余进入回收站
              </small>
              <button
                type="button"
                onClick={() => setActiveDuplicateGroup(null)}
                disabled={duplicatesMerging}
              >
                取消
              </button>
              <button
                type="button"
                className="is-primary"
                onClick={() => void mergeActiveDuplicateGroup()}
                disabled={duplicatesMerging
                  || duplicateSelectedIds.filter((id) => id !== duplicateSurvivorId).length === 0}
              >
                {duplicatesMerging ? '合并中…' : '合并并移入回收站'}
              </button>
            </footer>
          </div>
        </Modal>
      )}

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

      <DocumentPreviewModal
        target={documentPreview}
        onClose={() => setDocumentPreview(null)}
      />

      <LibraryTaskQueue onRetry={(task) => void retryLibraryTask(task)} />

      <LibraryCommandPalette
        open={commandOpen}
        onClose={() => setCommandOpen(false)}
        actions={commandActions}
        assets={paletteAssets}
        views={savedViews}
        onOpenAsset={openAssetCard}
        onApplyView={applySavedView}
      />

      {isMobile && !isEmbedded && <MobileBottomNav />}

    </div>
  );
}

const NAV_ITEMS: Array<{ id: ScopeView; label: string; icon: ReactNode }> = [
  { id: 'PROJECT', label: '项目库', icon: <Boxes size={16} /> },
  { id: 'GLOBAL', label: '全局库', icon: <Globe2 size={16} /> },
  { id: 'OUTPUT', label: '产出素材', icon: <Sparkles size={16} /> },
  { id: 'DUPLICATES', label: '重复项', icon: <Copy size={16} /> },
  { id: 'UNUSED', label: '未使用', icon: <CircleAlert size={16} /> },
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
    ...(asset.type === 'IMAGE' || asset.type === 'VIDEO' || asset.type === 'AUDIO'
      ? [{ id: 'merge', label: '合为资产组', icon: <Boxes size={14} /> }]
      : []),
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

function VersionAssetPane({ asset }: { asset: LibraryAsset }) {
  const src = asset.coverUrl ? assetUrl(asset.coverUrl) : '';
  return (
    <figure className="library-version-pane">
      <div className="library-version-pane__media">
        {asset.type === 'VIDEO' ? (
          <video src={src} controls preload="metadata" />
        ) : asset.type === 'AUDIO' ? (
          <audio src={src} controls preload="metadata" />
        ) : asset.coverUrl ? (
          <img src={src} alt={asset.name} />
        ) : (
          <Copy size={28} />
        )}
      </div>
      <figcaption>
        <strong title={asset.name}>{asset.name}</strong>
        <small>{formatOutputTime(asset.createdAt)} · {asset.type}</small>
      </figcaption>
    </figure>
  );
}

function AssetPreview({ asset }: { asset: LibraryAsset }) {
  if (asset.coverUrl && asset.type === 'VIDEO') {
    return <video src={assetUrl(asset.coverUrl)} muted preload="metadata" draggable={false} />;
  }
  if (asset.type === 'DOCUMENT' || asset.type === 'OTHER') {
    return <span><AssetIcon type={asset.type} /></span>;
  }
  if (asset.coverUrl) {
    return <img src={assetUrl(libraryThumbnailUrl(asset.coverUrl))} alt="" loading="lazy" draggable={false} />;
  }
  return <span><Package size={20} /></span>;
}

function parseAssetType(value: string | null): AssetType | '' {
  if (value === 'AI_ASSET') return 'GROUP';
  return value === 'IMAGE' || value === 'VIDEO' || value === 'AUDIO'
    || value === 'DOCUMENT' || value === 'OTHER'
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
  if (extension === 'mp4') return 'video/mp4';
  if (extension === 'mov') return 'video/quicktime';
  if (extension === 'mp3') return 'audio/mpeg';
  if (extension === 'wav') return 'audio/wav';
  if (extension === 'm4a') return 'audio/mp4';
  if (extension === 'pdf') return 'application/pdf';
  if (extension === 'doc') return 'application/msword';
  if (extension === 'docx') return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
  if (extension === 'xls') return 'application/vnd.ms-excel';
  if (extension === 'xlsx') return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
  if (extension === 'ppt') return 'application/vnd.ms-powerpoint';
  if (extension === 'pptx') return 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
  if (extension === 'csv') return 'text/csv';
  if (extension === 'md' || extension === 'markdown') return 'text/markdown';
  if (extension === 'json') return 'application/json';
  if (extension === 'html' || extension === 'htm') return 'text/html';
  if (extension === 'xml') return 'application/xml';
  if (extension === 'yaml' || extension === 'yml') return 'application/yaml';
  if (extension === 'txt' || extension === 'log') return 'text/plain';
  return '';
}
