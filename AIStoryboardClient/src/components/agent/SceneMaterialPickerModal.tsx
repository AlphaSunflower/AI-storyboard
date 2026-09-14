import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { Check, FolderOpen, Image as ImageIcon, LoaderCircle, Package, Video, X, Boxes } from 'lucide-react';
import { agentApi, type ProjectOutputMaterialGroup } from '../../api/agent';
import { assetApi, type Asset, type SceneMaterialReferenceInput } from '../../api/assets';
import { sceneApi } from '../../api/scenes';
import { assetUrl } from '../../config';
import { DS } from './ChatComposer';
import { libraryApi, type LibraryAsset } from '../../api/library';

type PickerTab = 'assets' | 'groups' | 'output' | 'scene';

interface Props {
  open: boolean;
  sceneId: string;
  projectId?: string;
  currentConversationId?: string | null;
  sceneImageUrls: string[];
  onClose: () => void;
  onSaved: () => void | Promise<void>;
}

export function SceneMaterialPickerModal({
  open,
  sceneId,
  projectId,
  currentConversationId,
  sceneImageUrls,
  onClose,
  onSaved,
}: Props) {
  const [tab, setTab] = useState<PickerTab>('assets');
  const [outputKind, setOutputKind] = useState<'image' | 'video'>('image');
  const [assets, setAssets] = useState<Asset[]>([]);
  const [groups, setGroups] = useState<LibraryAsset[]>([]);
  const [selectedGroupIds, setSelectedGroupIds] = useState<Set<string>>(new Set());
  const [outputGroups, setOutputGroups] = useState<ProjectOutputMaterialGroup[]>([]);
  const [selectedAssetIds, setSelectedAssetIds] = useState<Set<string>>(new Set());
  const [selectedRefs, setSelectedRefs] = useState<Map<string, SceneMaterialReferenceInput>>(new Map());
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open || !projectId) return;
    let cancelled = false;
    setLoading(true);
    setError('');
    Promise.all([
      assetApi.list(projectId),
      assetApi.listSceneAssets(sceneId),
      sceneApi.listReferences(sceneId),
      agentApi.listProjectOutputMaterials(projectId, currentConversationId),
      libraryApi.listAssets({ scope: 'PROJECT', projectId, type: 'GROUP', page: 1, size: 100 }),
    ]).then(([assetRes, sceneAssetRes, referenceRes, outputRes, groupRes]) => {
      if (cancelled) return;
      setAssets(assetRes.data.data ?? []);
      setOutputGroups(outputRes.data.data ?? []);
      setGroups(groupRes.data.data.records ?? []);

      const linked = sceneAssetRes.data.data;
      const linkedIds = new Set<string>();
      for (const asset of [...(linked?.imageAssets ?? []), ...(linked?.videoAssets ?? [])]) linkedIds.add(asset.id);
      setSelectedAssetIds(linkedIds);

      const linkedRefs = (referenceRes.data.data ?? [])
        .filter((ref) => ref.sourceType === 'output' || ref.sourceType === 'scene_image')
        .map<SceneMaterialReferenceInput>((ref) => ({
          type: ref.type === 'video' ? 'video' : 'image',
          purpose: ref.purpose === 'video' ? 'video' : 'image',
          url: ref.url,
          role: 'REFERENCE',
          sourceType: ref.sourceType === 'scene_image' ? 'scene_image' : 'output',
          sourceId: ref.sourceId ?? '',
          sourceConversationId: ref.sourceConversationId,
        }));
      setSelectedRefs(new Map(linkedRefs.map((ref) => [ref.url, ref])));
    }).catch((err) => {
      if (!cancelled) setError(err instanceof Error ? err.message : '加载素材失败');
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => { cancelled = true; };
  }, [open, projectId, sceneId, currentConversationId]);

  const availableRefKeys = useMemo(() => {
    const keys = new Set<string>();
    for (const group of outputGroups) {
      for (const item of group.items) {
        for (const url of item.urls) keys.add(`${group.conversationId}:${item.id}:${url}`);
      }
    }
    for (const url of sceneImageUrls) keys.add(`scene:${sceneId}:${url}`);
    return keys;
  }, [outputGroups, sceneImageUrls, sceneId]);

  const orphanRefs = [...selectedRefs.values()].filter((ref) => {
    const key = ref.sourceType === 'scene_image'
      ? `scene:${ref.sourceId}:${ref.url}`
      : `${ref.sourceConversationId ?? ''}:${ref.sourceId}:${ref.url}`;
    return !availableRefKeys.has(key);
  });

  if (!open) return null;

  const toggleAsset = (id: string) => {
    setSelectedAssetIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleRef = (ref: SceneMaterialReferenceInput) => {
    if (ref.type === 'video' && !selectedRefs.has(ref.url)
        && [...selectedRefs.values()].some((selected) => selected.type === 'video')) {
      setError('每个分镜最多选择 1 个视频参考');
      return;
    }
    setSelectedRefs((prev) => {
      const next = new Map(prev);
      if (next.has(ref.url)) next.delete(ref.url); else next.set(ref.url, ref);
      return next;
    });
  };

  const save = async () => {
    setSaving(true);
    setError('');
    try {
      const groupSnapshots = [];
      const groupRefs: SceneMaterialReferenceInput[] = [];
      for (const groupId of selectedGroupIds) {
        const snapshot = (await libraryApi.createSnapshot(groupId, 'VIDEO')).data.data;
        groupSnapshots.push({
          snapshotId: snapshot.snapshotId,
          groupId: snapshot.groupId,
          groupVersion: snapshot.groupVersion,
          contentHash: snapshot.contentHash,
          target: snapshot.target,
          items: snapshot.items as unknown as Array<Record<string, unknown>>,
        });
        for (const item of snapshot.items) {
          groupRefs.push({
            type: item.type.toLowerCase() as 'image' | 'video' | 'audio',
            purpose: 'video',
            url: item.contentUrl,
            role: item.role as SceneMaterialReferenceInput['role'],
            sourceType: 'group_snapshot',
            sourceId: snapshot.snapshotId,
          });
        }
      }
      await assetApi.setSceneMaterials(sceneId, {
        assetIds: [...selectedAssetIds],
        references: [...selectedRefs.values(), ...groupRefs],
        groupSnapshots,
      });
      await onSaved();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存素材失败');
    } finally {
      setSaving(false);
    }
  };

  const selectedCount = selectedAssetIds.size + selectedRefs.size + selectedGroupIds.size;

  return createPortal(
    <div style={overlay} onPointerDown={(e) => { if (e.target === e.currentTarget && !saving) onClose(); }}>
      <div style={dialog} onPointerDown={(e) => e.stopPropagation()}>
        <header style={header}>
          <div>
            <div style={{ fontSize: 16, fontWeight: 600, color: DS.ink }}>上传素材</div>
            <div style={{ fontSize: 12, color: DS.textCaption, marginTop: 2 }}>
              AI 资产按资产关联，产出素材与分镜图片按参考素材关联
            </div>
          </div>
          <button type="button" onClick={onClose} disabled={saving} style={iconButton}><X size={17} /></button>
        </header>

        <div style={tabs}>
          <TabButton active={tab === 'assets'} onClick={() => setTab('assets')} icon={<Package size={14} />} label={`项目资产 ${assets.length}`} />
          <TabButton active={tab === 'groups'} onClick={() => setTab('groups')} icon={<Boxes size={14} />} label={`资产组 ${groups.length}`} />
          <TabButton active={tab === 'output'} onClick={() => setTab('output')} icon={<FolderOpen size={14} />} label="产出素材" />
          <TabButton active={tab === 'scene'} onClick={() => setTab('scene')} icon={<ImageIcon size={14} />} label={`当前分镜图片 ${sceneImageUrls.length}`} />
        </div>

        <div style={body}>
          {loading ? (
            <div style={empty}><LoaderCircle size={18} className="is-spinning" /> 正在加载素材…</div>
          ) : tab === 'assets' ? (
            <AssetGrid assets={assets} selected={selectedAssetIds} onToggle={toggleAsset} />
          ) : tab === 'groups' ? (
            <GroupGrid
              groups={groups}
              selected={selectedGroupIds}
              onToggle={(id) => setSelectedGroupIds((current) => {
                const next = new Set(current);
                if (next.has(id)) next.delete(id); else next.add(id);
                return next;
              })}
            />
          ) : tab === 'output' ? (
            <>
              <div style={{ display: 'flex', gap: 6, marginBottom: 12 }}>
                <KindButton active={outputKind === 'image'} onClick={() => setOutputKind('image')} icon={<ImageIcon size={13} />} label="图片" />
                <KindButton active={outputKind === 'video'} onClick={() => setOutputKind('video')} icon={<Video size={13} />} label="视频（最多1）" />
              </div>
              <OutputGroups
                groups={outputGroups}
                kind={outputKind}
                selected={selectedRefs}
                currentConversationId={currentConversationId}
                onToggle={toggleRef}
              />
            </>
          ) : (
            <SceneImageGrid urls={sceneImageUrls} sceneId={sceneId} selected={selectedRefs} onToggle={toggleRef} />
          )}

          {orphanRefs.length > 0 && (
            <section style={{ marginTop: 16 }}>
              <div style={groupTitle}>已选素材（来源不可用）</div>
              <div style={grid}>
                {orphanRefs.map((ref) => (
                  <MediaTile
                    key={ref.url}
                    url={ref.url}
                    type={ref.type}
                    selected
                    label="来源不可用"
                    onToggle={() => toggleRef(ref)}
                  />
                ))}
              </div>
            </section>
          )}
        </div>

        <footer style={footer}>
          <span style={{ fontSize: 12, color: DS.textCaption }}>已选 {selectedCount} 项；再次保存会覆盖本镜已选 AI 资产和引用素材</span>
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" onClick={onClose} disabled={saving} style={ghostButton}>取消</button>
            <button type="button" onClick={() => void save()} disabled={saving} style={primaryButton}>
              {saving ? '保存中…' : `保存选择（${selectedCount}）`}
            </button>
          </div>
        </footer>
        {error && <div style={errorBar}>{error}</div>}
      </div>
    </div>,
    document.body,
  );
}

function AssetGrid({ assets, selected, onToggle }: {
  assets: Asset[];
  selected: Set<string>;
  onToggle: (id: string) => void;
}) {
  if (assets.length === 0) return <div style={empty}>当前项目暂无 AI 资产</div>;
  return (
    <div style={grid}>
      {assets.map((asset) => {
        const active = selected.has(asset.id);
        return (
          <button key={asset.id} type="button" onClick={() => onToggle(asset.id)} style={tileButton(active)}>
            {asset.images?.[0]?.url ? (
              <img src={assetUrl(asset.images[0].url)} alt={asset.name} style={media} />
            ) : <div style={placeholder}><Package size={18} /></div>}
            <span style={tileLabel}>{asset.name}</span>
            <span style={tileMeta}>{asset.type} · {asset.images?.length ?? 0} 图</span>
            {active && <span style={checkBadge}><Check size={12} /></span>}
          </button>
        );
      })}
    </div>
  );
}

function GroupGrid({ groups, selected, onToggle }: {
  groups: LibraryAsset[];
  selected: Set<string>;
  onToggle: (id: string) => void;
}) {
  if (groups.length === 0) return <div style={empty}>当前项目暂无资产组；请先到项目库创建。</div>;
  return (
    <div style={grid}>
      {groups.map((group) => {
        const active = selected.has(group.id);
        return (
          <button key={group.id} type="button" onClick={() => onToggle(group.id)} style={tileButton(active)}>
            {group.coverUrl ? (
              <img src={assetUrl(group.coverUrl)} alt={group.name} style={media} />
            ) : <div style={placeholder}><Boxes size={18} /></div>}
            <span style={tileLabel}>{group.name}</span>
            <span style={tileMeta}>视频素材包 · {group.scope === 'GLOBAL' ? '全局' : '项目'}</span>
            {active && <span style={checkBadge}><Check size={12} /></span>}
          </button>
        );
      })}
    </div>
  );
}

function OutputGroups({ groups, kind, selected, currentConversationId, onToggle }: {
  groups: ProjectOutputMaterialGroup[];
  kind: 'image' | 'video';
  selected: Map<string, SceneMaterialReferenceInput>;
  currentConversationId?: string | null;
  onToggle: (ref: SceneMaterialReferenceInput) => void;
}) {
  const visible = groups.filter((group) => group.items.some((item) => item.type === kind && item.urls.length > 0));
  if (visible.length === 0) return <div style={empty}>当前项目暂无{kind === 'image' ? '图片' : '视频'}产出素材</div>;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {visible.map((group) => (
        <section key={group.conversationId}>
          <div style={groupTitle}>
            {group.conversationId === currentConversationId ? '当前对话' : (group.title || '未命名对话')}
          </div>
          <div style={grid}>
            {group.items.filter((item) => item.type === kind).flatMap((item) =>
              item.urls.map((url) => {
                const ref: SceneMaterialReferenceInput = {
                  type: kind,
                  purpose: kind,
                  url,
                  role: 'REFERENCE',
                  sourceType: 'output',
                  sourceId: item.id,
                  sourceConversationId: group.conversationId,
                };
                return (
                  <MediaTile
                    key={`${item.id}-${url}`}
                    url={url}
                    type={kind}
                    selected={selected.has(url)}
                    onToggle={() => onToggle(ref)}
                  />
                );
              })
            )}
          </div>
        </section>
      ))}
    </div>
  );
}

function SceneImageGrid({ urls, sceneId, selected, onToggle }: {
  urls: string[];
  sceneId: string;
  selected: Map<string, SceneMaterialReferenceInput>;
  onToggle: (ref: SceneMaterialReferenceInput) => void;
}) {
  if (urls.length === 0) return <div style={empty}>当前分镜暂无生成图片</div>;
  return (
    <div style={grid}>
      {urls.map((url, index) => {
        const ref: SceneMaterialReferenceInput = {
          type: 'image',
          purpose: 'image',
          url,
          role: 'REFERENCE',
          sourceType: 'scene_image',
          sourceId: sceneId,
        };
        return (
          <MediaTile
            key={url}
            url={url}
            type="image"
            selected={selected.has(url)}
            label={`生成图 ${index + 1}`}
            onToggle={() => onToggle(ref)}
          />
        );
      })}
    </div>
  );
}

function MediaTile({ url, type, selected, label, onToggle }: {
  url: string;
  type: 'image' | 'video' | 'audio';
  selected: boolean;
  label?: string;
  onToggle: () => void;
}) {
  return (
    <button type="button" onClick={onToggle} style={tileButton(selected)}>
      {type === 'video'
        ? <video src={assetUrl(url)} muted style={media} />
        : type === 'audio'
          ? <audio src={assetUrl(url)} controls style={media} />
          : <img src={assetUrl(url)} alt="" style={media} />}
      {label && <span style={tileMeta}>{label}</span>}
      {selected && <span style={checkBadge}><Check size={12} /></span>}
    </button>
  );
}

function TabButton({ active, onClick, icon, label }: {
  active: boolean; onClick: () => void; icon: React.ReactNode; label: string;
}) {
  return <button type="button" onClick={onClick} style={tabButton(active)}>{icon}{label}</button>;
}

function KindButton({ active, onClick, icon, label }: {
  active: boolean; onClick: () => void; icon: React.ReactNode; label: string;
}) {
  return <button type="button" onClick={onClick} style={kindButton(active)}>{icon}{label}</button>;
}

const overlay: React.CSSProperties = {
  position: 'fixed', inset: 0, zIndex: 1450, background: 'rgba(15,17,21,0.46)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16,
};
const dialog: React.CSSProperties = {
  width: '100%', maxWidth: 760, maxHeight: '82vh', background: 'white', borderRadius: 16,
  display: 'flex', flexDirection: 'column', overflow: 'hidden', boxShadow: '0 18px 48px rgba(15,17,21,0.24)',
};
const header: React.CSSProperties = {
  padding: '14px 18px', borderBottom: `1px solid ${DS.border}`,
  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
};
const tabs: React.CSSProperties = {
  display: 'flex', gap: 6, padding: '10px 18px', borderBottom: `1px solid ${DS.border}`,
};
const body: React.CSSProperties = { flex: 1, minHeight: 0, overflowY: 'auto', padding: 16 };
const footer: React.CSSProperties = {
  padding: '12px 18px', borderTop: `1px solid ${DS.border}`,
  display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
};
const grid: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(112px, 1fr))', gap: 10,
};
const media: React.CSSProperties = {
  width: '100%', aspectRatio: '1', objectFit: 'cover', display: 'block', background: DS.hover,
};
const placeholder: React.CSSProperties = {
  width: '100%', aspectRatio: '1', display: 'flex', alignItems: 'center', justifyContent: 'center',
  color: DS.textCaption, background: DS.hover,
};
const empty: React.CSSProperties = {
  minHeight: 160, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
  color: DS.textCaption, fontSize: 13,
};
const groupTitle: React.CSSProperties = {
  fontSize: 12, fontWeight: 600, color: DS.textSecondary, margin: '0 0 8px',
};
const checkBadge: React.CSSProperties = {
  position: 'absolute', top: 6, right: 6, width: 20, height: 20, borderRadius: 10,
  display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'white', background: DS.brand,
};
const tileLabel: React.CSSProperties = {
  display: 'block', padding: '7px 8px 0', fontSize: 12, color: DS.ink,
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};
const tileMeta: React.CSSProperties = {
  display: 'block', padding: '3px 8px 7px', fontSize: 11, color: DS.textCaption,
};
const iconButton: React.CSSProperties = {
  border: 'none', background: 'transparent', color: DS.textCaption, cursor: 'pointer', padding: 4,
};
const primaryButton: React.CSSProperties = {
  padding: '8px 16px', borderRadius: 10, border: 'none', background: DS.brand,
  color: 'white', fontSize: 13, cursor: 'pointer',
};
const ghostButton: React.CSSProperties = {
  padding: '8px 16px', borderRadius: 10, border: `1px solid ${DS.border}`,
  background: 'white', color: DS.textSecondary, fontSize: 13, cursor: 'pointer',
};
const errorBar: React.CSSProperties = {
  padding: '8px 18px', color: '#d92d20', background: 'rgba(217,45,32,0.06)', fontSize: 12,
};
const tileButton = (active: boolean): React.CSSProperties => ({
  position: 'relative', padding: 0, overflow: 'hidden', textAlign: 'left',
  border: `1.5px solid ${active ? DS.brand : DS.border}`, borderRadius: 10,
  background: active ? DS.hover : 'white', cursor: 'pointer',
});
const tabButton = (active: boolean): React.CSSProperties => ({
  display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 12px',
  borderRadius: 9, border: `1px solid ${active ? DS.brand : DS.border}`,
  background: active ? DS.brand : 'white', color: active ? 'white' : DS.textSecondary,
  fontSize: 12, cursor: 'pointer',
});
const kindButton = (active: boolean): React.CSSProperties => ({
  display: 'inline-flex', alignItems: 'center', gap: 5, padding: '5px 10px',
  borderRadius: 8, border: `1px solid ${active ? DS.brand : DS.border}`,
  background: active ? DS.hover : 'white', color: active ? DS.brand : DS.textSecondary,
  fontSize: 12, cursor: 'pointer',
});
