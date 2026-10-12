import { useCallback, useEffect, useMemo, useState } from 'react';
import { Boxes, ChevronLeft, FileText, Folder, Globe2, Image as ImageIcon, LoaderCircle } from 'lucide-react';
import {
  libraryFolderChildren,
  loadCanvasProjectLibrary,
  type CanvasLibraryAsset,
  type CanvasLibraryFolder,
  type CanvasProjectLibraryResponse,
} from '../services/canvasProjectLibrary';

interface AssetLibraryProps {
  projectId: string;
  onNotify?: (message: string) => void;
}

interface FolderLocation {
  id: string;
  name: string;
  scope: 'PROJECT' | 'GLOBAL';
}

type LibraryFilter = 'all' | 'canvas' | 'groups';

export function AssetLibrary({ projectId, onNotify }: AssetLibraryProps) {
  const [library, setLibrary] = useState<CanvasProjectLibraryResponse | null>(null);
  const [path, setPath] = useState<FolderLocation[]>([]);
  const [filter, setFilter] = useState<LibraryFilter>('all');
  const [loading, setLoading] = useState(true);
  const currentFolder = path.at(-1) ?? null;

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setLibrary(await loadCanvasProjectLibrary({
        projectId,
        scope: currentFolder?.scope,
        folderId: currentFolder?.id,
      }));
    } catch (error) {
      onNotify?.(error instanceof Error ? error.message : '读取项目库失败');
    } finally {
      setLoading(false);
    }
  }, [currentFolder?.id, currentFolder?.scope, onNotify, projectId]);

  useEffect(() => { void reload(); }, [reload]);

  const folders = useMemo(
    () => libraryFolderChildren(library, currentFolder?.scope ?? null, currentFolder?.id ?? null),
    [currentFolder?.id, currentFolder?.scope, library],
  );
  const assets = useMemo(() => {
    const list = library?.assets ?? [];
    if (filter === 'groups') return [];
    return filter === 'canvas'
      ? list.filter((asset) => asset.sourceType?.startsWith('canvas-'))
      : list;
  }, [filter, library]);
  const groups = filter === 'canvas' ? [] : (library?.groups ?? []);

  return (
    <section style={{ padding: '20px 0' }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12, marginBottom: 14 }}>
        <div style={{ flex: 1 }}>
          <h2 style={{ margin: 0, fontSize: 22 }}>项目库</h2>
          <p style={{ margin: '5px 0 0', opacity: 0.55, fontSize: 13 }}>
            文件夹优先展示，包含当前项目画布素材、全局素材和资产集。
          </p>
        </div>
        <div style={{ display: 'flex', gap: 6 }}>
          <FilterButton active={filter === 'all'} label="全部" onClick={() => setFilter('all')} />
          <FilterButton active={filter === 'canvas'} label="项目画布素材" onClick={() => setFilter('canvas')} />
          <FilterButton active={filter === 'groups'} label="资产集" onClick={() => setFilter('groups')} />
        </div>
      </div>

      <div style={breadcrumbs}>
        <button type="button" disabled={path.length === 0} onClick={() => setPath([])} style={crumb}>
          <Folder size={12} /> 项目库
        </button>
        {path.map((folder, index) => (
          <span key={`${folder.scope}:${folder.id}`} style={{ display: 'inline-flex', alignItems: 'center', gap: 3 }}>
            <ChevronLeft size={11} />
            <button type="button" onClick={() => setPath((current) => current.slice(0, index + 1))} style={crumb}>
              {folder.name}
            </button>
          </span>
        ))}
      </div>

      {loading ? (
        <div style={empty}><LoaderCircle size={18} className="is-spinning" /> 正在读取项目库…</div>
      ) : (
        <>
          {folders.length > 0 && (
            <section style={{ margin: '18px 0' }}>
              <div style={sectionTitle}>文件夹 · 双击打开</div>
              <div style={folderGrid}>
                {folders.map((folder) => <FolderCard key={`${folder.scope}:${folder.id}`} folder={folder} onOpen={() => {
                  setPath((current) => [...current, { id: folder.id, name: folder.name, scope: folder.scope }]);
                }} />)}
              </div>
            </section>
          )}

          {filter !== 'groups' && (
            <section style={{ marginTop: 20 }}>
              <div style={sectionTitle}>项目画布素材 · {assets.length} 项</div>
              {assets.length > 0
                ? <div style={assetGrid}>{assets.map((asset) => <AssetCard key={asset.id} asset={asset} />)}</div>
                : <div style={empty}>当前目录暂无画布素材</div>}
            </section>
          )}

          {filter !== 'canvas' && (
            <section style={{ marginTop: 20 }}>
              <div style={sectionTitle}>资产集 · {groups.length} 项</div>
              {groups.length > 0
                ? <div style={groupGrid}>{groups.map((group) => <GroupCard key={group.id} group={group} />)}</div>
                : <div style={empty}>当前目录暂无资产集</div>}
            </section>
          )}
        </>
      )}
    </section>
  );
}

function FolderCard({ folder, onOpen }: { folder: CanvasLibraryFolder; onOpen: () => void }) {
  return (
    <button type="button" onDoubleClick={onOpen} style={folderCard} title="双击打开文件夹">
      <Folder size={28} />
      <strong style={ellipsis}>{folder.name}</strong>
      <small>{folder.descendantAssetCount} 项</small>
    </button>
  );
}

function AssetCard({ asset }: { asset: CanvasLibraryAsset }) {
  const [failed, setFailed] = useState(false);
  const isDocument = asset.type === 'DOCUMENT' || asset.type === 'OTHER';
  return (
    <article style={assetCard}>
      <div style={assetThumb}>
        {isDocument
          ? <FileText size={24} style={{ opacity: 0.55 }} />
          : asset.coverUrl && !failed
          ? <img src={asset.coverUrl} alt="" onError={() => setFailed(true)} style={thumbImage} />
          : <ImageIcon size={24} style={{ opacity: 0.4 }} />}
        {asset.scope === 'GLOBAL' && <span style={scopeBadge}><Globe2 size={10} /></span>}
      </div>
      <strong style={ellipsis}>{asset.name}</strong>
      <small style={assetMeta}>{asset.sourceType?.startsWith('canvas-') ? '画布素材' : '项目库素材'}</small>
    </article>
  );
}

function GroupCard({ group }: { group: CanvasLibraryAsset }) {
  return (
    <article style={groupCard}>
      <span style={groupIcon}><Boxes size={18} /></span>
      <span style={{ minWidth: 0, flex: 1 }}>
        <strong style={ellipsis}>{group.name}</strong>
        <small style={assetMeta}>
          {group.aiKind === 'CHARACTER' ? '人物' : group.aiKind === 'PROP' ? '道具'
            : group.aiKind === 'SCENE' ? '场景' : '通用资产集'}
        </small>
      </span>
      {group.scope === 'GLOBAL' && <Globe2 size={12} style={{ opacity: 0.5 }} />}
    </article>
  );
}

function FilterButton({ active, label, onClick }: { active: boolean; label: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} style={{
      padding: '6px 11px', borderRadius: 999, fontSize: 12, cursor: 'pointer',
      border: `1px solid ${active ? 'rgba(215,255,84,0.55)' : 'rgba(255,255,255,0.12)'}`,
      background: active ? 'rgba(215,255,84,0.12)' : 'transparent',
      color: active ? '#d7ff54' : 'inherit',
    }}>{label}</button>
  );
}

const breadcrumbs: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 4, padding: '8px 10px',
  border: '1px solid rgba(255,255,255,0.1)', borderRadius: 9, background: 'rgba(255,255,255,0.025)',
};
const crumb: React.CSSProperties = {
  border: 'none', background: 'transparent', color: 'inherit', opacity: 0.7,
  padding: 2, cursor: 'pointer', fontSize: 12,
};
const sectionTitle: React.CSSProperties = { marginBottom: 10, opacity: 0.58, fontSize: 12, fontWeight: 600 };
const folderGrid: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))', gap: 10,
};
const folderCard: React.CSSProperties = {
  minHeight: 100, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
  gap: 6, padding: 10, borderRadius: 10, border: '1px solid rgba(255,255,255,0.11)',
  background: 'rgba(255,255,255,0.03)', color: 'inherit', cursor: 'pointer',
};
const assetGrid: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(170px, 1fr))', gap: 12,
};
const assetCard: React.CSSProperties = {
  padding: 10, borderRadius: 10, border: '1px solid rgba(255,255,255,0.1)',
  background: 'rgba(255,255,255,0.03)',
};
const assetThumb: React.CSSProperties = {
  height: 110, position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center',
  borderRadius: 8, overflow: 'hidden', background: 'rgba(255,255,255,0.05)', marginBottom: 8,
};
const thumbImage: React.CSSProperties = { width: '100%', height: '100%', objectFit: 'cover' };
const scopeBadge: React.CSSProperties = {
  position: 'absolute', top: 6, right: 6, padding: 4, borderRadius: 6,
  background: 'rgba(0,0,0,0.55)',
};
const groupGrid: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 10,
};
const groupCard: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 10, padding: 11, borderRadius: 10,
  border: '1px solid rgba(255,255,255,0.1)', background: 'rgba(255,255,255,0.03)',
};
const groupIcon: React.CSSProperties = {
  width: 38, height: 38, display: 'grid', placeItems: 'center', borderRadius: 9,
  background: 'rgba(215,255,84,0.09)', color: '#d7ff54',
};
const ellipsis: React.CSSProperties = {
  display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: 13,
};
const assetMeta: React.CSSProperties = { display: 'block', marginTop: 3, opacity: 0.48, fontSize: 10 };
const empty: React.CSSProperties = {
  minHeight: 110, display: 'flex', alignItems: 'center', justifyContent: 'center',
  gap: 7, opacity: 0.55, fontSize: 12, textAlign: 'center',
};
