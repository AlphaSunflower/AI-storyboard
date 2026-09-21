import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Dialog } from '@base-ui/react/dialog';
import { Slider } from '@base-ui/react/slider';
import {
  Boxes,
  Check,
  ChevronLeft,
  ChevronRight,
  CircleAlert,
  Film,
  FolderPlus,
  Grid2X2,
  Image as ImageIcon,
  List,
  ListChecks,
  LoaderCircle,
  Maximize2,
  Music2,
  Package,
  Pause,
  Play,
  Plus,
  RotateCcw,
  Search,
  Sparkles,
  Tags,
  Trash2,
  Upload,
  Volume2,
  VolumeX,
  X,
} from 'lucide-react';
import {
  libraryApi,
  type AssetGroupSnapshot,
  type AssetScope,
  type AssetType,
  type ImportEntry,
  type ImportMode,
  type ImportScanResult,
  type LibraryAsset,
  type LibraryAssetDetail,
  type LibraryMedia,
  type LibraryTag,
  type MetadataState,
  type TagMode,
} from '../api/library';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/ui/select';
import { useProjectStore } from '../stores/projectStore';
import { useLibraryTaskStore } from '../stores/libraryTaskStore';
import { LibraryTaskQueue } from '../components/library/LibraryTaskQueue';
import {
  buildLibraryQuery,
  formatBytes,
  libraryAssetScope,
  pageCount,
  scopeFromQuery,
} from '../lib/libraryFilters';
import './LibraryPage.css';

type ScopeView = 'PROJECT' | 'ALL' | 'GLOBAL' | 'TRASH';
const LIBRARY_PAGE_SIZE = 40;

const NAV_ITEMS: Array<{ key: ScopeView; label: string; icon: React.ReactNode }> = [
  { key: 'PROJECT', label: '项目库', icon: <Package size={16} /> },
  { key: 'TRASH', label: '回收站', icon: <Trash2 size={16} /> },
];

const TYPE_LABEL: Record<AssetType, string> = {
  IMAGE: '图片',
  VIDEO: '视频',
  AUDIO: '音频',
  AI_ASSET: 'AI资产',
  GROUP: '资产组',
};

const METADATA_STATE_LABEL: Record<MetadataState, string> = {
  NONE: '',
  AI_GENERATING: 'AI 自动生成中',
  AI_GENERATED: 'AI 生成',
  AI_FAILED: 'AI 失败',
  EDITED: '已编辑',
};

type DialogState =
  | { kind: 'tag' }
  | { kind: 'group'; defaultProjectId?: string }
  | { kind: 'edit'; asset: LibraryAsset }
  | { kind: 'trash'; asset: LibraryAsset }
  | { kind: 'purge'; assetIds: string[]; count: number; all: boolean }
  | { kind: 'add-to-group'; media: LibraryMedia[]; groups: LibraryAsset[] }
  | { kind: 'manage-tags'; tags: LibraryTag[] }
  | {
    kind: 'folder-import';
    files: File[];
    entries: ImportEntry[];
    folderName: string;
    defaultMode: ImportMode;
  };

type DialogResult = {
  name?: string;
  description?: string;
  groupId?: string;
  mode?: ImportMode;
  targetProjectId?: string;
  groupName?: string;
};

export function LibraryPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const currentProject = useProjectStore((state) => state.currentProject);
  const projects = useProjectStore((state) => state.projects);
  const loadProjects = useProjectStore((state) => state.loadProjects);
  const initialScope = scopeFromQuery(searchParams.get('scope'));
  const [scopeView, setScopeView] = useState<ScopeView>(initialScope);
  const [selectedProjectId, setSelectedProjectId] = useState(searchParams.get('projectId') || '');
  const [type, setType] = useState<AssetType | ''>((searchParams.get('type') as AssetType) || '');
  const [query, setQuery] = useState(searchParams.get('q') || '');
  const [view, setView] = useState<'cards' | 'table'>(searchParams.get('view') === 'table' ? 'table' : 'cards');
  const [tagMode, setTagMode] = useState<TagMode>(searchParams.get('tagMode') === 'ANY' ? 'ANY' : 'ALL');
  const [selectedTagIds, setSelectedTagIds] = useState<string[]>(
    (searchParams.get('tags') || '').split(',').filter(Boolean),
  );
  const [assets, setAssets] = useState<LibraryAsset[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [tags, setTags] = useState<LibraryTag[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedTrashIds, setSelectedTrashIds] = useState<string[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<LibraryAssetDetail | null>(null);
  const [dialog, setDialog] = useState<DialogState | null>(null);
  const [dialogBusy, setDialogBusy] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const folderInput = useRef<HTMLInputElement>(null);
  const taskPanelOpen = useLibraryTaskStore((state) => state.panelOpen);
  const setTaskPanelOpen = useLibraryTaskStore((state) => state.setPanelOpen);
  const upsertTask = useLibraryTaskStore((state) => state.upsertTask);
  const updateTask = useLibraryTaskStore((state) => state.updateTask);
  const addAnalysisTask = useLibraryTaskStore((state) => state.addAnalysisTask);
  const tasks = useLibraryTaskStore((state) => state.tasks);

  const selectedProject = projects.find((project) => project.id === selectedProjectId)
    || (currentProject?.id === selectedProjectId ? currentProject : undefined);
  const filteringByProject = scopeView === 'ALL' && Boolean(selectedProjectId);
  const scopedProjectId = scopeView === 'PROJECT'
    ? currentProject?.id
    : filteringByProject
      ? selectedProjectId
      : undefined;
  const deleted = scopeView === 'TRASH';
  const effectiveScope: AssetScope | undefined = libraryAssetScope(scopeView, filteringByProject);
  const effectiveType: AssetType | undefined = type || undefined;

  const reload = useCallback(async () => {
    if (scopeView === 'PROJECT' && !currentProject?.id) {
      setAssets([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [assetResponse, tagResponse] = await Promise.all([
        libraryApi.listAssets({
          scope: effectiveScope,
          projectId: scopedProjectId,
          q: query.trim() || undefined,
          type: effectiveType,
          tagIds: selectedTagIds.length ? selectedTagIds : undefined,
          tagMode,
          deleted,
          page,
          size: LIBRARY_PAGE_SIZE,
        }),
        libraryApi.listTags(),
      ]);
      setAssets(assetResponse.data.data.records || []);
      setTotal(assetResponse.data.data.total || 0);
      setTags(tagResponse.data.data || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载项目库失败');
    } finally {
      setLoading(false);
    }
  }, [
    currentProject?.id,
    deleted,
    effectiveScope,
    effectiveType,
    page,
    query,
    scopeView,
    selectedTagIds,
    tagMode,
    scopedProjectId,
  ]);

  useEffect(() => {
    void reload();
  }, [reload]);

  useEffect(() => {
    if (projects.length === 0) {
      void loadProjects().catch(() => undefined);
    }
  }, [loadProjects, projects.length]);

  useEffect(() => {
    if (scopeView !== 'ALL' || !selectedProjectId || projects.length === 0) return;
    if (!projects.some((project) => project.id === selectedProjectId)) {
      setSelectedProjectId('');
    }
  }, [projects, scopeView, selectedProjectId]);

  useEffect(() => {
    const next = buildLibraryQuery({
      scopeView,
      projectId: scopeView === 'ALL' ? selectedProjectId : undefined,
      type,
      query,
      view,
      tagIds: selectedTagIds,
      tagMode,
    });
    setSearchParams(next, { replace: true });
  }, [query, scopeView, selectedProjectId, selectedTagIds, setSearchParams, tagMode, type, view]);

  useEffect(() => {
    setPage(1);
  }, [currentProject?.id, query, scopeView, selectedProjectId, selectedTagIds, tagMode, type]);

  useEffect(() => {
    setSelectedTrashIds([]);
  }, [page, query, scopeView, selectedTagIds, type]);

  useEffect(() => {
    if (scopeView !== 'TRASH') return;
    const visibleIds = new Set(assets.map((asset) => asset.id));
    setSelectedTrashIds((current) => {
      const next = current.filter((id) => visibleIds.has(id));
      return next.length === current.length ? current : next;
    });
  }, [assets, scopeView]);

  const pages = pageCount(total, LIBRARY_PAGE_SIZE);

  useEffect(() => {
    if (page > pages) setPage(pages);
  }, [page, pages]);

  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return;
    }
    void libraryApi.getAsset(selectedId).then((response) => setDetail(response.data.data));
  }, [selectedId, assets]);

  const selectedTagSet = useMemo(() => new Set(selectedTagIds), [selectedTagIds]);
  const selectedTrashSet = useMemo(() => new Set(selectedTrashIds), [selectedTrashIds]);
  const allPageTrashSelected = scopeView === 'TRASH'
    && assets.length > 0
    && assets.every((asset) => selectedTrashSet.has(asset.id));
  const activeTaskCount = tasks.filter((task) => task.status !== 'completed' && task.status !== 'failed').length;
  const activeAnalysisIds = useMemo(() => Array.from(new Set(
    [
      ...tasks
        .filter((task) => task.kind === 'analysis' && task.status === 'processing' && task.assetId)
        .map((task) => task.assetId as string),
      ...assets
        .filter((asset) => asset.metadataState === 'AI_GENERATING')
        .map((asset) => asset.id),
    ],
  )), [assets, tasks]);
  const activeAnalysisKey = activeAnalysisIds.join(',');

  useEffect(() => {
    const assetIds = activeAnalysisKey ? activeAnalysisKey.split(',').filter(Boolean) : [];
    if (!assetIds.length) return;
    let cancelled = false;
    const poll = async () => {
      const response = await libraryApi.getAnalysisTasks(assetIds);
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
        setAssets((current) => current.map((asset) => asset.id === task.assetId
          ? { ...asset, metadataState }
          : asset));
        setDetail((current) => current?.asset.id === task.assetId
          ? { ...current, asset: { ...current.asset, metadataState } }
          : current);
      });
      if (terminal) await reload();
    };
    void poll().catch(() => undefined);
    const timer = window.setInterval(() => void poll().catch(() => undefined), 4000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [activeAnalysisKey, reload, updateTask]);

  const assetMatchesView = useCallback((asset: LibraryAsset) => {
    if (scopeView === 'PROJECT') {
      if (!currentProject?.id) return false;
      if (asset.ownerProjectId !== currentProject.id
        && !asset.projects.some((project) => project.projectId === currentProject.id)) return false;
    } else if (scopeView === 'GLOBAL') {
      if (asset.scope !== 'GLOBAL') return false;
    } else if (scopeView === 'ALL' && selectedProjectId) {
      if (asset.ownerProjectId !== selectedProjectId
        && !asset.projects.some((project) => project.projectId === selectedProjectId)) return false;
    }
    if (effectiveType && asset.type !== effectiveType) return false;
    if (selectedTagIds.length > 0) {
      const assetTagIds = new Set(asset.tags.map((tag) => tag.id));
      const matched = tagMode === 'ALL'
        ? selectedTagIds.every((id) => assetTagIds.has(id))
        : selectedTagIds.some((id) => assetTagIds.has(id));
      if (!matched) return false;
    }
    return true;
  }, [
    currentProject?.id,
    effectiveType,
    scopeView,
    selectedProjectId,
    selectedTagIds,
    tagMode,
  ]);

  const upsertAsset = useCallback((asset: LibraryAsset) => {
    if (!assetMatchesView(asset)) return;
    setAssets((current) => {
      const exists = current.some((item) => item.id === asset.id);
      if (!exists) setTotal((value) => value + 1);
      return [asset, ...current.filter((item) => item.id !== asset.id)];
    });
  }, [assetMatchesView]);

  const registerAnalysisTask = useCallback((asset: LibraryAsset) => {
    addAnalysisTask(
      asset.id,
      asset.name,
      asset.metadataState || (asset.aiDescription ? 'AI_GENERATED' : 'AI_GENERATING'),
    );
  }, [addAnalysisTask]);

  const runDirectUploads = async (list: File[]) => {
    let cursor = 0;
    const workers = Array.from({ length: Math.min(3, list.length) }, async () => {
      while (cursor < list.length) {
        const file = list[cursor];
        const taskId = `upload:${crypto.randomUUID()}`;
        cursor += 1;
        upsertTask({
          id: taskId,
          kind: 'upload',
          label: file.name,
          detail: '上传素材',
          status: 'queued',
          progress: 0,
        });
        try {
          const response = await libraryApi.uploadDirect(file, {
            scope: currentProject?.id ? 'PROJECT' : 'GLOBAL',
            projectId: currentProject?.id,
            name: file.name.replace(/\.[^.]+$/, ''),
          }, (progress) => updateTask(taskId, { status: 'uploading', progress }));
          const asset = response.data.data;
          upsertAsset(asset);
          registerAnalysisTask(asset);
          updateTask(taskId, { status: 'completed', progress: 100, assetId: asset.id });
        } catch (err) {
          updateTask(taskId, {
            status: 'failed',
            error: err instanceof Error ? err.message : '上传失败',
          });
        }
      }
    });
    await Promise.all(workers);
    await reload();
  };

  const handleFiles = (files: FileList | null) => {
    const list = Array.from(files || []);
    if (fileInput.current) fileInput.current.value = '';
    if (list.length) void runDirectUploads(list);
  };

  const handleFolder = (files: FileList | null) => {
    const list = Array.from(files || []);
    if (folderInput.current) folderInput.current.value = '';
    if (!list.length) return;
    const firstPath = (list[0] as File & { webkitRelativePath?: string }).webkitRelativePath || list[0].name;
    const folderName = firstPath.split('/')[0] || '文件夹资产组';
    const entries = list.map((file) => ({
      itemKey: `${(file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name}:${file.size}:${file.lastModified}`,
      relativePath: (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name,
      fileName: file.name,
      sizeBytes: file.size,
      mimeType: file.type || mimeFromName(file.name),
    }));
    setDialog({
      kind: 'folder-import',
      files: list,
      entries,
      folderName,
      defaultMode: effectiveType === 'GROUP' ? 'GROUP' : 'ASSETS',
    });
  };

  const importFolderFiles = async (
    folderDialog: Extract<DialogState, { kind: 'folder-import' }>,
    options: { mode: ImportMode; projectId?: string; groupName?: string },
  ) => {
    const { files, entries } = folderDialog;
    const imageFiles = files.filter((file) => (file.type || mimeFromName(file.name)).startsWith('image/'));
    if (!imageFiles.length) {
      setError('目录中没有可导入的图片文件');
      return;
    }
    const targetProjectId = options.mode === 'GROUP'
      ? options.projectId
      : options.projectId || undefined;
    const scanResponse = await libraryApi.scanImport({
      scope: targetProjectId ? 'PROJECT' : 'GLOBAL',
      projectId: targetProjectId,
      mode: options.mode,
      groupName: options.groupName,
      entries,
    });
    const scan: ImportScanResult = scanResponse.data.data;
    const jobTaskId = `import:${scan.jobId}`;
    upsertTask({
      id: jobTaskId,
      kind: 'import',
      label: options.mode === 'GROUP' ? `${options.groupName || folderDialog.folderName}（资产组）` : folderDialog.folderName,
      detail: `准备导入 ${imageFiles.length} 张图片`,
      status: 'queued',
      progress: 0,
      jobId: scan.jobId,
    });
    await reload();
    try {
      let processed = 0;
      let succeeded = 0;
      let failed = 0;
      const failures: string[] = [];
      const concurrency = 3;
      let cursor = 0;
      const workers = Array.from({ length: Math.min(concurrency, imageFiles.length) }, async () => {
        while (cursor < imageFiles.length) {
          const index = cursor++;
          const file = imageFiles[index];
          const relativePath = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name;
          const entry = entries.find((item) => item.relativePath === relativePath);
          if (!entry) continue;
          const itemTaskId = `import-item:${scan.jobId}:${entry.itemKey}`;
          upsertTask({
            id: itemTaskId,
            kind: 'import',
            label: file.name,
            detail: options.mode === 'GROUP' ? '导入并加入资产组' : '导入素材',
            status: 'queued',
            progress: 0,
            jobId: scan.jobId,
          });
          try {
            const response = await libraryApi.uploadImportItem(
              scan.jobId,
              entry.itemKey,
              scan.confirmationToken,
              file,
              (progress) => updateTask(itemTaskId, { status: 'uploading', progress }),
            );
            if (response.data.data.asset) {
              upsertAsset(response.data.data.asset);
              registerAnalysisTask(response.data.data.asset);
            }
            succeeded += 1;
            updateTask(itemTaskId, {
              status: 'completed',
              progress: 100,
              assetId: response.data.data.asset?.id,
            });
          } catch (err) {
            failed += 1;
            failures.push(`${file.name}: ${err instanceof Error ? err.message : '导入失败'}`);
            updateTask(itemTaskId, {
              status: 'failed',
              error: err instanceof Error ? err.message : '导入失败',
            });
          } finally {
            processed += 1;
            updateTask(jobTaskId, {
              status: processed >= imageFiles.length ? (failed > 0 ? 'failed' : 'completed') : 'processing',
              progress: Math.round(processed / imageFiles.length * 100),
              detail: `已处理 ${processed}/${imageFiles.length}，成功 ${succeeded}，失败 ${failed}`,
              error: processed >= imageFiles.length && failed > 0 ? `${failed} 张导入失败` : undefined,
            });
          }
        }
      });
      await Promise.all(workers);
      await reload();
      if (failed > 0) {
        const detail = failures.slice(0, 3).join('；');
        setError(`已导入 ${succeeded} 张，${failed} 张失败${detail ? `：${detail}` : ''}`);
      }
    } catch (err) {
      updateTask(jobTaskId, {
        status: 'failed',
        error: err instanceof Error ? err.message : '目录导入失败',
      });
      setError(err instanceof Error ? err.message : '目录导入失败');
    }
  };

  const handleDialogConfirm = async (result: DialogResult) => {
    if (!dialog) return;
    setDialogBusy(true);
    try {
      if (dialog.kind === 'tag') {
        const name = result.name?.trim();
        if (!name) return;
        await libraryApi.createTag({ name });
        await reload();
      }

      if (dialog.kind === 'group') {
        const name = result.name?.trim();
        const projectId = result.targetProjectId;
        if (!name || !projectId) return;
        await libraryApi.createGroup({
          scope: 'PROJECT',
          ownerProjectId: projectId,
          name,
        });
        if (projectId === currentProject?.id) {
          setScopeView('PROJECT');
        } else {
          setScopeView('ALL');
          setSelectedProjectId(projectId);
        }
        setType('GROUP');
        await reload();
      }

      if (dialog.kind === 'edit') {
        const name = result.name?.trim();
        if (!name) return;
        await libraryApi.updateAsset(dialog.asset.id, {
          name,
          description: result.description?.trim() || '',
          expectedVersion: dialog.asset.version,
          manualOverride: true,
        });
        await reload();
      }

      if (dialog.kind === 'trash') {
        await libraryApi.trash(dialog.asset.id, dialog.asset.version);
        setSelectedId(null);
        await reload();
      }

      if (dialog.kind === 'purge') {
        const assetIds = dialog.all ? await collectAllTrashAssetIds() : dialog.assetIds;
        await purgeAssets(assetIds);
      }

      if (dialog.kind === 'add-to-group') {
        const groupId = result.groupId;
        if (!groupId) return;
        const detailResponse = await libraryApi.getGroup(groupId);
        const existing = detailResponse.data.data.media.map((item) => item.media);
        const merged = [
          ...existing.map((item, index) => ({ mediaId: item.id, sortOrder: index, role: item.role })),
          ...dialog.media.map((item, index) => ({
            mediaId: item.id,
            sortOrder: existing.length + index,
            role: item.role,
          })),
        ].filter((item, index, all) => all.findIndex((candidate) => candidate.mediaId === item.mediaId) === index)
          .map((item, index) => ({ ...item, sortOrder: index }));
        await libraryApi.replaceGroupItems(groupId, detailResponse.data.data.version, merged);
        await reload();
      }

      if (dialog.kind === 'folder-import') {
        const folderDialog = dialog;
        setDialog(null);
        void importFolderFiles(folderDialog, {
          mode: result.mode || folderDialog.defaultMode,
          projectId: result.targetProjectId,
          groupName: result.groupName?.trim() || folderDialog.folderName,
        }).catch((err) => {
          setError(err instanceof Error ? err.message : '目录导入失败');
        });
        return;
      }

      setDialog(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败，请稍后重试');
    } finally {
      setDialogBusy(false);
    }
  };

  const restoreAsset = async (asset: LibraryAsset) => {
    await libraryApi.restore(asset.id);
    await reload();
  };

  const toggleTrashSelection = (assetId: string) => {
    setSelectedTrashIds((current) =>
      current.includes(assetId)
        ? current.filter((id) => id !== assetId)
        : [...current, assetId]);
  };

  const toggleCurrentPageTrashSelection = () => {
    if (allPageTrashSelected) {
      const visibleIds = new Set(assets.map((asset) => asset.id));
      setSelectedTrashIds((current) => current.filter((id) => !visibleIds.has(id)));
      return;
    }
    setSelectedTrashIds((current) => Array.from(new Set([
      ...current,
      ...assets.map((asset) => asset.id),
    ])));
  };

  const collectAllTrashAssetIds = async () => {
    const first = await libraryApi.listAssets({ deleted: true, page: 1, size: 100 });
    const ids = first.data.data.records.map((asset) => asset.id);
    const pages = pageCount(first.data.data.total, 100);
    for (let currentPage = 2; currentPage <= pages; currentPage += 1) {
      const response = await libraryApi.listAssets({ deleted: true, page: currentPage, size: 100 });
      ids.push(...response.data.data.records.map((asset) => asset.id));
    }
    return Array.from(new Set(ids));
  };

  const purgeAssets = async (assetIds: string[]) => {
    const uniqueIds = Array.from(new Set(assetIds));
    if (!uniqueIds.length) return;

    let cursor = 0;
    let succeeded = 0;
    let failed = 0;
    const workers = Array.from(
      { length: Math.min(4, uniqueIds.length) },
      async () => {
        while (cursor < uniqueIds.length) {
          const assetId = uniqueIds[cursor];
          cursor += 1;
          try {
            await libraryApi.purge(assetId);
            succeeded += 1;
          } catch {
            failed += 1;
          }
        }
      },
    );
    await Promise.all(workers);

    setSelectedTrashIds([]);
    setSelectedId(null);
    await reload();
    if (failed > 0) {
      setError(`已永久删除 ${succeeded} 项，${failed} 项仍被资产组或生成结果引用。`);
    }
  };

  const createGroupSnapshotForVideo = async (asset: LibraryAsset) => {
    try {
      const response = await libraryApi.createSnapshot(asset.id);
      const snapshot: AssetGroupSnapshot = response.data.data;
      sessionStorage.setItem('moon.pendingGroupSnapshot', JSON.stringify(snapshot));
      navigate('/chat');
    } catch (err) {
      setError(err instanceof Error ? err.message : '创建资产组快照失败');
    }
  };

  const toggleTag = async (asset: LibraryAsset, tag: LibraryTag) => {
    const next = asset.tags.some((item) => item.id === tag.id)
      ? asset.tags.filter((item) => item.id !== tag.id).map((item) => item.id)
      : [...asset.tags.map((item) => item.id), tag.id];
    await libraryApi.replaceTags(asset.id, next);
    await reload();
  };

  const deleteTag = async (tag: LibraryTag) => {
    await libraryApi.deleteTag(tag.id);
    setSelectedTagIds((current) => current.filter((id) => id !== tag.id));
    setTags((current) => current.filter((item) => item.id !== tag.id));
    setDialog((current) => current?.kind === 'manage-tags'
      ? { ...current, tags: current.tags.filter((item) => item.id !== tag.id) }
      : current);
    await reload();
  };

  const addAssetToGroup = async (media: LibraryMedia[]) => {
    try {
      const projectId = scopeView === 'PROJECT'
        ? currentProject?.id
        : selectedProjectId || currentProject?.id;
      const groupResponse = await libraryApi.listAssets({
        scope: projectId ? 'PROJECT' : undefined,
        projectId,
        type: 'GROUP',
        page: 1,
        size: 100,
      });
      const groups = groupResponse.data.data.records || [];
      if (groups.length === 0) {
        setError(projectId ? '当前项目没有资产组，请先新建资产组' : '请先选择项目再添加资产组');
        return;
      }
      setDialog({ kind: 'add-to-group', media, groups });
    } catch (err) {
      setError(err instanceof Error ? err.message : '加入资产组失败');
    }
  };

  return (
    <div className="library-page">
      <aside className="library-nav">
        <button className="library-back" onClick={() => navigate('/chat')}><ChevronLeft size={16} /> 返回 Moon</button>
        <div className="library-brand">
          <span><Grid2X2 size={18} /></span>
          <div><strong>项目库</strong><small>统一媒体资产目录</small></div>
        </div>
        <nav>
          {NAV_ITEMS.map((item) => (
            <button
              key={item.key}
              className={scopeView === item.key ? 'is-active' : ''}
              onClick={() => {
                setScopeView(item.key);
                if (item.key === 'TRASH') setType('');
              }}
            >
              {item.icon}<span>{item.label}</span>
            </button>
          ))}
        </nav>
        <div className="library-nav__project">
          <small>当前项目</small>
          <strong>{currentProject?.name || '未选择项目'}</strong>
        </div>
      </aside>

      <main className="library-main">
        <header className="library-header">
          <div>
            <span className="library-header__eyebrow">MOON ASSET LIBRARY</span>
            <h1>{NAV_ITEMS.find((item) => item.key === scopeView)?.label}</h1>
            <p>{scopeDescription(scopeView, currentProject?.name, selectedProject?.name)}</p>
          </div>
          <div className="library-header__actions">
            <button
              className="is-secondary"
              onClick={() => setTaskPanelOpen(!taskPanelOpen)}
            >
              <ListChecks size={15} /> 任务{activeTaskCount > 0 ? ` ${activeTaskCount}` : ''}
            </button>
            <button
              className="is-secondary"
              onClick={() => folderInput.current?.click()}
            >
              <FolderPlus size={15} /> 上传文件夹
            </button>
            <button
              className="is-ghost"
              onClick={() => setDialog({
                kind: 'group',
                defaultProjectId: selectedProjectId || currentProject?.id || '',
              })}
            >
              <Plus size={15} /> 新建资产组
            </button>
            <button className="is-primary" onClick={() => fileInput.current?.click()}>
              <Upload size={15} /> 上传素材
            </button>
          </div>
        </header>

        <section className="library-toolbar">
          <label className="library-search">
            <Search size={16} />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索名称、标签、描述"
              aria-label="搜索资产"
            />
            {query && <button onClick={() => setQuery('')} aria-label="清除搜索" title="清除搜索"><X size={14} /></button>}
          </label>
          {scopeView === 'ALL' && (
            <Select
              value={selectedProjectId || 'ALL'}
              onValueChange={(value) => setSelectedProjectId(!value || value === 'ALL' ? '' : value)}
            >
              <SelectTrigger className="library-select-trigger" aria-label="按项目筛选">
                <SelectValue>
                  {(value) => value === 'ALL'
                    ? '全部项目'
                    : projects.find((project) => project.id === value)?.name || '全部项目'}
                </SelectValue>
              </SelectTrigger>
              <SelectContent className="library-select-content" align="start">
                <SelectItem value="ALL">
                  <span className="library-select-option"><Boxes size={15} /> 全部项目</span>
                </SelectItem>
                {projects.map((project) => (
                  <SelectItem key={project.id} value={project.id}>
                    <span className="library-select-option"><Package size={15} /> {project.name}</span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
          <Select
            value={type || 'ALL'}
            onValueChange={(value) => setType(value === 'ALL' ? '' : value as AssetType)}
            disabled={scopeView === 'TRASH'}
          >
            <SelectTrigger className="library-select-trigger" aria-label="按资产类型筛选">
              <SelectValue>
                {(value) => value === 'ALL' ? '全部类型' : TYPE_LABEL[value as AssetType]}
              </SelectValue>
            </SelectTrigger>
            <SelectContent className="library-select-content" align="start">
              <SelectItem value="ALL"><span className="library-select-option"><Package size={15} /> 全部类型</span></SelectItem>
              {(['IMAGE', 'VIDEO', 'AUDIO', 'AI_ASSET', 'GROUP'] as AssetType[]).map((item) => (
                <SelectItem key={item} value={item}>
                  <span className="library-select-option"><TypeIcon type={item} size={15} /> {TYPE_LABEL[item]}</span>
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <span className="library-result-count">
            <strong>{total}</strong> 项
          </span>
          <div className="library-view-switch">
            <button
              className={view === 'cards' ? 'is-active' : ''}
              onClick={() => setView('cards')}
              aria-label="卡片视图"
              title="卡片视图"
            >
              <Grid2X2 size={15} />
            </button>
            <button
              className={view === 'table' ? 'is-active' : ''}
              onClick={() => setView('table')}
              aria-label="列表视图"
              title="列表视图"
            >
              <List size={15} />
            </button>
          </div>
        </section>

        {scopeView !== 'TRASH' && (
          <section className="library-tags">
            <button
              className={selectedTagIds.length === 0 ? 'is-active' : ''}
              onClick={() => setSelectedTagIds([])}
            >全部标签</button>
            {tags.map((tag) => (
              <button
                key={tag.id}
                className={selectedTagSet.has(tag.id) ? 'is-active' : ''}
                onClick={() => setSelectedTagIds((current) =>
                  current.includes(tag.id) ? current.filter((id) => id !== tag.id) : [...current, tag.id])}
              >{tag.name}</button>
            ))}
            {selectedTagIds.length > 1 && (
              <button onClick={() => setTagMode((mode) => mode === 'ALL' ? 'ANY' : 'ALL')}>
                {tagMode === 'ALL' ? '全部命中' : '任一命中'}
              </button>
            )}
            <button className="library-tags__add" onClick={() => setDialog({ kind: 'tag' })}><Plus size={12} /> 新标签</button>
            <button className="library-tags__manage" onClick={() => setDialog({ kind: 'manage-tags', tags })}>
              <Tags size={12} /> 管理标签
            </button>
          </section>
        )}

        {scopeView === 'TRASH' && total > 0 && (
          <section className="library-trash-actions" aria-label="回收站批量操作">
            <div className="library-trash-actions__summary">
              <button
                type="button"
                onClick={toggleCurrentPageTrashSelection}
                disabled={assets.length === 0}
              >
                <Check size={14} />
                {allPageTrashSelected ? '取消本页' : '选择本页'}
              </button>
              <span><strong>{selectedTrashIds.length}</strong> 项已选</span>
            </div>
            <div className="library-trash-actions__buttons">
              <button
                type="button"
                className="is-danger"
                disabled={selectedTrashIds.length === 0}
                onClick={() => setDialog({
                  kind: 'purge',
                  assetIds: selectedTrashIds,
                  count: selectedTrashIds.length,
                  all: false,
                })}
              >
                <Trash2 size={14} /> 永久删除选中
              </button>
              <button
                type="button"
                className="is-danger-outline"
                onClick={() => setDialog({
                  kind: 'purge',
                  assetIds: [],
                  count: total,
                  all: true,
                })}
              >
                <Trash2 size={14} /> 清空回收站
              </button>
            </div>
          </section>
        )}

        {error && (
          <div className="library-error" role="alert">
            <CircleAlert size={16} />
            <span>{error}</span>
            <button onClick={() => setError('')} aria-label="关闭错误提示"><X size={14} /></button>
          </div>
        )}

        {loading ? (
          <div className="library-loading-grid" aria-label="正在加载资产">
            {Array.from({ length: 8 }, (_, index) => (
              <div className="library-card-skeleton" key={index}>
                <i />
                <span />
                <span />
              </div>
            ))}
          </div>
        ) : assets.length === 0 ? (
          <div className="library-empty">
            <span className="library-empty__icon"><Package size={26} /></span>
            <strong>{scopeView === 'TRASH' ? '回收站是空的' : '还没有匹配的资产'}</strong>
            <span>{scopeView === 'TRASH' ? '移入回收站的资产会在这里保留 7 天。' : '上传文件或调整搜索条件后，素材会出现在这里。'}</span>
            {scopeView !== 'TRASH' && (query || type || selectedTagIds.length > 0 || filteringByProject) && (
              <button
                className="library-empty__action"
                onClick={() => {
                  setQuery('');
                  setType('');
                  setSelectedTagIds([]);
                  setSelectedProjectId('');
                }}
              >
                清除筛选
              </button>
            )}
          </div>
        ) : view === 'cards' ? (
          <div className="library-grid">
            {assets.map((asset) => (
              <AssetCard
                key={asset.id}
                asset={asset}
                onOpen={() => setSelectedId(asset.id)}
                onRestore={scopeView === 'TRASH' ? () => void restoreAsset(asset) : undefined}
                selectable={scopeView === 'TRASH'}
                selected={selectedTrashSet.has(asset.id)}
                onToggleSelect={() => toggleTrashSelection(asset.id)}
                projectName={projectNameForAsset(asset, projects, currentProject)}
              />
            ))}
          </div>
        ) : (
          <AssetTable
            assets={assets}
            onOpen={setSelectedId}
            selectable={scopeView === 'TRASH'}
            selectedIds={selectedTrashSet}
            onToggleSelect={toggleTrashSelection}
          />
        )}

        {!loading && assets.length > 0 && (
          <nav className="library-pagination" aria-label="资产分页">
            <button
              onClick={() => setPage((current) => Math.max(1, current - 1))}
              disabled={page <= 1}
              aria-label="上一页"
              title="上一页"
            >
              <ChevronLeft size={15} />
            </button>
            <span>第 <strong>{page}</strong> / {pages} 页</span>
            <button
              onClick={() => setPage((current) => Math.min(pages, current + 1))}
              disabled={page >= pages}
              aria-label="下一页"
              title="下一页"
            >
              <ChevronRight size={15} />
            </button>
          </nav>
        )}
      </main>

      <input
        ref={fileInput}
        type="file"
        accept="image/*,video/*,audio/*"
        multiple
        hidden
        onChange={(event) => void handleFiles(event.target.files)}
      />
      <input
        ref={folderInput}
        type="file"
        multiple
        hidden
        {...({ webkitdirectory: '', directory: '' } as Record<string, string>)}
        onChange={(event) => void handleFolder(event.target.files)}
      />

      <LibraryTaskQueue />

      {detail && (
        <AssetDetail
          detail={detail}
          tags={tags}
          onClose={() => setSelectedId(null)}
          onEdit={() => setDialog({ kind: 'edit', asset: detail.asset })}
          onTrash={() => setDialog({ kind: 'trash', asset: detail.asset })}
          onPromote={() => void libraryApi.promote(detail.asset.id, detail.asset.version).then(reload)}
          onToggleTag={(tag) => void toggleTag(detail.asset, tag)}
          onUseForVideo={() => void createGroupSnapshotForVideo(detail.asset)}
          onAddToGroup={() => void addAssetToGroup(detail.media)}
          projectName={projectNameForAsset(detail.asset, projects, currentProject)}
        />
      )}

      {dialog?.kind === 'manage-tags' ? (
        <TagManagerDialog
          tags={dialog.tags}
          busy={dialogBusy}
          onDelete={(tag) => void deleteTag(tag).catch((err) => {
            setError(err instanceof Error ? err.message : '标签删除失败');
          })}
          onClose={() => setDialog(null)}
        />
      ) : dialog ? (
        <LibraryActionDialog
          key={dialogKey(dialog)}
          dialog={dialog}
          busy={dialogBusy}
          projects={projects}
          currentProjectId={scopeView === 'ALL'
            ? selectedProjectId
            : scopeView === 'PROJECT' ? currentProject?.id : ''}
          lockFolderProject={scopeView === 'PROJECT' && Boolean(currentProject?.id)}
          onClose={() => setDialog(null)}
          onConfirm={(result) => void handleDialogConfirm(result)}
        />
      ) : null}
    </div>
  );
}

function AssetCard({
  asset,
  onOpen,
  onRestore,
  selectable,
  selected,
  onToggleSelect,
  projectName,
}: {
  asset: LibraryAsset;
  onOpen: () => void;
  onRestore?: () => void;
  selectable: boolean;
  selected: boolean;
  onToggleSelect: () => void;
  projectName?: string;
}) {
  return (
    <article className={`library-card ${selectable ? 'is-selectable' : ''} ${selected ? 'is-selected' : ''}`}>
      {selectable && (
        <label className="library-card__select" onClick={(event) => event.stopPropagation()}>
          <input
            type="checkbox"
            checked={selected}
            onChange={onToggleSelect}
            aria-label={`选择资产 ${asset.name}`}
          />
        </label>
      )}
      <button className="library-card__open" onClick={onOpen} aria-label={`查看资产 ${asset.name}`}>
        <div className="library-card__cover">
          {asset.type === 'VIDEO' ? <VideoPreview url={asset.coverUrl} /> :
            asset.type === 'AUDIO' ? <div className="library-card__audio"><Music2 size={24} /></div> :
              asset.coverUrl ? <img src={asset.coverUrl} alt="" loading="lazy" /> :
                <div className="library-card__placeholder"><TypeIcon type={asset.type} /></div>}
          <span>{TYPE_LABEL[asset.type]}</span>
          {asset.scope === 'GLOBAL' && <em>全局</em>}
        </div>
        <div className="library-card__body">
          <strong>{asset.name}</strong>
          {asset.type === 'GROUP' && (
            <span className="library-card__project">{projectName ? `${projectName} · 资产组` : '项目资产组'}</span>
          )}
          <div className="library-card__description">
            <p>{asset.description || asset.aiDescription || '暂无描述'}</p>
            {asset.metadataState !== 'NONE' && (
              <span className={`library-metadata is-${asset.metadataState.toLowerCase()}`}>
                {METADATA_STATE_LABEL[asset.metadataState]}
              </span>
            )}
          </div>
          <div className="library-card__tags">
            {asset.tags.slice(0, 4).map((tag) => <span key={tag.id}>{tag.name}</span>)}
          </div>
        </div>
      </button>
      {onRestore && (
        <button className="library-card__restore" onClick={onRestore}>
          <RotateCcw size={13} /> 恢复资产
        </button>
      )}
    </article>
  );
}

function AssetTable({
  assets,
  onOpen,
  selectable,
  selectedIds,
  onToggleSelect,
}: {
  assets: LibraryAsset[];
  onOpen: (id: string) => void;
  selectable: boolean;
  selectedIds: Set<string>;
  onToggleSelect: (id: string) => void;
}) {
  return (
    <div className="library-table-wrap">
      <table className="library-table">
        <thead>
          <tr>
            {selectable && <th className="library-table__select-cell" aria-label="选择" />}
            <th>名称</th>
            <th>类型</th>
            <th>作用域</th>
            <th>标签</th>
            <th>来源</th>
            <th>更新时间</th>
          </tr>
        </thead>
        <tbody>
          {assets.map((asset) => (
            <tr
              key={asset.id}
              className={selectedIds.has(asset.id) ? 'is-selected' : ''}
              onClick={() => onOpen(asset.id)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  onOpen(asset.id);
                }
              }}
              tabIndex={0}
            >
              {selectable && (
                <td className="library-table__select-cell">
                  <input
                    type="checkbox"
                    checked={selectedIds.has(asset.id)}
                    onClick={(event) => event.stopPropagation()}
                    onKeyDown={(event) => event.stopPropagation()}
                    onChange={() => onToggleSelect(asset.id)}
                    aria-label={`选择资产 ${asset.name}`}
                  />
                </td>
              )}
              <td><div className="library-table__name"><TypeIcon type={asset.type} /><span>{asset.name}</span></div></td>
              <td>{TYPE_LABEL[asset.type]}</td>
              <td>项目库</td>
              <td>{asset.tags.map((tag) => tag.name).join('、') || '暂无标签'}</td>
              <td>{asset.sourceType || '手动上传'}</td>
              <td>{new Date(asset.updatedAt).toLocaleString('zh-CN', { hour12: false })}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function AssetDetail({
  detail,
  tags,
  onClose,
  onEdit,
  onTrash,
  onPromote,
  onToggleTag,
  onUseForVideo,
  onAddToGroup,
  projectName,
}: {
  detail: LibraryAssetDetail;
  tags: LibraryTag[];
  onClose: () => void;
  onEdit: () => void;
  onTrash: () => void;
  onPromote: () => void;
  onToggleTag: (tag: LibraryTag) => void;
  onUseForVideo: () => void;
  onAddToGroup: () => void;
  projectName?: string;
}) {
  const { asset, media } = detail;
  const [tagEditing, setTagEditing] = useState(false);

  useEffect(() => {
    setTagEditing(false);
  }, [asset.id]);

  return (
    <div className="library-detail-overlay" onClick={onClose}>
      <aside className="library-detail" onClick={(event) => event.stopPropagation()}>
        <header>
          <div><span>{TYPE_LABEL[asset.type]}</span><h2>{asset.name}</h2></div>
          <button onClick={onClose} aria-label="关闭资产详情" title="关闭"><X size={16} /></button>
        </header>
        <div className="library-detail__preview">
          {media[0] ? <MediaPreview media={media[0]} /> : <div className="library-detail__placeholder"><TypeIcon type={asset.type} /></div>}
        </div>
        <div className="library-detail__description">
          <p>{asset.description || asset.aiDescription || '暂无描述'}</p>
          {asset.metadataState !== 'NONE' && (
            <span className={`library-metadata is-${asset.metadataState.toLowerCase()}`}>
              {METADATA_STATE_LABEL[asset.metadataState]}
            </span>
          )}
        </div>
        <div className="library-detail__facts">
          <span>
            作用域 <b>{projectName ? `${projectName} 项目库` : '项目库'}</b>
          </span>
          <span>版本 <b>v{asset.version}</b></span>
          <span>媒体 <b>{media.length}</b></span>
        </div>
        <section>
          <div className="library-detail__section-header">
            <h3>标签</h3>
            <label className="library-detail__tag-switch">
              <input
                type="checkbox"
                role="switch"
                checked={tagEditing}
                onChange={(event) => setTagEditing(event.target.checked)}
              />
              <span aria-hidden="true" />
              编辑标签
            </label>
          </div>
          <div className="library-detail__tags">
            {tags.map((tag) => {
              const active = asset.tags.some((item) => item.id === tag.id);
              return (
                <button
                  key={tag.id}
                  className={active ? 'is-active' : ''}
                  onClick={() => onToggleTag(tag)}
                  disabled={!tagEditing}
                  aria-disabled={!tagEditing}
                >
                  {tag.name}
                </button>
              );
            })}
            {tags.length === 0 && <span className="library-detail__tags-empty">暂无标签</span>}
          </div>
        </section>
        {asset.type === 'GROUP' && (
          <>
            <button className="library-detail__primary" onClick={onUseForVideo}><Film size={15} /> 用此组生视频</button>
            <div className="library-detail__group-media">
              {media.map((item) => (
                <span key={item.id}>{item.type === 'IMAGE' ? '图' : item.type === 'VIDEO' ? '视频' : '音频'}</span>
              ))}
            </div>
          </>
        )}
        <div className="library-detail__actions">
          <button onClick={onEdit}>编辑信息</button>
          {asset.type !== 'GROUP' && <button onClick={onAddToGroup}>加入资产组</button>}
          {asset.scope === 'PROJECT' && <button onClick={onPromote}>提升到全局</button>}
          {asset.status === 'ACTIVE' && <button className="is-danger" onClick={onTrash}><Trash2 size={14} /> 移入回收站</button>}
        </div>
      </aside>
    </div>
  );
}

function MediaPreview({ media }: { media: LibraryMedia }) {
  if (media.type === 'VIDEO' || media.type === 'AUDIO') return <LibraryMediaPlayer media={media} />;
  return <img src={media.contentUrl} alt={media.fileName || ''} />;
}

function LibraryMediaPlayer({ media }: { media: LibraryMedia }) {
  const mediaRef = useRef<HTMLVideoElement | HTMLAudioElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const [playing, setPlaying] = useState(false);
  const [muted, setMuted] = useState(false);
  const [duration, setDuration] = useState(0);
  const [currentTime, setCurrentTime] = useState(0);

  useEffect(() => {
    setPlaying(false);
    setMuted(false);
    setDuration(0);
    setCurrentTime(0);
  }, [media.id]);

  const togglePlayback = async () => {
    const element = mediaRef.current;
    if (!element) return;
    if (element.paused) {
      await element.play();
    } else {
      element.pause();
    }
  };

  const seek = (next: number) => {
    const element = mediaRef.current;
    setCurrentTime(next);
    if (element) element.currentTime = next;
  };

  const toggleMute = () => {
    const element = mediaRef.current;
    if (!element) return;
    element.muted = !element.muted;
    setMuted(element.muted);
  };

  const toggleFullscreen = () => {
    const stage = stageRef.current;
    if (!stage) return;
    if (document.fullscreenElement) {
      void document.exitFullscreen();
    } else {
      void stage.requestFullscreen();
    }
  };

  const mediaElement = media.type === 'VIDEO' ? (
    <video
      ref={mediaRef as React.RefObject<HTMLVideoElement>}
      src={media.contentUrl}
      playsInline
      preload="metadata"
      onClick={() => void togglePlayback()}
      onPlay={() => setPlaying(true)}
      onPause={() => setPlaying(false)}
      onEnded={() => setPlaying(false)}
      onLoadedMetadata={(event) => setDuration(Number.isFinite(event.currentTarget.duration) ? event.currentTarget.duration : 0)}
      onTimeUpdate={(event) => setCurrentTime(event.currentTarget.currentTime)}
    />
  ) : (
    <audio
      ref={mediaRef as React.RefObject<HTMLAudioElement>}
      src={media.contentUrl}
      preload="metadata"
      onPlay={() => setPlaying(true)}
      onPause={() => setPlaying(false)}
      onEnded={() => setPlaying(false)}
      onLoadedMetadata={(event) => setDuration(Number.isFinite(event.currentTarget.duration) ? event.currentTarget.duration : 0)}
      onTimeUpdate={(event) => setCurrentTime(event.currentTarget.currentTime)}
    />
  );

  return (
    <div
      ref={stageRef}
      className={`library-player library-player--${media.type.toLowerCase()}`}
    >
      {mediaElement}
      {media.type === 'AUDIO' && (
        <button className="library-player__audio-wrap" onClick={() => void togglePlayback()} aria-label={playing ? '暂停音频' : '播放音频'}>
          <span className="library-player__audio-art"><Music2 size={28} /></span>
          <strong>{media.fileName || '音频素材'}</strong>
          <small>{playing ? '正在播放' : '点击播放'}</small>
        </button>
      )}
      {media.type === 'VIDEO' && !playing && (
        <button className="library-player__center-play" onClick={() => void togglePlayback()} aria-label="播放视频">
          <Play size={21} fill="currentColor" />
        </button>
      )}
      <div className="library-player__controls">
        <button onClick={() => void togglePlayback()} aria-label={playing ? '暂停' : '播放'} title={playing ? '暂停' : '播放'}>
          {playing ? <Pause size={15} fill="currentColor" /> : <Play size={15} fill="currentColor" />}
        </button>
        <span className="library-player__time">{formatMediaTime(currentTime)}</span>
        <Slider.Root
          className="library-player__slider"
          value={currentTime}
          min={0}
          max={Math.max(duration, 1)}
          step={0.01}
          disabled={!duration}
          onValueChange={(value) => {
            const next = Array.isArray(value) ? value[0] : value;
            seek(Number(next));
          }}
        >
          <Slider.Control className="library-player__slider-control">
            <Slider.Track className="library-player__slider-track">
              <Slider.Indicator className="library-player__slider-indicator" />
              <Slider.Thumb className="library-player__slider-thumb" aria-label="播放进度" />
            </Slider.Track>
          </Slider.Control>
        </Slider.Root>
        <span className="library-player__time">{formatMediaTime(duration)}</span>
        <button onClick={toggleMute} aria-label={muted ? '取消静音' : '静音'} title={muted ? '取消静音' : '静音'}>
          {muted ? <VolumeX size={15} /> : <Volume2 size={15} />}
        </button>
        {media.type === 'VIDEO' && (
          <button onClick={toggleFullscreen} aria-label="全屏播放" title="全屏播放">
            <Maximize2 size={15} />
          </button>
        )}
      </div>
    </div>
  );
}

function VideoPreview({ url }: { url: string | null }) {
  if (!url) return <div className="library-card__placeholder"><Film size={24} /></div>;
  return <video src={url} muted preload="metadata" />;
}

function TypeIcon({ type, size = 20 }: { type: AssetType; size?: number }) {
  if (type === 'IMAGE') return <ImageIcon size={size} />;
  if (type === 'VIDEO') return <Film size={size} />;
  if (type === 'AUDIO') return <Music2 size={size} />;
  if (type === 'GROUP') return <Boxes size={size} />;
  return <Sparkles size={size} />;
}

function LibraryActionDialog({
  dialog,
  busy,
  projects,
  currentProjectId,
  lockFolderProject,
  onClose,
  onConfirm,
}: {
  dialog: DialogState;
  busy: boolean;
  projects: Array<{ id: string; name: string }>;
  currentProjectId?: string;
  lockFolderProject: boolean;
  onClose: () => void;
  onConfirm: (result: DialogResult) => void;
}) {
  const initialName = dialog.kind === 'edit' ? dialog.asset.name : '';
  const [name, setName] = useState(initialName);
  const [description, setDescription] = useState(
    dialog.kind === 'edit' ? dialog.asset.description || dialog.asset.aiDescription || '' : '',
  );
  const [groupId, setGroupId] = useState(
    dialog.kind === 'add-to-group' ? dialog.groups[0]?.id || '' : '',
  );
  const [importMode, setImportMode] = useState<ImportMode>(
    dialog.kind === 'folder-import' ? dialog.defaultMode : 'ASSETS',
  );
  const [targetProjectId, setTargetProjectId] = useState(
    dialog.kind === 'folder-import' || dialog.kind === 'group'
      ? (
        dialog.kind === 'group'
          ? dialog.defaultProjectId || ''
          : currentProjectId || ''
      )
      : '',
  );
  const [groupName, setGroupName] = useState(
    dialog.kind === 'folder-import' ? dialog.folderName : '',
  );

  const isDestructive = dialog.kind === 'trash' || dialog.kind === 'purge';
  const title = dialogTitle(dialog);
  const descriptionText = dialogDescription(dialog);
  const canSubmit = dialog.kind === 'tag'
    ? Boolean(name.trim())
    : dialog.kind === 'group'
      ? Boolean(name.trim() && targetProjectId)
    : dialog.kind === 'edit'
      ? Boolean(name.trim())
      : dialog.kind === 'add-to-group'
        ? Boolean(groupId)
        : dialog.kind === 'folder-import'
          ? Boolean(targetProjectId && (importMode !== 'GROUP' || groupName.trim()))
          : true;

  return (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open && !busy) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Backdrop className="library-dialog-backdrop" />
        <Dialog.Popup className="library-dialog" initialFocus>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              if (!canSubmit || busy) return;
              onConfirm({
                name,
                description,
                groupId,
                mode: dialog.kind === 'folder-import' ? importMode : undefined,
                targetProjectId: dialog.kind === 'folder-import' || dialog.kind === 'group'
                  ? targetProjectId
                  : undefined,
                groupName: dialog.kind === 'folder-import' ? groupName : undefined,
              });
            }}
          >
            <header className="library-dialog__header">
              <div>
                <Dialog.Title className="library-dialog__title">{title}</Dialog.Title>
                <Dialog.Description className="library-dialog__description">
                  {descriptionText}
                </Dialog.Description>
              </div>
              <Dialog.Close className="library-dialog__close" disabled={busy} aria-label="关闭对话框">
                <X size={16} />
              </Dialog.Close>
            </header>

            <div className="library-dialog__body">
              {(dialog.kind === 'tag' || dialog.kind === 'group' || dialog.kind === 'edit') && (
                <label className="library-field">
                  <span>{dialog.kind === 'tag' ? '标签名称' : dialog.kind === 'group' ? '资产组名称' : '资产名称'}</span>
                  <input
                    value={name}
                    onChange={(event) => setName(event.target.value)}
                    placeholder={dialog.kind === 'tag' ? '例如：角色设定' : dialog.kind === 'group' ? '例如：主角三视图' : '输入资产名称'}
                    autoFocus
                  />
                </label>
              )}

              {dialog.kind === 'group' && (
                <label className="library-field">
                  <span>所属项目</span>
                  <Select value={targetProjectId} onValueChange={(value) => value && setTargetProjectId(value)}>
                    <SelectTrigger className="library-dialog__select-trigger">
                      <SelectValue>
                        {(value) => projects.find((project) => project.id === value)?.name || '选择项目'}
                      </SelectValue>
                    </SelectTrigger>
                    <SelectContent
                      className="library-select-content"
                      positionerClassName="library-select-positioner"
                      align="start"
                    >
                      {projects.map((project) => (
                        <SelectItem key={project.id} value={project.id}>{project.name}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </label>
              )}

              {dialog.kind === 'edit' && (
                <label className="library-field">
                  <span>描述 / 文字约束</span>
                  <textarea
                    value={description}
                    onChange={(event) => setDescription(event.target.value)}
                    placeholder="补充资产说明、使用范围或生成约束"
                    rows={4}
                  />
                </label>
              )}

              {dialog.kind === 'add-to-group' && (
                <label className="library-field">
                  <span>目标资产组</span>
                  <Select value={groupId} onValueChange={(value) => value && setGroupId(value)}>
                    <SelectTrigger className="library-dialog__select-trigger">
                      <SelectValue>
                        {(value) => dialog.groups.find((group) => group.id === value)?.name || '选择资产组'}
                      </SelectValue>
                    </SelectTrigger>
                    <SelectContent
                      className="library-select-content"
                      positionerClassName="library-select-positioner"
                      align="start"
                    >
                      {dialog.groups.map((group) => (
                        <SelectItem key={group.id} value={group.id}>{group.name}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </label>
              )}

              {dialog.kind === 'trash' && (
                <div className="library-dialog__warning">
                  <span><Trash2 size={17} /></span>
                  <p>「{dialog.asset.name}」会进入回收站，保留 7 天内仍可恢复。</p>
                </div>
              )}

              {dialog.kind === 'purge' && (
                <div className="library-dialog__warning">
                  <span><Trash2 size={17} /></span>
                  <p>
                    {dialog.all
                      ? `即将永久删除回收站中的 ${dialog.count} 项资产，删除后无法恢复。`
                      : `即将永久删除选中的 ${dialog.count} 项资产，删除后无法恢复。`}
                  </p>
                </div>
              )}

              {dialog.kind === 'folder-import' && (
                <>
                  <div className="library-dialog__scan">
                    <div className="library-dialog__scan-icon"><Check size={18} /></div>
                    <div>
                      <strong>{dialog.folderName}</strong>
                      <span>图片会在后台上传并立即显示，AI 分析随后执行。</span>
                    </div>
                    <dl>
                      <div>
                        <dt>图片</dt>
                        <dd>{dialog.entries.filter((entry) => entry.mimeType.startsWith('image/')).length} 张</dd>
                      </div>
                      <div>
                        <dt>总大小</dt>
                        <dd>{formatBytes(dialog.entries.reduce((total, entry) => total + entry.sizeBytes, 0))}</dd>
                      </div>
                      <div>
                        <dt>忽略</dt>
                        <dd>{dialog.entries.filter((entry) => !entry.mimeType.startsWith('image/')).length} 个文件</dd>
                      </div>
                    </dl>
                  </div>
                  <div className="library-import-mode" role="radiogroup" aria-label="目录导入方式">
                    <button
                      type="button"
                      className={importMode === 'ASSETS' ? 'is-active' : ''}
                      onClick={() => setImportMode('ASSETS')}
                    >
                      <strong>拆分为素材</strong>
                      <span>每张图片成为独立素材</span>
                    </button>
                    <button
                      type="button"
                      className={importMode === 'GROUP' ? 'is-active' : ''}
                      onClick={() => setImportMode('GROUP')}
                    >
                      <strong>整理为资产组</strong>
                      <span>整个文件夹对应一个项目资产组</span>
                    </button>
                  </div>
                  {importMode === 'GROUP' && (
                    <label className="library-field">
                      <span>资产组名称</span>
                      <input
                        value={groupName}
                        onChange={(event) => setGroupName(event.target.value)}
                        placeholder="输入资产组名称"
                      />
                    </label>
                  )}
                  <label className="library-field">
                    <span>{importMode === 'GROUP' ? '所属项目' : '目标项目'}</span>
                    {lockFolderProject ? (
                      <div className="library-field__static">
                        <Package size={15} />
                        <strong>
                          {projects.find((project) => project.id === targetProjectId)?.name || '当前项目'}
                        </strong>
                        <small>已锁定</small>
                      </div>
                    ) : (
                      <Select
                        value={targetProjectId}
                        onValueChange={(value) => value && setTargetProjectId(value)}
                      >
                        <SelectTrigger className="library-dialog__select-trigger">
                          <SelectValue>
                            {(value) => projects.find((project) => project.id === value)?.name || '选择项目'}
                          </SelectValue>
                        </SelectTrigger>
                        <SelectContent
                          className="library-select-content"
                          positionerClassName="library-select-positioner"
                          align="start"
                        >
                          {projects.map((project) => (
                            <SelectItem key={project.id} value={project.id}>{project.name}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    )}
                  </label>
                  {importMode === 'GROUP' && (
                    <p className="library-import-hint">
                      资产组始终归属具体项目，子文件夹只用于保持顺序。
                    </p>
                  )}
                </>
              )}
            </div>

            <footer className="library-dialog__footer">
              <Dialog.Close className="library-dialog__button is-secondary" disabled={busy}>
                取消
              </Dialog.Close>
              <button
                type="submit"
                className={`library-dialog__button ${isDestructive ? 'is-danger' : 'is-primary'}`}
                disabled={!canSubmit || busy}
              >
                {busy && <LoaderCircle size={14} className="is-spinning" />}
                {dialog.kind === 'tag' ? '创建标签'
                  : dialog.kind === 'group' ? '创建资产组'
                    : dialog.kind === 'edit' ? '保存修改'
                      : dialog.kind === 'trash' ? '移入回收站'
                        : dialog.kind === 'purge' ? (dialog.all ? '确认清空' : '永久删除')
                          : dialog.kind === 'add-to-group' ? '确认加入'
                            : '开始导入'}
              </button>
            </footer>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function TagManagerDialog({
  tags,
  busy,
  onDelete,
  onClose,
}: {
  tags: LibraryTag[];
  busy: boolean;
  onDelete: (tag: LibraryTag) => void;
  onClose: () => void;
}) {
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  return (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open && !busy) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Backdrop className="library-dialog-backdrop" />
        <Dialog.Popup className="library-dialog library-tag-manager" initialFocus>
          <header className="library-dialog__header">
            <div>
              <Dialog.Title className="library-dialog__title">管理标签</Dialog.Title>
              <Dialog.Description className="library-dialog__description">
                删除标签只解除素材关联，不会删除素材。
              </Dialog.Description>
            </div>
            <Dialog.Close className="library-dialog__close" disabled={busy} aria-label="关闭对话框">
              <X size={16} />
            </Dialog.Close>
          </header>
          <div className="library-tag-manager__body">
            {tags.length === 0 ? (
              <div className="library-tag-manager__empty">还没有标签，可在素材筛选栏点击“新标签”。</div>
            ) : tags.map((tag) => (
              <div className="library-tag-manager__row" key={tag.id}>
                <span><Tags size={14} /> {tag.name}</span>
                {confirmingId === tag.id ? (
                  <div>
                    <button type="button" onClick={() => setConfirmingId(null)} disabled={busy}>取消</button>
                    <button
                      type="button"
                      className="is-danger"
                      onClick={() => onDelete(tag)}
                      disabled={busy}
                    >
                      确认删除
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    className="is-danger-outline"
                    onClick={() => setConfirmingId(tag.id)}
                    disabled={busy}
                    aria-label={`删除标签 ${tag.name}`}
                    title="删除标签"
                  >
                    <Trash2 size={14} />
                  </button>
                )}
              </div>
            ))}
          </div>
          <footer className="library-dialog__footer">
            <Dialog.Close className="library-dialog__button is-secondary" disabled={busy}>关闭</Dialog.Close>
          </footer>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function dialogTitle(dialog: DialogState) {
  switch (dialog.kind) {
    case 'tag': return '新建标签';
    case 'group': return '新建资产组';
    case 'edit': return '编辑资产信息';
    case 'trash': return '移入回收站';
    case 'purge': return dialog.all ? '清空回收站' : '永久删除资产';
    case 'add-to-group': return '加入资产组';
    case 'manage-tags': return '管理标签';
    case 'folder-import': return '导入文件夹';
  }
}

function dialogDescription(dialog: DialogState) {
  switch (dialog.kind) {
    case 'tag': return '用一个清晰的名称整理同类素材。';
    case 'group': return '资产组归属具体项目，用于组织可复用的视频生成素材。';
    case 'edit': return '修改后的名称和描述会同步到项目库与生成上下文。';
    case 'trash': return '此操作不会立即永久删除资产。';
    case 'purge': return '此操作不可恢复，仍被资产组或生成结果引用的资产会保留。';
    case 'add-to-group': return `将 ${dialog.media.length} 个媒体文件加入已有资产组。`;
    case 'manage-tags': return '删除标签只解除素材关联，不会删除素材。';
    case 'folder-import': return '先确定文件夹是一个资产组，还是拆成多个独立素材。';
  }
}

function dialogKey(dialog: DialogState) {
  if (dialog.kind === 'edit' || dialog.kind === 'trash') return `${dialog.kind}:${dialog.asset.id}`;
  if (dialog.kind === 'purge') return `purge:${dialog.all ? 'all' : dialog.assetIds.join(',')}`;
  if (dialog.kind === 'add-to-group') return `add-to-group:${dialog.media.map((item) => item.id).join(',')}`;
  if (dialog.kind === 'folder-import') {
    return `folder-import:${dialog.folderName}:${dialog.files.length}:${dialog.defaultMode}`;
  }
  return dialog.kind;
}

function formatMediaTime(value: number) {
  if (!Number.isFinite(value) || value < 0) return '00:00';
  const totalSeconds = Math.floor(value);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

function scopeDescription(scope: ViewScope, projectName?: string, selectedProjectName?: string) {
  switch (scope) {
    case 'PROJECT': return projectName ? `查看「${projectName}」项目库中的全部素材。` : '请先选择项目。';
    case 'ALL': return selectedProjectName
      ? `仅查看「${selectedProjectName}」项目中的资产。`
      : '聚合当前账号全部项目中的资产，可按项目筛选。';
    case 'GLOBAL': return '可跨项目复用的全局资产。';
    case 'TRASH': return '回收站资产保留 7 天，可恢复。';
  }
}

type ViewScope = ScopeView;

function projectNameForAsset(
  asset: LibraryAsset,
  projects: Array<{ id: string; name: string }>,
  currentProject?: { id: string; name: string } | null,
) {
  const projectId = asset.ownerProjectId || asset.projects[0]?.projectId;
  if (!projectId) return undefined;
  if (currentProject?.id === projectId) return currentProject.name;
  return projects.find((project) => project.id === projectId)?.name;
}

function mimeFromName(name: string) {
  const extension = name.split('.').pop()?.toLowerCase();
  if (extension === 'png') return 'image/png';
  if (extension === 'jpg' || extension === 'jpeg') return 'image/jpeg';
  if (extension === 'webp') return 'image/webp';
  if (extension === 'gif') return 'image/gif';
  if (extension === 'mp4') return 'video/mp4';
  if (extension === 'mov') return 'video/quicktime';
  if (extension === 'mp3') return 'audio/mpeg';
  if (extension === 'wav') return 'audio/wav';
  if (extension === 'm4a') return 'audio/mp4';
  return 'application/octet-stream';
}
