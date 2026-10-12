import { useCallback, useEffect, useMemo, useState } from 'react';
import { Boxes, ChevronLeft, FileText, Folder, Globe2, Image as ImageIcon, Layers, LoaderCircle, X } from 'lucide-react';
import {
  libraryFolderChildren,
  loadCanvasProjectLibrary,
  type CanvasLibraryAsset,
  type CanvasLibraryFolder,
  type CanvasProjectLibraryResponse,
} from '../services/canvasProjectLibrary';

export const ASSET_DRAG_MIME = 'application/x-morrow-asset';
export const LIBRARY_ASSET_DRAG_MIME = 'application/x-morrow-library-asset';

interface AssetDrawerProps {
  projectId: string;
  canvasId: string;
  onClose: () => void;
}

interface FolderLocation {
  id: string;
  name: string;
  scope: 'PROJECT' | 'GLOBAL';
}

type LibraryFilter = 'all' | 'canvas' | 'groups';

export function AssetDrawer({ projectId, canvasId, onClose }: AssetDrawerProps) {
  const [library, setLibrary] = useState<CanvasProjectLibraryResponse | null>(null);
  const [path, setPath] = useState<FolderLocation[]>([]);
  const [filter, setFilter] = useState<LibraryFilter>('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const currentFolder = path.at(-1) ?? null;

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
    <aside className="asset-drawer-panel" style={drawer} aria-label="项目库抽屉">
      <header style={header}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Layers size={16} />
          <strong style={{ fontSize: 14 }}>项目库</strong>
        </div>
        <button onClick={onClose} aria-label="关闭项目库" style={iconButton}><X size={16} /></button>
      </header>

      <div style={filterRow}>
        <FilterButton active={filter === 'all'} label="全部" onClick={() => setFilter('all')} />
        <FilterButton active={filter === 'canvas'} label="项目画布素材" onClick={() => setFilter('canvas')} />
        <FilterButton active={filter === 'groups'} label="资产集" onClick={() => setFilter('groups')} />
      </div>

      <div style={breadcrumbs}>
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
      </div>

      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: 12 }}>
        {loading ? (
          <div style={empty}><LoaderCircle size={16} className="is-spinning" /> 正在读取项目库…</div>
        ) : error ? (
          <div style={{ ...empty, color: '#ff9b9b' }}>{error}</div>
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
                    {assets.map((asset) => <LibraryAssetCard key={asset.id} asset={asset} />)}
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
      <input type="hidden" value={canvasId} readOnly />
    </aside>
  );
}

function LibraryAssetCard({ asset }: { asset: CanvasLibraryAsset }) {
  const [failed, setFailed] = useState(false);
  const canDrag = asset.type === 'IMAGE' || asset.type === 'VIDEO';
  const isDocument = asset.type === 'DOCUMENT' || asset.type === 'OTHER';
  return (
    <div
      draggable={canDrag}
      onDragStart={(event) => {
        if (!canDrag) return;
        event.dataTransfer.setData(LIBRARY_ASSET_DRAG_MIME, JSON.stringify({
          libraryAssetId: asset.id,
          type: asset.type.toLowerCase(),
          name: asset.name,
        }));
        event.dataTransfer.effectAllowed = 'copy';
      }}
      style={assetCard}
      title={canDrag ? '拖到画布上导入' : asset.name}
    >
      <div style={thumb}>
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
        borderColor: active ? 'rgba(215,255,84,0.55)' : 'rgba(255,255,255,0.12)',
        background: active ? 'rgba(215,255,84,0.12)' : 'transparent',
        color: active ? '#d7ff54' : 'inherit',
      }}
    >
      {label}
    </button>
  );
}

const drawer: React.CSSProperties = {
  position: 'absolute', left: 16, top: 16, bottom: 16, width: 300,
  display: 'flex', flexDirection: 'column', boxSizing: 'border-box', zIndex: 40,
  background: 'rgba(24, 26, 30, 0.97)', borderRadius: 14,
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
  height: 66, borderRadius: 6, overflow: 'hidden', background: 'rgba(255,255,255,0.05)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 5,
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
  background: 'rgba(215,255,84,0.09)', color: '#d7ff54', flexShrink: 0,
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
