import { useCallback, useEffect, useMemo, useState } from 'react';
import { Boxes, Check, ChevronLeft, FileText, Folder, Globe2, Image as ImageIcon, Layers, LoaderCircle, X } from 'lucide-react';
import {
  libraryFolderChildren,
  loadCanvasProjectLibrary,
  type CanvasLibraryAsset,
  type CanvasLibraryFolder,
  type CanvasProjectLibraryResponse,
} from '../services/canvasProjectLibrary';
import { listLibraryAssets } from '../services/projectStorage';
import type { PersistedAsset } from '../types';

export const ASSET_DRAG_MIME = 'application/x-morrow-asset';
export const LIBRARY_ASSET_DRAG_MIME = 'application/x-morrow-library-asset';

interface AssetDrawerProps {
  projectId: string;
  canvasId: string;
  onClose: () => void;
  /** 多选上传模式：点选图片 / 视频，底部「上传到画布」把选中项落成节点。 */
  selecting?: boolean;
  onConfirmUpload?: (libraryAssetIds: string[]) => void;
  /**
   * 「本地素材库」里点一条素材：把它落成画布节点。
   * 抽屉不因此关闭——连插几张是常见动作。
   */
  onInsertLocalAsset?: (assetId: string) => void;
  uploading?: boolean;
  /** 显示/隐藏动效：由父级 usePresence 传进来，退场期间保持挂载 */
  present?: boolean;
}

interface FolderLocation {
  id: string;
  name: string;
  scope: 'PROJECT' | 'GLOBAL';
}

type LibraryFilter = 'all' | 'canvas' | 'groups' | 'local';

export function AssetDrawer({ projectId, canvasId, onClose, selecting = false, onConfirmUpload, onInsertLocalAsset, uploading = false, present = true }: AssetDrawerProps) {
  const [library, setLibrary] = useState<CanvasProjectLibraryResponse | null>(null);
  const [path, setPath] = useState<FolderLocation[]>([]);
  const [filter, setFilter] = useState<LibraryFilter>('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  // 跨项目本地素材：和项目库不是同一份数据源（IndexedDB vs 项目库接口），所以按需单独读。
  const [localAssets, setLocalAssets] = useState<PersistedAsset[]>([]);
  const currentFolder = path.at(-1) ?? null;
  // 本地素材没有文件夹层级，进去就把面包屑和文件夹区让出来。
  const browsingLocal = filter === 'local';

  useEffect(() => {
    if (!selecting) setSelectedIds([]);
  }, [selecting]);

  const toggleSelected = useCallback((assetId: string) => {
    setSelectedIds((current) => current.includes(assetId)
      ? current.filter((id) => id !== assetId)
      : [...current, assetId]);
  }, []);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setLibrary(await loadCanvasProjectLibrary({
        projectId,
        scope: currentFolder?.scope,
        folderId: currentFolder?.id,
      }));
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '读取项目库失败');
    } finally {
      setLoading(false);
    }
  }, [currentFolder?.id, currentFolder?.scope, projectId]);

  useEffect(() => { void reload(); }, [reload]);

  useEffect(() => {
    if (!browsingLocal) return;
    let cancelled = false;
    void listLibraryAssets()
      .then((assets) => { if (!cancelled) setLocalAssets(assets); })
      .catch(() => { if (!cancelled) setLocalAssets([]); });
    return () => { cancelled = true; };
  }, [browsingLocal]);

  const folders = useMemo(
    () => libraryFolderChildren(library, currentFolder?.scope ?? null, currentFolder?.id ?? null),
    [currentFolder?.id, currentFolder?.scope, library],
  );
  const assets = useMemo(() => {
    const list = library?.assets ?? [];
    if (filter === 'groups') return [];
    if (filter === 'canvas') {
      return list.filter((asset) => asset.sourceType?.startsWith('canvas-'));
    }
    return list;
  }, [filter, library]);
  const groups = filter === 'canvas' ? [] : (library?.groups ?? []);

  const openFolder = (folder: CanvasLibraryFolder) => {
    setPath((current) => [...current, { id: folder.id, name: folder.name, scope: folder.scope }]);
  };

  return (
    <aside className="asset-drawer-panel motion-presence" data-present={present} style={drawer} aria-label="项目库抽屉">
      <header style={header}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Layers size={16} />
          <strong style={{ fontSize: 14 }}>{selecting ? '项目库 · 选择素材' : '项目库'}</strong>
        </div>
        <button onClick={onClose} aria-label="关闭项目库" style={iconButton}><X size={16} /></button>
      </header>

      {selecting && (
        <div style={selectHint}>勾选图片 / 视频，底部「上传到画布」按类型落成图片、视频节点</div>
      )}

      <div style={filterRow}>
        <FilterButton active={filter === 'all'} label="全部" onClick={() => setFilter('all')} />
        <FilterButton active={filter === 'canvas'} label="项目画布素材" onClick={() => setFilter('canvas')} />
        <FilterButton active={filter === 'groups'} label="资产集" onClick={() => setFilter('groups')} />
        <FilterButton active={browsingLocal} label="本地素材库" onClick={() => setFilter('local')} />
      </div>

      {!browsingLocal && <div style={breadcrumbs}>
        <button type="button" onClick={() => setPath([])} disabled={path.length === 0} style={crumbButton}>
          <Folder size={12} /> 项目库
        </button>
        {path.map((folder, index) => (
          <span key={`${folder.scope}:${folder.id}`} style={{ display: 'inline-flex', alignItems: 'center', gap: 3 }}>
            <ChevronLeft size={11} />
            <button
              type="button"
              onClick={() => setPath((current) => current.slice(0, index + 1))}
              style={crumbButton}
            >
              {folder.name}
            </button>
          </span>
        ))}
      </div>}

      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: 12 }}>
        {browsingLocal ? (
          localAssets.length > 0 ? (
            <section>
              <div style={sectionTitle}>本地素材库 · {localAssets.length} 项</div>
              <div style={assetGrid}>
                {localAssets.map((item) => (
                  <LocalLibraryCard
                    key={item.id}
                    asset={item}
                    onInsert={() => onInsertLocalAsset?.(item.id)}
                  />
                ))}
              </div>
            </section>
          ) : (
            <div style={empty}>还没有保存到本地素材库的素材</div>
          )
        ) : loading ? (
          <div style={empty}><LoaderCircle size={16} className="is-spinning" /> 正在读取项目库…</div>
        ) : error ? (
          <div style={{ ...empty, color: '#b0b0b0' }}>{error}</div>
        ) : (
          <>
            {folders.length > 0 && (
              <section style={{ marginBottom: 16 }}>
                <div style={sectionTitle}>文件夹 · 双击打开</div>
                <div style={folderGrid}>
                  {folders.map((folder) => (
                    <button
                      key={`${folder.scope}:${folder.id}`}
                      type="button"
                      onDoubleClick={() => openFolder(folder)}
                      style={folderCard}
                      title="双击打开文件夹"
                    >
                      <Folder size={25} />
                      <strong style={ellipsis}>{folder.name}</strong>
                      <small>{folder.descendantAssetCount} 项</small>
                      {folder.scope === 'GLOBAL' && <Globe2 size={10} style={{ opacity: 0.55 }} />}
                    </button>
                  ))}
                </div>
              </section>
            )}

            {filter !== 'groups' && (
              <section style={{ marginBottom: groups.length ? 18 : 0 }}>
                <div style={sectionTitle}>项目画布素材 · {assets.length} 项</div>
                {assets.length > 0 ? (
                  <div style={assetGrid}>
                    {assets.map((asset) => <LibraryAssetCard
                      key={asset.id}
                      asset={asset}
                      selecting={selecting}
                      selected={selectedIds.includes(asset.id)}
                      onToggle={toggleSelected}
                    />)}
                  </div>
                ) : (
                  <div style={empty}>当前目录暂无画布素材</div>
                )}
              </section>
            )}

            {filter !== 'canvas' && groups.length > 0 && (
              <section>
                <div style={sectionTitle}>资产集 · {groups.length} 项</div>
                <div style={groupList}>
                  {groups.map((group) => (
                    <div
                      key={group.id}
                      draggable
                      onDragStart={(event) => {
                        event.dataTransfer.setData(LIBRARY_ASSET_DRAG_MIME, JSON.stringify({
                          libraryAssetId: group.id,
                          type: 'group',
                          name: group.name,
                        }));
                        event.dataTransfer.effectAllowed = 'copy';
                      }}
                      style={groupCard}
                      title="拖到画布上列出全部文件"
                    >
                      <span style={groupIcon}><Boxes size={17} /></span>
                      <span style={{ minWidth: 0, flex: 1 }}>
                        <strong style={ellipsis}>{group.name}</strong>
                        <small style={groupMeta}>
                          {group.aiKind === 'CHARACTER' ? '人物' : group.aiKind === 'PROP' ? '道具'
                            : group.aiKind === 'SCENE' ? '场景' : '通用资产集'}
                        </small>
                      </span>
                      {group.scope === 'GLOBAL' && <Globe2 size={11} style={{ opacity: 0.5 }} />}
                    </div>
                  ))}
                </div>
              </section>
            )}

            {filter === 'groups' && groups.length === 0 && (
              <div style={empty}>当前目录暂无资产集</div>
            )}
          </>
        )}
      </div>
      {selecting && (
        <footer style={selectFooter}>
          <span style={{ fontSize: 11, opacity: 0.72 }}>已选 {selectedIds.length} 项</span>
          <div style={{ display: 'flex', gap: 6 }}>
            <button type="button" style={selectCancel} onClick={() => setSelectedIds([])} disabled={!selectedIds.length}>清空</button>
            <button
              type="button"
              style={{ ...selectConfirm, opacity: selectedIds.length && !uploading ? 1 : 0.45, cursor: selectedIds.length && !uploading ? 'pointer' : 'not-allowed' }}
              disabled={!selectedIds.length || uploading}
              onClick={() => onConfirmUpload?.(selectedIds)}
            >
              {uploading ? '上传中…' : '上传到画布'}
            </button>
          </div>
        </footer>
      )}
      <input type="hidden" value={canvasId} readOnly />
    </aside>
  );
}

/**
 * 跨项目本地素材（IndexedDB 里的 PersistedAsset）卡片。
 * 和项目库素材一样可拖到画布（同一个 ASSET_DRAG_MIME），也可以点一下直接插到视口中心。
 */
function LocalLibraryCard({ asset, onInsert }: { asset: PersistedAsset; onInsert: () => void }) {
  const [url, setUrl] = useState('');
  useEffect(() => {
    const next = URL.createObjectURL(asset.thumbnailBlob);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [asset]);
  return (
    <div
      draggable
      onDragStart={(event) => {
        event.dataTransfer.setData(ASSET_DRAG_MIME, asset.id);
        event.dataTransfer.effectAllowed = 'copy';
      }}
      style={localCard}
      title="拖到画布，或点击插入画布"
    >
      <button type="button" onClick={onInsert} style={localInsert} aria-label={`插入画布 ${asset.name}`}>
        <span style={thumb}>
          {url ? <img src={url} alt="" draggable={false} style={image} /> : <ImageIcon size={19} style={{ opacity: 0.4 }} />}
        </span>
        <strong style={ellipsis}>{asset.name}</strong>
        <small style={assetMeta}>
          {asset.kind === 'video' ? 'video' : 'image'} · {asset.width} × {asset.height}
        </small>
      </button>
    </div>
  );
}

function LibraryAssetCard({ asset, selecting, selected, onToggle }: {
  asset: CanvasLibraryAsset;
  selecting: boolean;
  selected: boolean;
  onToggle: (assetId: string) => void;
}) {
  const [failed, setFailed] = useState(false);
  const canDrag = asset.type === 'IMAGE' || asset.type === 'VIDEO';
  const isDocument = asset.type === 'DOCUMENT' || asset.type === 'OTHER';
  const selectable = canDrag;
  const interactive = selecting ? selectable : canDrag;
  return (
    <div
      role={selecting ? 'checkbox' : undefined}
      aria-checked={selecting ? selected : undefined}
      aria-label={selecting ? `选择素材 ${asset.name}` : undefined}
      aria-disabled={selecting && !selectable ? true : undefined}
      tabIndex={selecting && selectable ? 0 : undefined}
      draggable={canDrag && !selecting}
      onDragStart={(event) => {
        if (!canDrag) return;
        event.dataTransfer.setData(LIBRARY_ASSET_DRAG_MIME, JSON.stringify({
          libraryAssetId: asset.id,
          type: asset.type.toLowerCase(),
          name: asset.name,
        }));
        event.dataTransfer.effectAllowed = 'copy';
      }}
      onClick={selecting && selectable ? () => onToggle(asset.id) : undefined}
      onKeyDown={selecting && selectable
        ? (event) => {
          if (event.key !== 'Enter' && event.key !== ' ') return;
          event.preventDefault();
          onToggle(asset.id);
        }
        : undefined}
      style={{
        ...assetCard,
        ...(selecting
          ? {
            cursor: selectable ? 'pointer' : 'not-allowed',
            opacity: selectable ? 1 : 0.42,
            borderColor: selected ? 'rgba(234,234,234,0.75)' : assetCard.borderColor,
            background: selected ? 'rgba(234,234,234,0.10)' : assetCard.background,
          }
          : {}),
      }}
      title={selecting
        ? (selectable ? (selected ? '点击取消选择' : '点击选择') : '仅支持图片 / 视频')
        : (canDrag ? '拖到画布上导入' : asset.name)}
    >
      <div style={thumb}>
        {selecting && selectable && (
          <span style={{ ...selectBadge, opacity: selected ? 1 : 0.42 }} aria-hidden="true">{selected ? <Check size={12} /> : null}</span>
        )}
        {isDocument ? (
          <FileText size={19} style={{ opacity: 0.55 }} />
        ) : asset.coverUrl && !failed ? (
          asset.type === 'VIDEO'
            ? <video
                src={`${asset.coverUrl}#t=0.1`}
                muted
                playsInline
                preload="metadata"
                onError={() => setFailed(true)}
                style={image}
              />
            : <img src={asset.coverUrl} alt="" draggable={false} onError={() => setFailed(true)} style={image} />
        ) : <ImageIcon size={19} style={{ opacity: 0.4 }} />}
      </div>
      <strong style={ellipsis}>{asset.name}</strong>
      <small style={assetMeta}>{asset.type.toLowerCase()}{asset.sourceType?.startsWith('canvas-') ? ' · 画布' : ''}</small>
    </div>
  );
}

function FilterButton({ active, label, onClick }: { active: boolean; label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        ...filterButton,
        borderColor: active ? 'rgba(255,255,255,0.55)' : 'rgba(255,255,255,0.12)',
        background: active ? 'rgba(255,255,255,0.12)' : 'transparent',
        color: active ? '#f4f4f5' : 'inherit',
      }}
    >
      {label}
    </button>
  );
}

const drawer: React.CSSProperties = {
  // 左侧工具岛（x 16~68）与项目栏（top 16~64）要留出来，否则抽屉会压住工具岛按钮
  position: 'absolute', left: 80, top: 92, bottom: 16, width: 300,
  display: 'flex', flexDirection: 'column', boxSizing: 'border-box', zIndex: 40,
  background: 'rgba(26,26,26,0.97)', borderRadius: 14,
  border: '1px solid rgba(255,255,255,0.12)', boxShadow: '0 12px 40px rgba(0,0,0,0.4)',
  overflow: 'hidden',
};
const header: React.CSSProperties = {
  padding: '13px 14px', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
  borderBottom: '1px solid rgba(255,255,255,0.08)',
};
const filterRow: React.CSSProperties = {
  padding: '9px 10px', display: 'flex', gap: 5, flexWrap: 'wrap',
  borderBottom: '1px solid rgba(255,255,255,0.07)',
};
const breadcrumbs: React.CSSProperties = {
  minHeight: 34, padding: '7px 10px', display: 'flex', alignItems: 'center', gap: 3,
  overflowX: 'auto', whiteSpace: 'nowrap', borderBottom: '1px solid rgba(255,255,255,0.07)',
};
const crumbButton: React.CSSProperties = {
  border: 'none', background: 'transparent', color: 'inherit', opacity: 0.72,
  padding: 2, cursor: 'pointer', fontSize: 11,
};
const sectionTitle: React.CSSProperties = {
  marginBottom: 8, fontSize: 11, opacity: 0.55, fontWeight: 600,
};
const folderGrid: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(112px, 1fr))', gap: 8,
};
const folderCard: React.CSSProperties = {
  minHeight: 86, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
  gap: 4, padding: 8, border: '1px solid rgba(255,255,255,0.11)', borderRadius: 9,
  background: 'rgba(255,255,255,0.035)', color: 'inherit', cursor: 'pointer',
};
const assetGrid: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(106px, 1fr))', gap: 8,
};
const assetCard: React.CSSProperties = {
  padding: 6, borderRadius: 9, border: '1px solid rgba(255,255,255,0.1)',
  background: 'rgba(255,255,255,0.03)', cursor: 'grab',
};
const thumb: React.CSSProperties = {
  position: 'relative',
  height: 66, borderRadius: 6, overflow: 'hidden', background: 'rgba(255,255,255,0.05)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 5,
};
const localCard: React.CSSProperties = {
  ...assetCard, padding: 0, cursor: 'grab', overflow: 'hidden',
};
const localInsert: React.CSSProperties = {
  width: '100%', display: 'block', padding: 6, border: 'none', background: 'transparent',
  color: 'inherit', textAlign: 'left', cursor: 'pointer', font: 'inherit',
};
const selectBadge: React.CSSProperties = {
  position: 'absolute', right: 4, top: 4, width: 16, height: 16, display: 'grid', placeItems: 'center',
  borderRadius: 5, border: '1px solid rgba(234,234,234,0.75)', background: 'rgba(13,13,13,0.72)', color: '#eaeaea',
};
const selectHint: React.CSSProperties = {
  padding: '8px 12px', fontSize: 10.5, lineHeight: 1.5, color: 'rgba(244,244,245,0.6)',
  borderBottom: '1px solid rgba(255,255,255,0.07)',
};
const selectFooter: React.CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8,
  padding: '10px 12px', borderTop: '1px solid rgba(255,255,255,0.09)', background: 'rgba(255,255,255,0.02)',
};
const selectCancel: React.CSSProperties = {
  padding: '6px 10px', borderRadius: 8, border: '1px solid rgba(255,255,255,0.14)',
  background: 'transparent', color: 'inherit', fontSize: 11, cursor: 'pointer',
};
const selectConfirm: React.CSSProperties = {
  padding: '6px 12px', borderRadius: 8, border: 'none',
  background: '#eaeaea', color: '#121212', fontSize: 11, fontWeight: 600,
};
const image: React.CSSProperties = { width: '100%', height: '100%', objectFit: 'cover' };
const ellipsis: React.CSSProperties = {
  display: 'block', fontSize: 11, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};
const assetMeta: React.CSSProperties = { display: 'block', marginTop: 2, opacity: 0.48, fontSize: 9 };
const groupList: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 7 };
const groupCard: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8, padding: 8, borderRadius: 9,
  border: '1px solid rgba(255,255,255,0.1)', background: 'rgba(255,255,255,0.03)',
};
const groupIcon: React.CSSProperties = {
  width: 34, height: 34, display: 'grid', placeItems: 'center', borderRadius: 8,
  background: 'rgba(255,255,255,0.09)', color: '#f4f4f5', flexShrink: 0,
};
const groupMeta: React.CSSProperties = { display: 'block', marginTop: 2, opacity: 0.48, fontSize: 9 };
const empty: React.CSSProperties = {
  minHeight: 100, display: 'flex', alignItems: 'center', justifyContent: 'center',
  gap: 7, opacity: 0.55, fontSize: 12, textAlign: 'center',
};
const iconButton: React.CSSProperties = {
  background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', padding: 4,
};
const filterButton: React.CSSProperties = {
  padding: '4px 9px', borderRadius: 999, border: '1px solid', cursor: 'pointer', fontSize: 10,
};
