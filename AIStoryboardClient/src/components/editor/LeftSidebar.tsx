import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useProjectStore } from '../../stores/projectStore';
import { resolveVideoPreset } from '../common/VideoPresetSelector';
import { REFERENCE_LIMITS, type UnderstandingModelParams } from '../../config';
import { useAgentStore } from '../../stores/agentStore';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '../ui/select';
import type { SceneMaterialReferenceInput } from '../../api/assets';
import { libraryApi, type LibraryAsset } from '../../api/library';
import { assetUrl } from '../../config';
import { ProjectLibraryPickerModal } from '../library/ProjectLibraryPickerModal';
import { projectLibraryReferencesToDataUrls } from '../../lib/projectLibraryReferences';
import { FolderOpen, LoaderCircle, Puzzle, Plus } from 'lucide-react';

const creationTypes = [
  { value: 'movie', label: '电影片段' },
  { value: 'commercial', label: '广告视频' },
  { value: 'music_video', label: '音乐视频' },
  { value: 'animation', label: '动画短片' },
  { value: 'trailer', label: '预告片' },
  { value: 'custom', label: '自定义' },
];

const collapsedWidth = 40;

const labelStyle: React.CSSProperties = {
  display: 'block',
  fontSize: 11.5,
  fontWeight: 500,
  color: 'var(--color-muted)',
  marginBottom: 6,
  lineHeight: 1.35,
};

const sharedInputStyle: React.CSSProperties = {
  width: '100%',
  height: 36,
  padding: '0 10px',
  borderRadius: 'var(--rounded-md)',
  border: '1px solid var(--color-hairline)',
  fontSize: 13,
  background: 'var(--color-panel)',
  color: 'var(--color-ink)',
  outline: 'none',
};

type ProjectLibraryPreview = {
  asset: LibraryAsset;
  previewUrl: string | null;
};

export function LeftSidebar() {
  const navigate = useNavigate();
  const {
    currentProject,
    scenes,
    isLoading,
    generateScript,
    createProject,
    understandingModel,
    understandingModelOptions,
    setUnderstandingModel,
    videoPreset,
  } = useProjectStore();

  // 智能体已生成分镜时，手动剧本输入与生成按钮互斥禁用
  const agentGeneratedScenes = useAgentStore((s) => s.agentGeneratedScenes);

  const [collapsed, setCollapsed] = useState(false);
  const [creationType, setCreationType] = useState('movie');
  const [customTypeDesc, setCustomTypeDesc] = useState('');
  const [scriptText, setScriptText] = useState('');
  // 参考图（base64 data URI 列表；作为理解模型的看图输入）
  const [refImages, setRefImages] = useState<string[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [confirmOverwrite, setConfirmOverwrite] = useState<null | 'pending'>(null);
  // 侧栏内置「AI资产库」区块（独立于剧本输入，紧凑列表 + 打开完整面板）
  const [libraryAssets, setLibraryAssets] = useState<ProjectLibraryPreview[]>([]);
  const [libraryAssetsLoading, setLibraryAssetsLoading] = useState(false);
  const [libraryPickerOpen, setLibraryPickerOpen] = useState(false);
  const [preparingLibraryRefs, setPreparingLibraryRefs] = useState(false);
  const [libraryRefError, setLibraryRefError] = useState<string | null>(null);

  const loadProjectLibrary = useCallback(async () => {
    if (!currentProject?.id) {
      setLibraryAssets([]);
      setLibraryAssetsLoading(false);
      return;
    }
    setLibraryAssetsLoading(true);
    try {
      const res = await libraryApi.listAssets({
        scope: 'PROJECT',
        projectId: currentProject.id,
        page: 1,
        size: 8,
      });
      const records = (res.data.data?.records ?? [])
        .filter((asset) => asset.status === 'ACTIVE' && asset.type !== 'GROUP');
      const previews = await Promise.all(records.map(async (asset): Promise<ProjectLibraryPreview> => {
        try {
          const detail = await libraryApi.getAsset(asset.id);
          const media = detail.data.data?.media;
          const preview = media?.find((item) => item.primary && item.type === 'IMAGE')
            ?? media?.find((item) => item.type === 'IMAGE');
          return { asset, previewUrl: preview?.contentUrl ?? asset.coverUrl };
        } catch {
          return { asset, previewUrl: asset.coverUrl };
        }
      }));
      setLibraryAssets(previews);
    } catch {
      setLibraryAssets([]);
    } finally {
      setLibraryAssetsLoading(false);
    }
  }, [currentProject?.id]);

  useEffect(() => {
    void loadProjectLibrary();
  }, [loadProjectLibrary]);

  // 上传约束：优先当前理解模型 params（网关 model_params refImages/maxImageSizeMB），未配置回退静态兜底
  const uParams = understandingModelOptions.find((m) => m.value === understandingModel)?.params as UnderstandingModelParams | undefined;
  const maxCount = uParams?.refImages?.max ?? REFERENCE_LIMITS.image.maxCount;
  const maxSizeMB = uParams?.maxImageSizeMB ?? REFERENCE_LIMITS.image.maxSizeMB;
  const remainingRefSlots = Math.max(0, maxCount - refImages.length);

  const handleRefImages = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    const available = maxCount - refImages.length;
    if (files.length > available) {
      alert(`最多上传 ${maxCount} 张参考图（当前已 ${refImages.length} 张）`);
    }
    const accepted = files.slice(0, Math.max(0, available));
    if (accepted.some((f) => f.size > maxSizeMB * 1024 * 1024)) {
      alert(`单张图片不能超过 ${maxSizeMB}MB`);
    }
    const valid = accepted.filter((f) => f.size <= maxSizeMB * 1024 * 1024);
    Promise.all(
      valid.map((f) => new Promise<string>((resolve) => {
        const r = new FileReader();
        r.onloadend = () => resolve(r.result as string);
        r.readAsDataURL(f);
      }))
    ).then((uris) => setRefImages((prev) => [...prev, ...uris]));
    // 允许重复选择同一文件：重置 input value
    e.target.value = '';
  };

  const removeRefImage = (idx: number) => {
    setRefImages((prev) => prev.filter((_, i) => i !== idx));
  };

  const handleProjectLibraryReferences = async (references: SceneMaterialReferenceInput[]) => {
    setPreparingLibraryRefs(true);
    setLibraryRefError(null);
    try {
      const uris = await projectLibraryReferencesToDataUrls(references);
      setRefImages((prev) => [...prev, ...uris].slice(0, maxCount));
      await loadProjectLibrary();
    } catch (error) {
      const message = error instanceof Error ? error.message : '读取项目库素材失败';
      setLibraryRefError(message);
      throw error;
    } finally {
      setPreparingLibraryRefs(false);
    }
  };

  const handleGenerate = async () => {
    if (!scriptText.trim()) return;

    // 项目已有分镜 → 弹 覆盖更新/追加/不生成 确认（避免无提示直接追加或误覆盖）
    if ((scenes?.length ?? 0) > 0) {
      setConfirmOverwrite('pending');
      return;
    }
    await doGenerate();
  };

  const doGenerate = async (mode?: string) => {
    let projectId = currentProject?.id;

    // If no current project, create one first
    if (!projectId) {
      try {
        const preset = resolveVideoPreset(videoPreset);
        const p = await createProject('未命名项目', creationType, preset.aspectRatio);
        projectId = p.id;
      } catch {
        alert('创建项目失败，请重试');
        return;
      }
    }

    const preset = resolveVideoPreset(videoPreset);
    await generateScript(
      projectId, scriptText, creationType, preset.aspectRatio, undefined,
      refImages.length ? understandingModel : undefined,
      refImages.length ? refImages : undefined,
      undefined, mode
    );
  };

  // ── Collapsed state: vertical tab ──
  if (collapsed) {
    return (
      <button
        type="button"
        aria-label="展开剧本输入"
        onClick={() => setCollapsed(false)}
        style={{
          width: collapsedWidth,
          minWidth: collapsedWidth,
          borderTop: 0,
          borderBottom: 0,
          borderLeft: 0,
          borderRight: '1px solid var(--color-hairline)',
          background: 'var(--color-canvas)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          cursor: 'pointer',
          userSelect: 'none',
        }}
      >
        <span
          style={{
            writingMode: 'vertical-rl',
            fontSize: 11.5,
            fontWeight: 500,
            color: 'var(--color-muted)',
            letterSpacing: '0.16em',
          }}
        >
          剧本 <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
        </span>
      </button>
    );
  }

  return (
    <div className="editor-sidebar">
      {/* Section title with collapse button */}
      <div className="editor-sidebar__head">
        <div>
          <h2 className="editor-sidebar__title">剧本输入</h2>
          <p className="editor-sidebar__subtitle">写一段描述，生成可逐镜编辑的分镜</p>
        </div>
        <button
          type="button"
          className="icon-btn"
          aria-label="收起剧本输入"
          onClick={() => setCollapsed(true)}
          style={{ width: 28, height: 28, marginTop: -2 }}
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
        </button>
      </div>

      {/* ═══════════ 剧本输入区域 ═══════════ */}

      {/* Creation type */}
      <div className="editor-sidebar__section">
        <label style={labelStyle}>创作类型</label>
        <Select value={creationType} onValueChange={(v) => v && setCreationType(v)}>
          <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue>{(v) => creationTypes.find((t) => t.value === v)?.label ?? v}</SelectValue></SelectTrigger>
          <SelectContent>
            {creationTypes.map((t) => <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>

      {/* Custom type description (only when custom selected) */}
      {creationType === 'custom' && (
        <div>
          <label style={labelStyle}>自定义描述</label>
          <input
            type="text"
            value={customTypeDesc}
            onChange={(e) => setCustomTypeDesc(e.target.value)}
            placeholder="描述你的创作类型..."
            style={sharedInputStyle}
          />
        </div>
      )}

      {/* Script textarea */}
      <div style={{ display: 'flex', flexDirection: 'column' }}>
        <label style={labelStyle}>剧本 / 描述</label>
        <textarea
          className="editor-textarea"
          value={scriptText}
          onChange={(e) => setScriptText(e.target.value)}
          disabled={agentGeneratedScenes}
          placeholder={agentGeneratedScenes ? '分镜已由智能体生成，如需手动生成请刷新页面' : '输入剧本或创作描述，AI 将自动拆解为分镜…'}
        />
      </div>

      {/* Reference image multi-upload（理解模型看图输入） */}
      <div className="editor-sidebar__section">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <button
            type="button"
            className="editor-upload"
            onClick={() => fileInputRef.current?.click()}
          >
            <svg width="15" height="15" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><circle cx="10" cy="10" r="8.5"/><line x1="10" y1="6" x2="10" y2="14"/><line x1="6" y1="10" x2="14" y2="10"/></svg>
            <span style={{ display: 'grid', gap: 1 }}>
              <span style={{ color: 'var(--color-body)', fontSize: 12, fontWeight: 500 }}>上传参考图</span>
              <span style={{ color: 'var(--color-muted-soft)', fontSize: 10.5 }}>可选 · 最多 {maxCount} 张 · 单张 ≤{maxSizeMB}MB</span>
            </span>
          </button>
          <button
            type="button"
            className="btn btn--outline btn--sm"
            aria-label="从项目库选择参考图"
            disabled={remainingRefSlots === 0 || preparingLibraryRefs}
            onClick={() => setLibraryPickerOpen(true)}
            style={{ width: '100%' }}
          >
            {preparingLibraryRefs ? <LoaderCircle size={13} className="is-spinning" /> : <FolderOpen size={13} />}
            从项目库选择
          </button>
        </div>
        <input ref={fileInputRef} type="file" accept="image/*" multiple hidden onChange={handleRefImages} />
        {libraryRefError && (
          <div style={{ marginTop: 6, fontSize: 11, color: 'var(--color-error)' }}>{libraryRefError}</div>
        )}
        {refImages.length > 0 && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
            {refImages.map((uri, i) => (
              <div key={i} style={{ position: 'relative' }}>
                <img
                  src={uri}
                  alt={`参考图 ${i + 1}`}
                  style={{
                    width: 54,
                    height: 54,
                    objectFit: 'contain',
                    borderRadius: 'var(--rounded-sm)',
                    background: 'var(--color-surface-soft)',
                    border: '1px solid var(--color-hairline)',
                  }}
                />
                <button
                  type="button"
                  aria-label={`移除参考图 ${i + 1}`}
                  onClick={() => removeRefImage(i)}
                  style={{
                    position: 'absolute', top: -5, right: -5,
                    width: 16, height: 16, padding: 0,
                    background: 'var(--color-error)', color: 'var(--color-on-danger)',
                    border: '2px solid var(--color-canvas)',
                    borderRadius: '50%', fontSize: 10, lineHeight: 1,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    cursor: 'pointer',
                  }}
                >
                  ×
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 理解模型：驱动图片/视频理解的看图模型（剧本输入在这里选，分镜生图分别可选） */}
      <div style={{ flexShrink: 0 }}>
        <label style={labelStyle}>理解模型</label>
        <Select value={understandingModel} onValueChange={(v) => v && setUnderstandingModel(v)}>
          <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue /></SelectTrigger>
          <SelectContent>
            {understandingModelOptions.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>

      {/* Generate button */}
      <button
        className="editor-generate"
        onClick={handleGenerate}
        disabled={isLoading || !scriptText.trim() || agentGeneratedScenes}
      >
        {agentGeneratedScenes ? '已由智能体生成' : isLoading ? '生成中…' : '生成分镜脚本'}
      </button>

      {/* 项目库（独立于剧本输入，侧栏内嵌） */}
      <div className="editor-library">
        <div className="editor-library__head">
          <span className="editor-library__title">
            <Puzzle size={13} strokeWidth={1.8} /> 项目素材
          </span>
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            onClick={() => currentProject && navigate(`/library?projectId=${encodeURIComponent(currentProject.id)}`)}
            disabled={!currentProject}
            style={{ height: 26, padding: '0 6px' }}
          >
            <Plus size={13} strokeWidth={1.8} /> 管理
          </button>
        </div>
        {libraryAssetsLoading ? (
          <div style={{ fontSize: 11.5, color: 'var(--color-muted)' }}>加载中…</div>
        ) : libraryAssets.length === 0 ? (
          <div style={{ fontSize: 11.5, color: 'var(--color-muted-soft)', lineHeight: 1.6 }}>
            项目库暂无素材。上传后可在这里一键引用为参考图。
          </div>
        ) : (
          <div className="editor-library__grid" data-testid="project-library-list">
            {libraryAssets.map(({ asset, previewUrl }) => (
              <div key={asset.id} className="editor-library__card">
                {previewUrl
                  ? <img src={assetUrl(previewUrl)} alt={asset.name} className="editor-library__thumb" />
                  : <div className="editor-library__thumb">无图</div>}
                <div className="editor-library__name" title={asset.name}>{asset.name}</div>
                <div className="editor-library__meta">
                  {asset.type === 'IMAGE' ? '图片' : asset.type === 'VIDEO' ? '视频' : asset.type === 'AUDIO' ? '音频' : '素材'}
                  {asset.scope === 'GLOBAL' ? ' · 全局' : ''}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {confirmOverwrite && (
        <div
          onClick={() => setConfirmOverwrite(null)}
          style={{
            position: 'fixed', inset: 0, background: 'rgba(20, 20, 19, 0.35)',
            display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200,
          }}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            style={{
              background: 'var(--color-panel)', borderRadius: 'var(--rounded-md)',
              boxShadow: '0 8px 32px rgba(20, 20, 19, 0.18)', padding: 24, minWidth: 320, maxWidth: 400,
            }}
          >
            <h3 style={{ margin: '0 0 8px', font: 'var(--text-body)', color: 'var(--color-ink)' }}>项目已有 {scenes.length} 个分镜</h3>
            <p style={{ margin: '0 0 16px', font: 'var(--text-body-sm)', color: 'var(--color-muted)' }}>选择如何处理本次生成：</p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <button onClick={() => { setConfirmOverwrite(null); void doGenerate('overwrite'); }} onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--color-primary-active)'; }} onMouseLeave={(e) => { e.currentTarget.style.background = 'var(--color-primary)'; }} style={{ padding: '10px', borderRadius: 'var(--rounded-md)', border: 'none', background: 'var(--color-primary)', color: 'var(--color-on-primary)', fontSize: 14, cursor: 'pointer' }}>覆盖更新（清空现有分镜）</button>
              <button onClick={() => { setConfirmOverwrite(null); void doGenerate('append'); }} onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--color-surface-card)'; }} onMouseLeave={(e) => { e.currentTarget.style.background = 'var(--color-panel)'; }} style={{ padding: '10px', borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-panel)', color: 'var(--color-ink)', fontSize: 14, cursor: 'pointer' }}>追加到现有分镜</button>
              <button onClick={() => setConfirmOverwrite(null)} onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--color-surface-card)'; }} onMouseLeave={(e) => { e.currentTarget.style.background = 'var(--color-panel)'; }} style={{ padding: '10px', borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-panel)', color: 'var(--color-muted)', fontSize: 14, cursor: 'pointer' }}>不生成（取消）</button>
            </div>
          </div>
        </div>
      )}

      <ProjectLibraryPickerModal
        open={libraryPickerOpen}
        projectId={currentProject?.id}
        type="image"
        purpose="image"
        maxSelectable={remainingRefSlots}
        onClose={() => setLibraryPickerOpen(false)}
        onConfirm={handleProjectLibraryReferences}
      />
    </div>
  );
}


