import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Dialog } from '@base-ui/react/dialog';
import { Slider } from '@base-ui/react/slider';
import {
  Boxes,
  Check,
  ChevronLeft,
  CircleAlert,
  Film,
  FolderPlus,
  Grid2X2,
  Image as ImageIcon,
  List,
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
  type ImportScanResult,
  type LibraryAsset,
  type LibraryAssetDetail,
  type LibraryMedia,
  type LibraryTag,
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
import { buildLibraryQuery, formatBytes, scopeFromQuery } from '../lib/libraryFilters';
import './LibraryPage.css';

type ScopeView = 'PROJECT' | 'ALL' | 'GLOBAL' | 'GROUPS' | 'TAGS' | 'TRASH';

const NAV_ITEMS: Array<{ key: ScopeView; label: string; icon: React.ReactNode }> = [
  { key: 'PROJECT', label: '当前项目', icon: <Package size={16} /> },
  { key: 'ALL', label: '所有项目', icon: <Boxes size={16} /> },
  { key: 'GLOBAL', label: '全局库', icon: <Sparkles size={16} /> },
  { key: 'GROUPS', label: '资产组', icon: <Film size={16} /> },
  { key: 'TAGS', label: '标签', icon: <Tags size={16} /> },
  { key: 'TRASH', label: '回收站', icon: <Trash2 size={16} /> },
];

const TYPE_LABEL: Record<AssetType, string> = {
  IMAGE: '图片',
  VIDEO: '视频',
  AUDIO: '音频',
  AI_ASSET: 'AI资产',
  GROUP: '资产组',
};

type DialogState =
  | { kind: 'tag' }
  | { kind: 'group' }
  | { kind: 'edit'; asset: LibraryAsset }
  | { kind: 'trash'; asset: LibraryAsset }
  | { kind: 'add-to-group'; media: LibraryMedia[]; groups: LibraryAsset[] }
  | { kind: 'folder-import'; files: File[]; entries: ImportEntry[]; scan: ImportScanResult };

type DialogResult = {
  name?: string;
  description?: string;
  groupId?: string;
};

export function LibraryPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const currentProject = useProjectStore((state) => state.currentProject);
  const initialScope = scopeFromQuery(searchParams.get('scope'));
  const [scopeView, setScopeView] = useState<ScopeView>(initialScope);
  const [type, setType] = useState<AssetType | ''>((searchParams.get('type') as AssetType) || '');
  const [query, setQuery] = useState(searchParams.get('q') || '');
  const [view, setView] = useState<'cards' | 'table'>(searchParams.get('view') === 'table' ? 'table' : 'cards');
  const [tagMode, setTagMode] = useState<TagMode>(searchParams.get('tagMode') === 'ANY' ? 'ANY' : 'ALL');
  const [selectedTagIds, setSelectedTagIds] = useState<string[]>(
    (searchParams.get('tags') || '').split(',').filter(Boolean),
  );
  const [assets, setAssets] = useState<LibraryAsset[]>([]);
  const [tags, setTags] = useState<LibraryTag[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<LibraryAssetDetail | null>(null);
  const [importState, setImportState] = useState<{ total: number; done: number; label: string } | null>(null);
  const [dialog, setDialog] = useState<DialogState | null>(null);
  const [dialogBusy, setDialogBusy] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const folderInput = useRef<HTMLInputElement>(null);

  const scopedProjectId = scopeView === 'PROJECT' ? currentProject?.id : undefined;
  const deleted = scopeView === 'TRASH';
  const effectiveScope: AssetScope | undefined =
    scopeView === 'GLOBAL' ? 'GLOBAL' : scopeView === 'PROJECT' || scopeView === 'TRASH' ? 'PROJECT' : undefined;
  const effectiveType: AssetType | undefined =
    scopeView === 'GROUPS' ? 'GROUP' : (type || undefined);

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
          page: 1,
          size: 100,
        }),
        libraryApi.listTags(),
      ]);
      setAssets(assetResponse.data.data.records || []);
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
    const next = buildLibraryQuery({ scopeView, type, query, view, tagIds: selectedTagIds, tagMode });
    setSearchParams(next, { replace: true });
  }, [query, scopeView, selectedTagIds, setSearchParams, tagMode, type, view]);

  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return;
    }
    void libraryApi.getAsset(selectedId).then((response) => setDetail(response.data.data));
  }, [selectedId, assets]);

  const selectedTagSet = useMemo(() => new Set(selectedTagIds), [selectedTagIds]);

  const uploadOne = async (file: File, relativePath?: string) => {
    const scope: AssetScope = currentProject?.id ? 'PROJECT' : 'GLOBAL';
    await libraryApi.uploadDirect(file, {
      scope,
      projectId: currentProject?.id,
      name: file.name.replace(/\.[^.]+$/, ''),
      relativePath,
    });
  };

  const handleFiles = async (files: FileList | null) => {
    const list = Array.from(files || []);
    if (!list.length) return;
    setImportState({ total: list.length, done: 0, label: '正在上传素材' });
    try {
      for (let index = 0; index < list.length; index += 1) {
        await uploadOne(list[index]);
        setImportState({ total: list.length, done: index + 1, label: '正在上传素材' });
      }
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '上传失败');
    } finally {
      setImportState(null);
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const handleFolder = async (files: FileList | null) => {
    const list = Array.from(files || []);
    if (!list.length) return;
    const entries = list.map((file) => ({
      itemKey: `${(file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name}:${file.size}:${file.lastModified}`,
      relativePath: (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name,
      fileName: file.name,
      sizeBytes: file.size,
      mimeType: file.type || mimeFromName(file.name),
    }));
    setImportState({ total: list.length, done: 0, label: '正在扫描目录' });
    try {
      const scope: AssetScope = currentProject?.id ? 'PROJECT' : 'GLOBAL';
      const scan = await libraryApi.scanImport({
        scope,
        projectId: currentProject?.id,
        entries,
      });
      const result = scan.data.data;
      setDialog({ kind: 'folder-import', files: list, entries, scan: result });
    } catch (err) {
      setError(err instanceof Error ? err.message : '目录导入失败');
    } finally {
      setImportState(null);
      if (folderInput.current) folderInput.current.value = '';
    }
  };

  const importFolderFiles = async ({
    files,
    entries,
    scan,
  }: Extract<DialogState, { kind: 'folder-import' }>) => {
    const imageFiles = files.filter((file) => (file.type || mimeFromName(file.name)).startsWith('image/'));
    if (!imageFiles.length) {
      setError('目录中没有可导入的图片文件');
      return;
    }
    setImportState({ total: imageFiles.length, done: 0, label: '正在导入图片' });
    try {
      let done = 0;
      const concurrency = 3;
      let cursor = 0;
      const workers = Array.from({ length: Math.min(concurrency, imageFiles.length) }, async () => {
        while (cursor < imageFiles.length) {
          const index = cursor++;
          const file = imageFiles[index];
          const relativePath = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name;
          const entry = entries.find((item) => item.relativePath === relativePath);
          if (!entry) continue;
          await libraryApi.uploadImportItem(scan.jobId, entry.itemKey, scan.confirmationToken, file);
          done += 1;
          setImportState({ total: imageFiles.length, done, label: '正在导入图片' });
        }
      });
      await Promise.all(workers);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : '目录导入失败');
    } finally {
      setImportState(null);
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
        if (!name) return;
        await libraryApi.createGroup({
          scope: currentProject?.id ? 'PROJECT' : 'GLOBAL',
          ownerProjectId: currentProject?.id,
          name,
        });
        setScopeView('GROUPS');
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

      if (dialog.kind === 'add-to-group') {
        const groupId = result.groupId;
        if (!groupId) return;
        const detailResponse = await libraryApi.getGroup(groupId);
        const existing = detailResponse.data.data.media;
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

      setDialog(null);
      if (dialog.kind === 'folder-import') {
        await importFolderFiles(dialog);
      }
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

  const addAssetToGroup = async (media: LibraryMedia[]) => {
    try {
      const groupResponse = await libraryApi.listAssets({
        scope: scopeView === 'GLOBAL' ? 'GLOBAL' : undefined,
        projectId: scopeView === 'GLOBAL' ? undefined : currentProject?.id,
        type: 'GROUP',
        page: 1,
        size: 100,
      });
      const groups = groupResponse.data.data.records || [];
      if (groups.length === 0) {
        setError('当前作用域没有资产组，请先新建资产组');
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
                if (item.key === 'GROUPS') setType('GROUP');
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
            <p>{scopeDescription(scopeView, currentProject?.name)}</p>
          </div>
          <div className="library-header__actions">
            <button
              className="is-secondary"
              onClick={() => folderInput.current?.click()}
            >
              <FolderPlus size={15} /> 上传文件夹
            </button>
            <button
              className="is-ghost"
              onClick={() => setDialog({ kind: 'group' })}
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
          <Select
            value={type || 'ALL'}
            onValueChange={(value) => setType(value === 'ALL' ? '' : value as AssetType)}
            disabled={scopeView === 'GROUPS' || scopeView === 'TRASH'}
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
            <strong>{assets.length}</strong> 项
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
            {scopeView !== 'TRASH' && (query || type || selectedTagIds.length > 0) && (
              <button
                className="library-empty__action"
                onClick={() => {
                  setQuery('');
                  setType('');
                  setSelectedTagIds([]);
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
              />
            ))}
          </div>
        ) : (
          <AssetTable assets={assets} onOpen={setSelectedId} />
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

      {importState && (
        <div className="library-import-toast" role="status" aria-live="polite">
          <LoaderCircle className="is-spinning" size={15} />
          <span>{importState.label}</span>
          <strong>{importState.done}/{importState.total}</strong>
          <i><b style={{ width: `${importState.total ? importState.done / importState.total * 100 : 0}%` }} /></i>
        </div>
      )}

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
        />
      )}

      {dialog && (
        <LibraryActionDialog
          key={dialogKey(dialog)}
          dialog={dialog}
          busy={dialogBusy}
          onClose={() => setDialog(null)}
          onConfirm={(result) => void handleDialogConfirm(result)}
        />
      )}
    </div>
  );
}

function AssetCard({
  asset,
  onOpen,
  onRestore,
}: {
  asset: LibraryAsset;
  onOpen: () => void;
  onRestore?: () => void;
}) {
  return (
    <article className="library-card">
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
          <p>{asset.description || asset.aiDescription || '暂无描述'}</p>
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

function AssetTable({ assets, onOpen }: { assets: LibraryAsset[]; onOpen: (id: string) => void }) {
  return (
    <div className="library-table-wrap">
      <table className="library-table">
        <thead><tr><th>名称</th><th>类型</th><th>作用域</th><th>标签</th><th>来源</th><th>更新时间</th></tr></thead>
        <tbody>
          {assets.map((asset) => (
            <tr
              key={asset.id}
              onClick={() => onOpen(asset.id)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  onOpen(asset.id);
                }
              }}
              tabIndex={0}
            >
              <td><div className="library-table__name"><TypeIcon type={asset.type} /><span>{asset.name}</span></div></td>
              <td>{TYPE_LABEL[asset.type]}</td>
              <td>{asset.scope === 'GLOBAL' ? '全局库' : '项目库'}</td>
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
}) {
  const { asset, media } = detail;
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
        <p className="library-detail__description">{asset.description || asset.aiDescription || '暂无描述'}</p>
        <div className="library-detail__facts">
          <span>作用域 <b>{asset.scope === 'GLOBAL' ? '全局库' : '项目库'}</b></span>
          <span>版本 <b>v{asset.version}</b></span>
          <span>媒体 <b>{media.length}</b></span>
        </div>
        <section>
          <h3>标签</h3>
          <div className="library-detail__tags">
            {tags.map((tag) => {
              const active = asset.tags.some((item) => item.id === tag.id);
              return <button key={tag.id} className={active ? 'is-active' : ''} onClick={() => onToggleTag(tag)}>{tag.name}</button>;
            })}
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
  onClose,
  onConfirm,
}: {
  dialog: DialogState;
  busy: boolean;
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

  const isDestructive = dialog.kind === 'trash';
  const title = dialogTitle(dialog);
  const descriptionText = dialogDescription(dialog);
  const canSubmit = dialog.kind === 'tag' || dialog.kind === 'group'
    ? Boolean(name.trim())
    : dialog.kind === 'edit'
      ? Boolean(name.trim())
      : dialog.kind === 'add-to-group'
        ? Boolean(groupId)
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
              onConfirm({ name, description, groupId });
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
                    <SelectContent className="library-select-content" align="start">
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

              {dialog.kind === 'folder-import' && (
                <div className="library-dialog__scan">
                  <div className="library-dialog__scan-icon"><Check size={18} /></div>
                  <div>
                    <strong>目录扫描完成</strong>
                    <span>确认后将开始上传，请保持当前页面打开。</span>
                  </div>
                  <dl>
                    <div><dt>图片</dt><dd>{dialog.scan.imageCount} 张</dd></div>
                    <div><dt>总大小</dt><dd>{formatBytes(dialog.scan.totalBytes)}</dd></div>
                    <div><dt>忽略</dt><dd>{dialog.scan.ignoredCount} 个文件</dd></div>
                  </dl>
                </div>
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

function dialogTitle(dialog: DialogState) {
  switch (dialog.kind) {
    case 'tag': return '新建标签';
    case 'group': return '新建资产组';
    case 'edit': return '编辑资产信息';
    case 'trash': return '移入回收站';
    case 'add-to-group': return '加入资产组';
    case 'folder-import': return '确认目录导入';
  }
}

function dialogDescription(dialog: DialogState) {
  switch (dialog.kind) {
    case 'tag': return '用一个清晰的名称整理同类素材。';
    case 'group': return '将图片、视频和音频组织成可复用的视频生成素材包。';
    case 'edit': return '修改后的名称和描述会同步到项目库与生成上下文。';
    case 'trash': return '此操作不会立即永久删除资产。';
    case 'add-to-group': return `将 ${dialog.media.length} 个媒体文件加入已有资产组。`;
    case 'folder-import': return '请确认扫描结果后再开始导入图片。';
  }
}

function dialogKey(dialog: DialogState) {
  if (dialog.kind === 'edit' || dialog.kind === 'trash') return `${dialog.kind}:${dialog.asset.id}`;
  if (dialog.kind === 'add-to-group') return `add-to-group:${dialog.media.map((item) => item.id).join(',')}`;
  if (dialog.kind === 'folder-import') return `folder-import:${dialog.scan.jobId}`;
  return dialog.kind;
}

function formatMediaTime(value: number) {
  if (!Number.isFinite(value) || value < 0) return '00:00';
  const totalSeconds = Math.floor(value);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

function scopeDescription(scope: ViewScope, projectName?: string) {
  switch (scope) {
    case 'PROJECT': return projectName ? `仅查看「${projectName}」及全局库中已关联到本项目的资产。` : '请先选择项目。';
    case 'ALL': return '聚合当前账号全部项目中的资产。';
    case 'GLOBAL': return '可跨项目复用的全局资产。';
    case 'GROUPS': return '用于视频生成的跨媒体素材包。';
    case 'TAGS': return '创建和管理项目库标签。';
    case 'TRASH': return '回收站资产保留 7 天，可恢复。';
  }
}

type ViewScope = ScopeView;

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
