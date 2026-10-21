import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { Check, ChevronLeft, FileText, Folder, Globe2, LoaderCircle, Upload, X } from 'lucide-react';
import {
  libraryApi,
  type AssetScope,
  type LibraryAsset,
  type LibraryFolderNode,
  type LibraryMedia,
  type MediaType,
} from '../../api/library';
import type { SceneMaterialReferenceInput } from '../../api/assets';
import { assetUrl } from '../../config';
import { AssetGroupPickerPanel } from './AssetGroupPickerPanel';

interface Candidate {
  asset: LibraryAsset;
  media: LibraryMedia;
}

interface FolderLocation {
  id: string;
  name: string;
  scope: AssetScope;
  parentId: string | null;
}

type PickableMediaType = 'image' | 'video' | 'audio' | 'document';
type TypeFilter = 'all' | PickableMediaType;
type PickerTab = 'material' | 'groups';

export interface ProjectLibraryAssetGroupsConfig {
  selectedIds: string[];
  onConfirm: (groupIds: string[]) => void | Promise<void>;
}

interface Props {
  open: boolean;
  projectId?: string;
  type: PickableMediaType;
  allowedTypes?: PickableMediaType[];
  purpose?: 'image' | 'video';
  selected?: SceneMaterialReferenceInput[];
  maxSelectable?: number;
  /**
   * 传入后启用顶层「素材 / 资产组」页签，并在统一确认时先保存资产组绑定。
   * 未传入时保持原有纯素材选择器外观与行为（编辑器 PreviewPanel 等场景）。
   */
  assetGroups?: ProjectLibraryAssetGroupsConfig;
  onClose: () => void;
  onConfirm: (references: SceneMaterialReferenceInput[]) => void | Promise<void>;
}

const EMPTY_REFS: SceneMaterialReferenceInput[] = [];
const EMPTY_GROUP_IDS: string[] = [];

export function ProjectLibraryPickerModal({
  open,
  projectId,
  type,
  allowedTypes,
  purpose,
  selected = EMPTY_REFS,
  maxSelectable,
  assetGroups,
  onClose,
  onConfirm,
}: Props) {
  const [activeTab, setActiveTab] = useState<PickerTab>('material');
  const [typeFilter, setTypeFilter] = useState<TypeFilter>('all');
  const [outputOnly, setOutputOnly] = useState(false);
  const [projectFolders, setProjectFolders] = useState<LibraryFolderNode[]>([]);
  const [globalFolders, setGlobalFolders] = useState<LibraryFolderNode[]>([]);
  const [path, setPath] = useState<FolderLocation[]>([]);
  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [selection, setSelection] = useState<Map<string, SceneMaterialReferenceInput>>(new Map());
  const [groupSelection, setGroupSelection] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const selectedKey = selected.map((item) => item.mediaId ?? item.url).join('\u0000');
  const groupSelectedIds = assetGroups?.selectedIds ?? EMPTY_GROUP_IDS;
  const assetGroupsKey = groupSelectedIds.join('\u0000');
  const currentFolder = path.at(-1) ?? null;
  const effectiveTypes = allowedTypes?.length ? allowedTypes : [type];
  const activeType = typeFilter !== 'all' && effectiveTypes.includes(typeFilter)
    ? typeFilter
    : null;

  useEffect(() => {
    if (!open) return;
    setActiveTab('material');
  }, [open]);

  useEffect(() => {
    if (!open) return;
    setTypeFilter('all');
    setOutputOnly(false);
    setPath([]);
    setSelection(new Map(
      selected
        .filter((item) => item.mediaId)
        .map((item) => [item.mediaId!, item]),
    ));
    setError('');
  }, [open, selectedKey]);

  // 资产组选中态以当前绑定初始化，并在外部绑定变化（稳定 key）时重新同步，避免普通重渲染重置用户选择。
  useEffect(() => {
    if (!open) return;
    setGroupSelection(new Set(groupSelectedIds));
  }, [open, assetGroupsKey]);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    Promise.all([
      projectId
        ? libraryApi.getFolderTree({ scope: 'PROJECT', projectId })
        : Promise.resolve(null),
      libraryApi.getFolderTree({ scope: 'GLOBAL', projectId: undefined }),
    ]).then(([projectTree, globalTree]) => {
      if (cancelled) return;
      setProjectFolders(projectTree?.data.data?.folders ?? []);
      setGlobalFolders(globalTree.data.data?.folders ?? []);
    }).catch((err: unknown) => {
      if (!cancelled) setError(err instanceof Error ? err.message : '加载文件夹失败');
    });
    return () => { cancelled = true; };
  }, [open, projectId]);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    setError('');
    const scope = currentFolder?.scope ?? (projectId ? 'PROJECT' : 'GLOBAL');
    const folderId = currentFolder?.id ?? 'UNFILED';
    const projectForQuery = scope === 'PROJECT' ? projectId : undefined;
    if (scope === 'PROJECT' && !projectForQuery) {
      setCandidates([]);
      setLoading(false);
      return;
    }

    const requests: Array<Promise<{ data: { data: { records: LibraryAsset[] } } }>> = [
      libraryApi.listAssets({
        scope,
        projectId: projectForQuery,
        folderId,
        type: activeType ? activeType.toUpperCase() as MediaType : undefined,
        origin: outputOnly ? 'GENERATED' as const : undefined,
        page: 1,
        size: 80,
      }) as Promise<{ data: { data: { records: LibraryAsset[] } } }>,
    ];
    if (!currentFolder && projectId) {
      requests.push(libraryApi.listAssets({
        scope: 'GLOBAL',
        folderId: 'UNFILED',
        type: activeType ? activeType.toUpperCase() as MediaType : undefined,
        page: 1,
        size: 80,
      }) as Promise<{ data: { data: { records: LibraryAsset[] } } }>);
    }

    Promise.all(requests).then(async (responses) => {
      const assets = responses.flatMap((response) => response.data.data.records ?? []);
      const details = await Promise.all(assets.map((asset) => libraryApi.getAsset(asset.id)));
      if (cancelled) return;
      const next: Candidate[] = [];
      for (const detail of details) {
        for (const media of detail.data.data.media) {
          if (!activeType || media.type === activeType.toUpperCase()) {
            next.push({ asset: detail.data.data.asset, media });
          }
        }
      }
      setCandidates(next);
    }).catch((err: unknown) => {
      if (!cancelled) setError(err instanceof Error ? err.message : '加载项目库失败');
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => { cancelled = true; };
  }, [open, projectId, currentFolder?.id, currentFolder?.scope, activeType, outputOnly]);

  const childFolders = useMemo(() => {
    if (!currentFolder) {
      return [
        ...projectFolders.filter((folder) => folder.parentId === null),
        ...globalFolders.filter((folder) => folder.parentId === null),
      ];
    }
    if (currentFolder.scope === 'GLOBAL') {
      return globalFolders.filter((folder) => folder.parentId === currentFolder.id);
    }
    return projectFolders.filter((folder) => folder.parentId === currentFolder.id);
  }, [currentFolder, globalFolders, projectFolders]);

  const visible = useMemo(() => candidates.filter(({ asset }) => asset.status === 'ACTIVE'), [candidates]);

  if (!open) return null;

  const selectionKey = (candidate: Candidate) => candidate.media.id;

  const toggle = (candidate: Candidate) => {
    setSelection((current) => {
      const next = new Map(current);
      const key = selectionKey(candidate);
      if (next.has(key)) {
        next.delete(key);
      } else if (maxSelectable == null || next.size < maxSelectable) {
        next.set(key, toReference(candidate, purpose));
      } else {
        setError(`最多选择 ${maxSelectable} 项`);
      }
      return next;
    });
  };

  const toggleGroup = (groupId: string) => {
    setGroupSelection((current) => {
      const next = new Set(current);
      if (next.has(groupId)) next.delete(groupId); else next.add(groupId);
      return next;
    });
  };

  const openFolder = (folder: LibraryFolderNode) => {
    setPath((current) => [...current, {
      id: folder.id,
      name: folder.name,
      scope: folder.scope,
      parentId: folder.parentId,
    }]);
  };

  const upload = async (files: FileList | null) => {
    if (!files?.length) return;
    setUploading(true);
    setError('');
    const targetScope = currentFolder?.scope ?? 'PROJECT';
    const targetProjectId = targetScope === 'PROJECT' ? projectId : undefined;
    if (targetScope === 'PROJECT' && !targetProjectId) {
      setError('请先选择项目');
      setUploading(false);
      return;
    }
    try {
      const uploaded: Candidate[] = [];
      const uploadedAssetIds: string[] = [];
      for (const file of Array.from(files)) {
        const response = await libraryApi.uploadDirect(file, {
          projectId: targetProjectId,
          scope: targetScope,
        });
        const assetId = response.data.data?.id;
        if (!assetId) continue;
        const detail = await libraryApi.getAsset(assetId);
        uploadedAssetIds.push(assetId);
        for (const media of detail.data.data.media) {
          if (!activeType || media.type === activeType.toUpperCase()) {
            uploaded.push({ asset: detail.data.data.asset, media });
          }
        }
      }
      if (currentFolder && uploadedAssetIds.length) {
        await libraryApi.moveAssetsToFolder(currentFolder.id, {
          scope: targetScope,
          ownerProjectId: targetProjectId,
          assetIds: uploadedAssetIds,
        });
      }
      setCandidates((current) => [...uploaded, ...current]);
      setSelection((current) => {
        const next = new Map(current);
        uploaded.forEach((candidate) => next.set(candidate.media.id, toReference(candidate, purpose)));
        return next;
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : '上传项目库素材失败');
    } finally {
      setUploading(false);
    }
  };

  const confirm = async () => {
    setSaving(true);
    setError('');
    try {
      // 先保存资产组绑定，再提交本轮素材；两步语义互相独立，故不共享事务。
      // 若资产组已成功而素材失败，重试时差量逻辑保证不会重复绑定。
      if (assetGroups) {
        await assetGroups.onConfirm([...groupSelection]);
      }
      await onConfirm([...selection.values()]);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存项目库素材失败');
    } finally {
      setSaving(false);
    }
  };

  const showGroups = !!assetGroups && activeTab === 'groups';

  return createPortal(
    <div style={overlay} onPointerDown={(event) => {
      if (event.target === event.currentTarget && !saving && !uploading) onClose();
    }}>
      <div role="dialog" aria-modal="true" style={dialog} onPointerDown={(event) => event.stopPropagation()}>
        <header style={header}>
          <div>
            <strong style={{ fontSize: 16 }}>项目库素材</strong>
            <div style={{ marginTop: 3, fontSize: 12, color: 'var(--color-muted)' }}>
              {assetGroups
                ? '选择素材加入本次对话，或关联、解绑资产组'
                : '文件夹优先展示，双击文件夹进入，再选择其中的素材'}
            </div>
          </div>
          <button type="button" onClick={onClose} disabled={saving || uploading} style={iconButton} aria-label="关闭">
            <X size={18} />
          </button>
        </header>

        {assetGroups && (
          <div style={tabStrip}>
            <div role="tablist" aria-label="项目库来源" style={tabButtons}>
              <button
                type="button"
                role="tab"
                id="project-library-tab-material"
                aria-selected={activeTab === 'material'}
                aria-controls="project-library-tabpanel"
                onClick={() => setActiveTab('material')}
                style={tabButton(activeTab === 'material')}
              >
                素材
              </button>
              <button
                type="button"
                role="tab"
                id="project-library-tab-groups"
                aria-selected={activeTab === 'groups'}
                aria-controls="project-library-tabpanel"
                onClick={() => setActiveTab('groups')}
                style={tabButton(activeTab === 'groups')}
              >
                资产组
              </button>
            </div>
            {projectId && activeTab === 'material' && (
              <FilterButton
                active={outputOnly}
                label="项目产出"
                onClick={() => setOutputOnly((current) => !current)}
              />
            )}
          </div>
        )}

        <div
          id="project-library-tabpanel"
          role={assetGroups ? 'tabpanel' : undefined}
          aria-labelledby={assetGroups
            ? (activeTab === 'material' ? 'project-library-tab-material' : 'project-library-tab-groups')
            : undefined}
          style={tabPanel}
        >
          {showGroups ? (
            <AssetGroupPickerPanel
              projectId={projectId}
              selectedIds={[...groupSelection]}
              onToggle={toggleGroup}
            />
          ) : (
            <>
              <div style={toolbar}>
                <FilterButton active={typeFilter === 'all'} label="全部" onClick={() => setTypeFilter('all')} />
                {effectiveTypes.includes('image') && (
                  <FilterButton active={typeFilter === 'image'} label="图片" onClick={() => setTypeFilter('image')} />
                )}
                {effectiveTypes.includes('video') && (
                  <FilterButton active={typeFilter === 'video'} label="视频" onClick={() => setTypeFilter('video')} />
                )}
                {effectiveTypes.includes('audio') && (
                  <FilterButton active={typeFilter === 'audio'} label="音频" onClick={() => setTypeFilter('audio')} />
                )}
                {effectiveTypes.includes('document') && (
                  <FilterButton active={typeFilter === 'document'} label="文档" onClick={() => setTypeFilter('document')} />
                )}
                {projectId && !assetGroups && (
                  <FilterButton
                    active={outputOnly}
                    label="项目产出"
                    onClick={() => setOutputOnly((current) => !current)}
                  />
                )}
                <label style={{ ...uploadButton, marginLeft: 'auto', opacity: uploading ? 0.65 : 1 }}>
                  {uploading ? <LoaderCircle size={14} className="is-spinning" /> : <Upload size={14} />}
                  {uploading ? '上传中…' : '上传到项目库'}
                  <input
                    type="file"
                    hidden
                    multiple
                    accept={ACCEPT[activeType ?? 'all']}
                    disabled={uploading}
                    onChange={(event) => {
                      void upload(event.target.files);
                      event.target.value = '';
                    }}
                  />
                </label>
              </div>

              <div style={breadcrumbs}>
                <button type="button" onClick={() => setPath([])} disabled={path.length === 0}>
                  <Folder size={14} /> 项目库
                </button>
                {path.map((folder, index) => (
                  <span key={`${folder.scope}:${folder.id}`}>
                    <ChevronLeft size={12} />
                    <button type="button" onClick={() => setPath((current) => current.slice(0, index + 1))}>
                      {folder.scope === 'GLOBAL' && <Globe2 size={11} />}
                      {folder.name}
                    </button>
                  </span>
                ))}
              </div>

              <div style={body}>
                {loading ? (
                  <div style={empty}><LoaderCircle className="is-spinning" size={18} /> 正在加载素材…</div>
                ) : (
                  <>
                    {childFolders.length > 0 && (
                      <section>
                        <div style={sectionTitle}>文件夹 · 双击打开</div>
                        <div style={folderGrid}>
                          {childFolders.map((folder) => (
                            <button
                              key={`${folder.scope}:${folder.id}`}
                              type="button"
                              onDoubleClick={() => openFolder(folder)}
                              style={folderCard}
                              title="双击打开"
                            >
                              <Folder size={30} strokeWidth={1.5} />
                              <strong style={ellipsis}>{folder.name}</strong>
                              <small>{folder.descendantAssetCount} 项</small>
                            </button>
                          ))}
                        </div>
                      </section>
                    )}

                    <section style={{ marginTop: childFolders.length ? 18 : 0 }}>
                      <div style={sectionTitle}>素材 · {visible.length} 项</div>
                      {visible.length === 0 ? (
                        <div style={empty}>当前文件夹暂无{activeType ? TYPE_LABEL[activeType] : '素材'}</div>
                      ) : (
                        <div style={grid}>
                          {visible.map((candidate) => {
                            const active = selection.has(candidate.media.id);
                            const preview = candidate.media.type === 'VIDEO' ? null : candidate.media.contentUrl || candidate.media.url;
                            return (
                              <button
                                key={candidate.media.id}
                                type="button"
                                onClick={() => toggle(candidate)}
                                style={card(active)}
                              >
                                <div style={mediaBox}>
                                  {candidate.media.type === 'VIDEO'
                                    ? <video src={assetUrl(candidate.media.contentUrl || candidate.media.url)} muted style={mediaStyle} />
                                    : candidate.media.type === 'AUDIO'
                                      ? <audio src={assetUrl(candidate.media.contentUrl || candidate.media.url)} controls style={mediaStyle} />
                                      : candidate.media.type === 'DOCUMENT' || candidate.media.type === 'OTHER'
                                        ? (
                                          <span style={documentBox}>
                                            <FileText size={26} strokeWidth={1.6} />
                                            <span style={ellipsis}>{candidate.media.mimeType || '文件'}</span>
                                          </span>
                                        )
                                      : <img src={assetUrl(preview ?? '')} alt="" style={mediaStyle} />}
                                </div>
                                <div style={{ padding: '7px 8px', minWidth: 0 }}>
                                  <strong style={ellipsis}>{candidate.media.fileName || candidate.asset.name}</strong>
                                  <small style={assetMeta}>
                                    {candidate.asset.scope === 'GLOBAL' && <Globe2 size={10} />}
                                    {candidate.asset.name}
                                  </small>
                                </div>
                                {active && <span style={check}><Check size={12} /></span>}
                              </button>
                            );
                          })}
                        </div>
                      )}
                    </section>
                  </>
                )}
              </div>
            </>
          )}

          {error && <div style={errorBar}>{error}</div>}
        </div>

        <footer style={footer}>
          <span style={{ fontSize: 12, color: 'var(--color-muted)' }}>
            {assetGroups
              ? `已选 ${selection.size} 项素材 · ${groupSelection.size} 个资产组`
              : `已选 ${selection.size} 项`}
          </span>
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" onClick={onClose} disabled={saving || uploading} style={ghostButton}>取消</button>
            <button type="button" onClick={() => void confirm()} disabled={saving || uploading} style={primaryButton}>
              {saving ? '保存中…' : '确认选择'}
            </button>
          </div>
        </footer>
      </div>
    </div>,
    document.body,
  );
}

function toReference(candidate: Candidate, purpose?: 'image' | 'video'): SceneMaterialReferenceInput {
  const mediaType = candidate.media.type.toLowerCase() as 'image' | 'video' | 'audio' | 'document' | 'other';
  const referenceType = mediaType === 'other' ? 'document' : mediaType;
  return {
    type: referenceType,
    purpose: purpose ?? (mediaType === 'video' || mediaType === 'audio' ? 'video' : 'image'),
    url: candidate.media.contentUrl || candidate.media.url,
    role: 'REFERENCE',
    sourceType: 'library_asset',
    sourceId: candidate.asset.id,
    mediaId: candidate.media.id,
    fileName: candidate.media.fileName || candidate.asset.name,
    mimeType: candidate.media.mimeType,
    sizeBytes: candidate.media.sizeBytes,
  };
}

function FilterButton({ active, label, onClick }: { active: boolean; label: string; onClick: () => void }) {
  return <button type="button" onClick={onClick} style={scopeButton(active)}>{label}</button>;
}

const TYPE_LABEL = { image: '图片', video: '视频', audio: '音频', document: '文档' } as const;
const ACCEPT = {
  all: 'image/*,video/mp4,video/quicktime,audio/wav,audio/mpeg,audio/mp4,'
    + '.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.md,.markdown,.txt,.csv,.json,.yaml,.yml,.xml,.html',
  image: 'image/*',
  video: 'video/mp4,video/quicktime',
  audio: 'audio/wav,audio/mpeg,audio/mp4',
  document: '.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.md,.markdown,.txt,.csv,.json,.yaml,.yml,.xml,.html',
} as const;

const overlay: React.CSSProperties = {
  position: 'fixed', inset: 0, zIndex: 1500, padding: 16,
  display: 'flex', alignItems: 'center', justifyContent: 'center',
  background: 'rgba(15,17,21,0.46)',
};
const dialog: React.CSSProperties = {
  width: '100%', maxWidth: 820, maxHeight: '86dvh', display: 'flex', flexDirection: 'column',
  overflow: 'hidden', borderRadius: 16, background: 'var(--color-panel)',
  boxShadow: '0 18px 48px rgba(15,17,21,0.24)',
};
const header: React.CSSProperties = {
  padding: '14px 18px', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
  borderBottom: '1px solid var(--color-hairline)',
};
const tabStrip: React.CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
  gap: 8, padding: '10px 18px 0',
};
const tabButtons: React.CSSProperties = {
  display: 'flex', gap: 4,
};
const tabPanel: React.CSSProperties = {
  flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column',
};
const toolbar: React.CSSProperties = {
  padding: '10px 18px', display: 'flex', alignItems: 'center', gap: 8,
  borderBottom: '1px solid var(--color-hairline)',
};
const breadcrumbs: React.CSSProperties = {
  minHeight: 38, padding: '7px 18px', display: 'flex', alignItems: 'center', gap: 4,
  borderBottom: '1px solid var(--color-hairline)', color: 'var(--color-muted)', fontSize: 12,
};
const body: React.CSSProperties = { flex: 1, minHeight: 0, overflowY: 'auto', padding: 16 };
const sectionTitle: React.CSSProperties = {
  margin: '0 0 9px', color: 'var(--color-muted)', fontSize: 12, fontWeight: 600,
};
const folderGrid: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(132px, 1fr))', gap: 10,
};
const grid: React.CSSProperties = { display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(132px, 1fr))', gap: 10 };
const folderCard: React.CSSProperties = {
  minHeight: 104, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
  gap: 6, padding: 10, border: '1px solid var(--color-hairline)', borderRadius: 10,
  background: 'var(--color-surface-card)', color: 'var(--color-body)', cursor: 'pointer',
};
const mediaBox: React.CSSProperties = { width: '100%', aspectRatio: '1', overflow: 'hidden', background: 'var(--color-surface-soft)' };
const mediaStyle: React.CSSProperties = { width: '100%', height: '100%', objectFit: 'cover', display: 'block' };
const documentBox: React.CSSProperties = {
  width: '100%', height: '100%', display: 'flex', flexDirection: 'column',
  alignItems: 'center', justifyContent: 'center', gap: 6, padding: 8,
  color: 'var(--color-muted)',
};
const empty: React.CSSProperties = {
  minHeight: 120, display: 'flex', alignItems: 'center', justifyContent: 'center',
  gap: 8, color: 'var(--color-muted)', fontSize: 13,
};
const ellipsis: React.CSSProperties = {
  display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: 12,
};
const assetMeta: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 4, marginTop: 3,
  color: 'var(--color-muted)', fontSize: 11,
};
const check: React.CSSProperties = {
  position: 'absolute', top: 6, right: 6, width: 20, height: 20, borderRadius: 10,
  display: 'grid', placeItems: 'center', color: 'var(--color-on-primary)', background: 'var(--color-primary)',
};
const errorBar: React.CSSProperties = {
  padding: '8px 18px', borderTop: '1px solid var(--color-hairline)',
  color: 'var(--color-error)', fontSize: 12,
};
const footer: React.CSSProperties = {
  padding: '12px 18px', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
  borderTop: '1px solid var(--color-hairline)',
};
const iconButton: React.CSSProperties = {
  border: 'none', background: 'transparent', color: 'var(--color-muted)', cursor: 'pointer', padding: 4,
};
const uploadButton: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 5, padding: '6px 10px', borderRadius: 8,
  border: '1px solid var(--color-primary)', color: 'var(--color-primary)', background: 'var(--color-panel)',
  fontSize: 12, cursor: 'pointer',
};
const ghostButton: React.CSSProperties = {
  padding: '8px 16px', borderRadius: 10, border: '1px solid var(--color-hairline)',
  background: 'var(--color-panel)', color: 'var(--color-body)', fontSize: 13, cursor: 'pointer',
};
const primaryButton: React.CSSProperties = {
  padding: '8px 16px', borderRadius: 10, border: 'none',
  background: 'var(--color-primary)', color: 'var(--color-on-primary)', fontSize: 13, cursor: 'pointer',
};
const scopeButton = (active: boolean): React.CSSProperties => ({
  padding: '6px 12px', borderRadius: 8, border: `1px solid ${active ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
  background: active ? 'var(--color-primary)' : 'var(--color-panel)', color: active ? 'var(--color-on-primary)' : 'var(--color-muted)',
  fontSize: 12, cursor: 'pointer',
});
// 顶层「素材 / 资产组」页签：与筛选 pill 区分，使用下划线式 tablist 语义。
const tabButton = (active: boolean): React.CSSProperties => ({
  padding: '7px 2px', marginRight: 18, border: 'none', borderBottom: `2px solid ${active ? 'var(--color-primary)' : 'transparent'}`,
  background: 'transparent', color: active ? 'var(--color-primary)' : 'var(--color-muted)',
  fontSize: 13, fontWeight: active ? 600 : 500, cursor: 'pointer',
});
const card = (active: boolean): React.CSSProperties => ({
  position: 'relative', padding: 0, overflow: 'hidden', border: `1.5px solid ${active ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
  borderRadius: 10, background: 'var(--color-panel)', cursor: 'pointer', textAlign: 'left',
});
