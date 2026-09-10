import { useEffect, useMemo, useRef, useState } from 'react';
import { AlertTriangle, Check, ChevronDown, ChevronUp, CircleStop, Clapperboard, Eye, ImageIcon, ImagePlus, Package, Settings2, Sparkles, Wand2, X } from 'lucide-react';
import { useAgentStore, type HumanInputInfo } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import { assetUrl } from '../../config';
import { sceneApi } from '../../api/scenes';
import { assetApi } from '../../api/assets';
import { VideoGenGateModal } from './VideoGenGateModal';
import { AgentParamSelector } from './AgentParamSelector';
import { RefineImageModal } from './RefineImageModal';
import { SceneMaterialPickerModal } from './SceneMaterialPickerModal';
import { SceneReferenceViewerModal } from './SceneReferenceViewerModal';

const STATUS_LABEL: Record<string, string> = {
  queued: '排队中',
  generating: '生成中',
  running: '生成中',
  completed: '已完成',
  failed: '生成失败',
  cancelled: '已取消',
};

const ACTIVE_STATUS = new Set(['queued', 'generating', 'running']);

interface SceneItem {
  id: string;
  /** 勾选/状态 key：id 缺失时用稳定占位，避免所有行共享同一 key 导致全选。 */
  key: string;
  sceneNumber?: number;
  name: string;
  image?: string;
  imageUrls?: string[];
  videoStatus?: string;
  videoPrompt?: string;
  imagePrompt?: string;
  scriptContent?: string;
  imageModel?: string;
  imageSize?: string;
  imageQuality?: string;
  videoModel?: string;
  videoResolution?: string;
  videoAspectRatio?: string;
  duration?: number;
}

/** 场景参数源（SceneItem / SceneResponse 均满足） */
type SceneConfigLike = {
  imageModel?: string | null;
  imageSize?: string | null;
  imageQuality?: string | null;
  videoModel?: string | null;
  videoResolution?: string | null;
  videoAspectRatio?: string | null;
  duration?: number | null;
};

/** 从分镜已存配置构造该类型参数回填对象（仅包含已配置值；未配置留空，不预选默认值）。 */
function sceneBuiltValueFor(s: SceneConfigLike, kind: 'image' | 'video') {
  if (kind === 'video') {
    const out: Record<string, string> = {};
    if (s.videoModel) out.videoModel = s.videoModel;
    if (s.videoResolution) out.videoResolution = s.videoResolution;
    if (s.videoAspectRatio) out.videoAspectRatio = s.videoAspectRatio;
    if (s.duration != null) out.videoDuration = String(s.duration);
    return out;
  }
  const out: Record<string, string> = {};
  if (s.imageModel) out.imageModel = s.imageModel;
  if (s.imageSize) out.imageSize = s.imageSize;
  if (s.imageQuality) out.imageQuality = s.imageQuality;
  return out;
}

function sceneImageUrlsFor(sceneId: string, scenes: Array<{ id: string; imageUrl?: string; imageUrls?: string }>, item?: SceneItem) {
  const live = scenes.find((scene) => scene.id === sceneId);
  const raw = live?.imageUrls ?? item?.imageUrls?.join(',') ?? '';
  const urls = raw.split(',').map((url) => url.trim()).filter(Boolean);
  if (urls.length > 0) return [...new Set(urls)];
  const primary = live?.imageUrl ?? item?.image;
  return primary ? [primary] : [];
}

export const isSceneReview = (info: HumanInputInfo) =>
  Array.isArray(info.assets) && info.assets.length > 0 &&
  info.assets.some((a) => a.type === 'scene' && (a.sceneNumber !== undefined || a.videoStatus !== undefined || a.scriptContent !== undefined));

/**
 * 分镜审查结果卡片（逐镜可编辑）：/chat Moon 智能体「展示分镜」触发的 HITL 卡片。
 *
 * <p>每行一个分镜：缩略图 + 勾选 + 图/视频提示词切换 + 折叠参数（网关模型选择）+ 上传图片 +
 * 上传素材 + 查看已投参考物 + 状态徽标。批量区提供「生成图片」「生成视频」「优化方案」，
 * 全部作用于勾选分镜。卡片整体限高可滚动，避免遮挡下方输入框。
 */
export function SceneReviewCard({ info, startExpanded = false }: { info: HumanInputInfo; startExpanded?: boolean }) {
  const projectId = useProjectStore((s) => s.currentProject?.id);
  const submitHumanInput = useAgentStore((s) => s.submitHumanInput);
  const streaming = useAgentStore((s) => s.streaming);
  const queue = useAgentStore((s) => s.storyboardVideoQueue);
  const cancelQueue = useAgentStore((s) => s.cancelStoryboardVideoQueue);
  const enqueueImageRefine = useAgentStore((s) => s.enqueueStoryboardImageRefine);
  const activeConversationId = useAgentStore((s) => s.activeConversationId);
  const scenes = useProjectStore((s) => s.scenes);

  const items = useMemo<SceneItem[]>(
    () => (info.assets ?? []).map((a, index) => ({
      id: a.id,
      key: a.id || `scene-${index}`,
      sceneNumber: a.sceneNumber,
      name: a.name,
      image: a.image,
      imageUrls: a.imageUrls,
      videoStatus: a.videoStatus,
      videoPrompt: a.videoPrompt,
      imagePrompt: a.imagePrompt,
      scriptContent: a.scriptContent,
      imageModel: a.imageModel,
      imageSize: a.imageSize,
      imageQuality: a.imageQuality,
      videoModel: a.videoModel,
      videoResolution: a.videoResolution,
      videoAspectRatio: a.videoAspectRatio,
      duration: a.duration,
    })),
    [info.assets],
  );

  // 参数回填用最新 project.scenes（数据库），不用 checkpoint 快照；快照兜底
  const freshestScene = (sceneId: string): SceneConfigLike | undefined =>
    scenes.find((sc) => sc.id === sceneId) ?? items.find((it) => it.id === sceneId);

  const [checked, setChecked] = useState<Record<string, boolean>>(() =>
    // 默认不全选，避免未取消勾选就误优化/生成全部
    Object.fromEntries(items.map((i) => [i.key, false])));
  const [expandedRow, setExpandedRow] = useState<string | null>(null);
  const [gateOpen, setGateOpen] = useState(false);
  const [refineFor, setRefineFor] = useState<string | null>(null);
  const [confirmGenImage, setConfirmGenImage] = useState(false);
  const [pane, setPane] = useState<'image' | 'video'>('image');
  const [collapsed, setCollapsed] = useState(!startExpanded);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [prompts, setPrompts] = useState<Record<string, Record<'image' | 'video', string>>>(() =>
    Object.fromEntries(items.map((i) => [i.id, {
      image: i.imagePrompt ?? i.scriptContent ?? '',
      video: i.videoPrompt ?? i.scriptContent ?? '',
    }])));
  // 每镜参数（key=sceneId → {image/video: {model,resolution,...}}）
  const [rowParams, setRowParams] = useState<Record<string, Record<'image' | 'video', Record<string, string>>>>(() =>
    Object.fromEntries(items.map((i) => {
      const cfg = freshestScene(i.id) ?? i;
      return [i.id, { image: sceneBuiltValueFor(cfg, 'image'), video: sceneBuiltValueFor(cfg, 'video') }];
    })));
  // 素材选择 / 参考物查看
  const [materialPickerFor, setMaterialPickerFor] = useState<string | null>(null);
  const [viewerFor, setViewerFor] = useState<string | null>(null);
  // 每镜已投参考图/关联资产数量
  const [refsSummary, setRefsSummary] = useState<Record<string, { images: number; assets: number }>>({});
  // 每个 panel(key=sceneId/kind) 当前已落库的参数签名；仅当与实际变化时保存，避免初始默认回填覆盖 scene 已存配置
  const lastSavedRef = useRef<Record<string, string>>({});

  // 拉取每镜已投参考图/关联资产计数（抛错忽略，不阻断卡片渲染）
  const loadRefSummaries = async () => {
    const summaries: Record<string, { images: number; assets: number }> = {};
    await Promise.all(items.map(async (s) => {
      try {
        const [refsRes, assetsRes] = await Promise.all([sceneApi.listReferences(s.id), assetApi.listSceneAssets(s.id)]);
        summaries[s.id] = {
          images: (refsRes.data.data ?? []).filter((r) => r.type === 'image').length,
          assets: new Set([
            ...(assetsRes.data.data?.imageAssets ?? []).map((asset) => asset.id),
            ...(assetsRes.data.data?.videoAssets ?? []).map((asset) => asset.id),
          ]).size,
        };
      } catch { /* 静默 */ }
    }));
    setRefsSummary(summaries);
  };

  useEffect(() => {
    void loadRefSummaries();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items]);

  // 卡片加载后刷新项目分镜：从数据库取最新参数配置回填，而不是 checkpoint 快照（快照里的参数可能已过期）
  useEffect(() => {
    const proj = useProjectStore.getState().currentProject;
    if (proj) void useProjectStore.getState().loadProject(proj.id).catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 完善图片/视频队列出现终态时刷新分镜，让行内缩略图与状态回显最新生成结果
  const imageQueue = useAgentStore((s) => s.storyboardImageQueue);
  const imageFinishedCount = imageQueue.filter((t) => t.status === 'completed' || t.status === 'failed' || t.status === 'cancelled').length;
  const videoFinishedCount = queue.filter((t) => t.status === 'completed' || t.status === 'failed' || t.status === 'cancelled').length;
  const finishedCountRef = useRef(0);
  useEffect(() => {
    const total = imageFinishedCount + videoFinishedCount;
    if (total > finishedCountRef.current) {
      finishedCountRef.current = total;
      const proj = useProjectStore.getState().currentProject;
      if (proj) void useProjectStore.getState().loadProject(proj.id).catch(() => undefined);
    }
  }, [imageFinishedCount, videoFinishedCount]);

  /** 分镜已存配置的规范签名（用于判断参数是否真的变化，避免初始回填覆盖）。 */
  const scenePayloadFor = (s: SceneItem, kind: 'image' | 'video') => {
    const cfg = freshestScene(s.id) ?? s;
    if (kind === 'video') {
      const p: Record<string, unknown> = {};
      if (cfg.videoModel) p.videoModel = cfg.videoModel;
      if (cfg.videoResolution) p.videoResolution = cfg.videoResolution;
      if (cfg.videoAspectRatio) p.videoAspectRatio = cfg.videoAspectRatio;
      if (cfg.duration != null) p.duration = cfg.duration;
      return JSON.stringify(p);
    }
    const p: Record<string, unknown> = {};
    if (cfg.imageModel) p.imageModel = cfg.imageModel;
    if (cfg.imageSize) p.imageSize = cfg.imageSize;
    if (cfg.imageQuality) p.imageQuality = cfg.imageQuality;
    return JSON.stringify(p);
  };

  /** 以分镜已存配置回填该类型参数选择器（键带前缀，AgentParamSelector 优先采纳）。 */
  const initialValueFor = (s: SceneItem, kind: 'image' | 'video') => {
    // 用最新 project.scenes（数据库）回填，而不是 checkpoint 快照；未配置字段留空，不预选默认
    return sceneBuiltValueFor(freshestScene(s.id) ?? s, kind);
  };

  const liveStatusMap = useMemo(() => {
    const map: Record<string, string> = {};
    for (const s of scenes) map[s.id] = s.videoStatus ?? '';
    return map;
  }, [scenes]);

  const liveScene = useMemo(() => {
    const map: Record<string, { hasImage: boolean; hasVideo: boolean; image?: string }> = {};
    for (const s of scenes) map[s.id] = { hasImage: Boolean(s.imageUrl), hasVideo: Boolean(s.videoUrl), image: s.imageUrl };
    return map;
  }, [scenes]);

  const chosen = items.filter((i) => checked[i.key]);
  const selectedCount = chosen.length;
  const allChecked = items.length > 0 && selectedCount === items.length;

  if (!isSceneReview(info)) return null;

  const flash = (msg: string) => {
    setFeedback(msg);
    window.setTimeout(() => setFeedback(null), 2600);
  };
  const refreshProject = async () => {
    const proj = useProjectStore.getState().currentProject;
    if (proj) await useProjectStore.getState().loadProject(proj.id).catch(() => undefined);
  };
  const toggle = (id: string) => setChecked((prev) => ({ ...prev, [id]: !prev[id] }));
  const toggleAll = () => setChecked(Object.fromEntries(items.map((i) => [i.key, !allChecked])));
  const setPrompt = (id: string, kind: 'image' | 'video', value: string) =>
    setPrompts((prev) => ({ ...prev, [id]: { ...prev[id], [kind]: value } }));
  const setRowParam = (id: string, kind: 'image' | 'video', params: Record<string, string>) =>
    setRowParams((prev) => ({ ...prev, [id]: { ...prev[id], [kind]: params } }));

  const savePrompt = async (s: SceneItem, kind: 'image' | 'video', value: string) => {
    if (!projectId) return;
    try {
      await sceneApi.update(s.id, { [kind === 'video' ? 'videoPrompt' : 'imagePrompt']: value || null });
    } catch { flash('提示词保存失败'); }
  };

  const buildPayload = (kind: 'image' | 'video', params: Record<string, string>) => {
    const payload: Record<string, unknown> = {};
    if (params[`${kind}Model`]) payload[`${kind}Model`] = params[`${kind}Model`];
    if (kind === 'video') {
      if (params[`${kind}Resolution`]) payload[`${kind}Resolution`] = params[`${kind}Resolution`];
      if (params[`${kind}AspectRatio`]) payload[`${kind}AspectRatio`] = params[`${kind}AspectRatio`];
      if (params[`${kind}Duration`]) payload.duration = Number(params[`${kind}Duration`]);
    } else {
      if (params[`${kind}Size`]) payload[`${kind}Size`] = params[`${kind}Size`];
      if (params[`${kind}Quality`]) payload[`${kind}Quality`] = params[`${kind}Quality`];
    }
    return payload;
  };

  // 需求6：把当前分镜的该类型参数同步到所有分镜
  const syncRowParamsToAll = async (s: SceneItem, kind: 'image' | 'video') => {
    const aliveParams = rowParams[s.id]?.[kind] ?? {};
    let payload = buildPayload(kind, aliveParams);
    // 兜底：选择器尚未回填（如未展开参数面板）时，用分镜当前已存配置
    if (Object.keys(payload).length === 0) payload = buildPayload(kind, sceneBuiltValueFor(freshestScene(s.id) ?? s, kind));
    if (Object.keys(payload).length === 0) { flash('当前分镜还没有可选参数'); return; }
    let ok = 0;
    for (const t of items) {
      try { await sceneApi.update(t.id, payload); ok += 1; } catch { /* 静默 */ }
    }
    flash(`已将${kind === 'video' ? '视频' : '图片'}参数同步到 ${ok}/${items.length} 个分镜`);
    await refreshProject();
  };

  const uploadImage = async (s: SceneItem) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.onchange = () => {
      const file = input.files?.[0];
      if (!file) return;
      void sceneApi.uploadReference(s.id, 'image', 'video', file)
        .then(() => { flash(`分镜 ${s.sceneNumber ?? ''} 参考图上传成功`); void refreshProject(); void loadRefSummaries(); })
        .catch((e) => flash(e instanceof Error ? e.message : '参考图上传失败'));
    };
    input.click();
  };

  // ── 资产选择器：拉取项目资产，勾选指定后关联（video 用途） ──
  const submitAction = (actionId: string, ids: string[]) => {
    if (ids.length === 0) { flash('请先选择要生成的分镜'); return; }
    if (streaming) return;
    void submitHumanInput(actionId, undefined, {}, ids);
  };

  // 暂不操作：退出当前人工介入（清 checkpoint，卡片关闭），不执行任何生成
  const skipSceneReview = () => {
    if (streaming) return;
    void submitHumanInput('review-skip', undefined, {}, []);
  };

  const refineScene = items.find((s) => s.id === refineFor);
  const submitRefine = async (payload: { prompt: string; model?: string; size?: string; quality?: string; sourceImageUrl?: string }) => {
    if (!refineFor || !projectId) return;
    const scene = items.find((s) => s.id === refineFor);
    await enqueueImageRefine([{
      sceneId: refineFor,
      prompt: payload.prompt,
      model: payload.model,
      size: payload.size,
      quality: payload.quality,
      sourceImageUrl: payload.sourceImageUrl ?? (refineFor ? liveScene[refineFor]?.image : undefined) ?? scene?.image,
    }], projectId);
    flash('已加入完善图片队列，可在右上角任务队列查看');
  };

  const cancelSceneTask = (sceneId: string) => {
    const task = queue.find((t) => t.sceneId === sceneId && (t.status === 'queued' || t.status === 'running'));
    if (task) void cancelQueue(task.id).catch(() => flash('取消失败'));
  };

  if (collapsed) {
    return (
      <button type="button" onClick={() => setCollapsed(false)}
        style={{ width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 14px', borderRadius: 12, border: '1px solid rgba(0,0,0,0.08)', background: '#faf9f5', cursor: 'pointer' }}>
        <span style={{ fontSize: 13, color: 'rgb(96,100,108)' }}>需要您确认 · 分镜审查结果（已勾选 {selectedCount}/{items.length}）</span>
        <ChevronUp size={16} />
      </button>
    );
  }

  return (
    <div style={{
      display: 'flex', flexDirection: 'column', maxHeight: 'min(58vh, 520px)',
      border: '1px solid rgba(0,0,0,0.08)', borderRadius: 16, background: 'white',
      boxShadow: '0 2px 8px rgba(15,17,21,0.06)', overflow: 'hidden',
    }}>
      {/* 固定头部 */}
      <div style={{ padding: '12px 14px', borderBottom: '1px solid rgba(0,0,0,0.06)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexShrink: 0, gap: 8 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ fontSize: 14, fontWeight: 600, color: 'rgb(15,17,21)' }}>分镜审查结果</span>
          <span style={{ fontSize: 12, color: 'rgb(152,158,168)' }}>{items.length} 个分镜</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <button type="button" onClick={() => setPane('image')}
            style={{ ...paneBtn, background: pane === 'image' ? 'rgb(65,118,230)' : 'transparent', color: pane === 'image' ? 'white' : 'rgb(96,100,108)' }}>图片</button>
          <button type="button" onClick={() => setPane('video')}
            style={{ ...paneBtn, background: pane === 'video' ? 'rgb(65,118,230)' : 'transparent', color: pane === 'video' ? 'white' : 'rgb(96,100,108)' }}>视频</button>
          <button type="button" onClick={() => setCollapsed(true)} style={{ ...iconBtn }} title="折叠"><ChevronDown size={16} /></button>
        </div>
      </div>

      {/* 滚动主体 */}
      <div style={{ padding: '10px 14px', overflowY: 'auto', flex: 1, minHeight: 0 }}>
        {feedback && (
          <div style={{ padding: '6px 10px', borderRadius: 8, background: 'rgba(65,118,230,0.08)', color: 'rgb(65,118,230)', fontSize: 12, marginBottom: 10 }}>{feedback}</div>
        )}

        {/* 批量选择栏 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10, flexWrap: 'wrap' }}>
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'rgb(96,100,108)' }}>
            <input type="checkbox" checked={allChecked} onChange={toggleAll} style={{ accentColor: 'rgb(65,118,230)' }} />
            全选
          </label>
          <button type="button" disabled={streaming || selectedCount === 0} onClick={() => {
            const hasImgChecked = chosen.some((s) => liveScene[s.id]?.hasImage ?? Boolean(s.image));
            if (hasImgChecked) setConfirmGenImage(true); else submitAction('review-gen-image', chosen.map((s) => s.id));
          }} style={{ ...batchBtn }}>
            <ImageIcon size={14} /> 生成图片{selectedCount > 0 ? ` (${selectedCount})` : ''}
          </button>
          <button type="button" disabled={streaming || selectedCount === 0} onClick={() => setGateOpen(true)} style={{ ...batchBtn }}>
            <Clapperboard size={14} /> 生成视频{selectedCount > 0 ? ` (${selectedCount})` : ''}
          </button>
          <button type="button" disabled={streaming || selectedCount === 0} onClick={() => submitAction('review-optimize', chosen.map((s) => s.id))} style={batchBtnGhost}>
            <Wand2 size={14} /> 优化方案
          </button>
          <button type="button" disabled={streaming} onClick={skipSceneReview} style={batchBtnGhost}>
            <X size={14} /> 暂不操作
          </button>
        </div>

        {/* 逐镜行 */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {items.map((s) => {
            const live = liveScene[s.id];
            const hasImage = live ? live.hasImage : Boolean(s.image);
            const hasVideo = live ? live.hasVideo : false;
            const status = liveStatusMap[s.id] || s.videoStatus || '';
            const active = ACTIVE_STATUS.has(status);
            const cancellable = status === 'queued' || status === 'generating' || status === 'running';
            const thumb = live?.image || s.image;
            const prompt = prompts[s.id]?.[pane] ?? '';
            const rowExpanded = expandedRow === s.id;
          const rowModels = pane === 'video' ? info.videoModels : info.imageModels;
          const rowKeyPrefix = pane === 'video' ? 'video' : 'image';
          const refs = refsSummary[s.id] ?? { images: 0, assets: 0 };
          return (
              <article key={s.key} style={{ display: 'flex', gap: 10, padding: 10, borderRadius: 12, border: checked[s.key] ? '1.5px solid rgba(65,118,230,0.35)' : '1px solid rgba(0,0,0,0.06)', background: checked[s.key] ? 'rgba(65,118,230,0.03)' : 'white' }}>
                <input type="checkbox" checked={Boolean(checked[s.key])} disabled={streaming} onChange={() => toggle(s.key)} style={{ accentColor: 'rgb(65,118,230)', marginTop: 4 }} />
                {thumb ? (
                  <img src={assetUrl(thumb)} alt={`分镜${s.sceneNumber ?? ''}`} style={{ width: 46, height: 46, borderRadius: 8, objectFit: 'cover', flexShrink: 0, border: '1px solid rgba(0,0,0,0.06)' }}
                    onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
                ) : (
                  <span style={{ width: 46, height: 46, borderRadius: 8, background: 'rgba(0,0,0,0.04)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'rgb(152,158,168)', fontSize: 13, flexShrink: 0 }}>{String(s.sceneNumber ?? 0).padStart(2, '0')}</span>
                )}
                <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                    <strong style={{ fontSize: 13, color: 'rgb(15,17,21)' }}>{s.name}</strong>
                    <span style={statusPill()}>{hasImage ? '有图' : '缺图'}</span>
                    <span style={statusPill()}>{hasVideo ? '有视频' : '缺视频'}</span>
                    {status && status !== 'idle' && (
                      <span style={statusPill(active ? 'active' : status === 'failed' ? 'failed' : 'ok')}>{STATUS_LABEL[status] ?? status}</span>
                    )}
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                    <span style={{ fontSize: 12, color: 'rgb(152,158,168)' }}>{pane === 'video' ? '视频提示词' : '图片提示词'}</span>
                    <textarea
                      value={prompt}
                      disabled={streaming}
                      onChange={(e) => setPrompt(s.id, pane, e.target.value)}
                      onBlur={(e) => void savePrompt(s, pane, e.target.value)}
                      placeholder={pane === 'video' ? '视频提示词…' : '图片提示词…'}
                      rows={2}
                      style={{ width: '100%', boxSizing: 'border-box', padding: '5px 8px', borderRadius: 8, border: '1px solid rgba(0,0,0,0.1)', fontSize: 12, outline: 'none', resize: 'vertical', minHeight: 44, lineHeight: 1.5 }}
                    />
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                    <button type="button" onClick={() => void uploadImage(s)} style={rowActionBtn}><ImagePlus size={13} /> 上传图片</button>
                    <button type="button" onClick={() => setMaterialPickerFor(s.id)} style={rowActionBtn}><Package size={13} /> 上传素材</button>
                    <span style={{ fontSize: 12, color: 'rgb(152,158,168)' }}>参考图 {refs.images} · 资产 {refs.assets}</span>
                    <button type="button" onClick={() => setViewerFor(s.id)} style={rowActionBtn}><Eye size={13} /> 查看参考物</button>
                    {hasImage && (
                      <button type="button" disabled={streaming} onClick={() => setRefineFor(s.id)} style={{ ...rowActionBtn, color: 'rgb(65,118,230)' }}><Sparkles size={13} /> 完善图片</button>
                    )}
                    <button type="button" onClick={() => setExpandedRow(rowExpanded ? null : s.id)} style={{ ...rowActionBtn, ...(rowExpanded ? { color: 'rgb(65,118,230)' } : {}) }}>
                      <Settings2 size={13} /> 参数{rowExpanded ? '' : '配置'}
                    </button>
                    {cancellable && (
                      <button type="button" onClick={() => cancelSceneTask(s.id)} style={{ ...rowActionBtn, color: 'rgb(217,45,32)' }}><CircleStop size={13} /> 取消</button>
                    )}
                  </div>
                  {rowExpanded && (
                    <div style={{ padding: 8, borderRadius: 10, background: 'rgba(0,0,0,0.03)', marginTop: 2 }}>
                      {rowModels && rowModels.length > 0 ? (
                        <AgentParamSelector
                          key={`${s.id}-${pane}`}
                          keyPrefix={rowKeyPrefix}
                          models={rowModels}
                          recommended={info.recommended}
                          reasons={info.reasons}
                          initialValue={initialValueFor(s, pane)}
                          silent
                          onParamsChange={(p) => {
                            setRowParam(s.id, pane, p);
                            // 需求5：调整每一个参数选项就即时保存该分镜配置。
                            // 与「分镜已存配置」或「最近一次已保存值」一致时不重复保存（避免初始回填覆盖）。
                            const key = `${s.id}/${pane}`;
                            const payload = buildPayload(pane, p);
                            if (Object.keys(payload).length === 0) return;
                            const sig = JSON.stringify(payload);
                            if (lastSavedRef.current[key] === sig) return;
                            const sceneSig = scenePayloadFor(s, pane);
                            if (sig === sceneSig) {
                              lastSavedRef.current[key] = sig;
                              return;
                            }
                            void sceneApi.update(s.id, payload)
                              .then(() => { lastSavedRef.current[key] = sig; })
                              .catch(() => flash('参数保存失败'));
                          }}
                        />
                      ) : (
                        <div style={{ fontSize: 12, color: 'rgb(152,158,168)', padding: '4px 2px' }}>暂无{rowKeyPrefix === 'video' ? '视频' : '图片'}模型（网关未配置）</div>
                      )}
                      <button type="button" disabled={streaming} onClick={() => void syncRowParamsToAll(s, pane)} style={{ ...batchBtnGhost, padding: '5px 12px', marginTop: 4 }}>
                        <Check size={13} /> 将{pane === 'video' ? '视频' : '图片'}参数同步到所有分镜
                      </button>
                    </div>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      </div>

      <SceneMaterialPickerModal
        open={Boolean(materialPickerFor)}
        sceneId={materialPickerFor ?? ''}
        projectId={projectId}
        currentConversationId={activeConversationId}
        sceneImageUrls={sceneImageUrlsFor(
          materialPickerFor ?? '',
          scenes,
          items.find((value) => value.id === materialPickerFor),
        )}
        onClose={() => setMaterialPickerFor(null)}
        onSaved={async () => {
          await refreshProject();
          await loadRefSummaries();
          flash('素材选择已保存');
        }}
      />

      <SceneReferenceViewerModal
        open={Boolean(viewerFor)}
        sceneId={viewerFor ?? ''}
        onClose={() => setViewerFor(null)}
        onChanged={async () => {
          await refreshProject();
          await loadRefSummaries();
        }}
      />

      <VideoGenGateModal open={gateOpen} count={selectedCount} onClose={() => setGateOpen(false)}
        onConfirm={() => { setGateOpen(false); submitAction('review-video-auto', chosen.map((s) => s.id)); }} />

      <RefineImageModal
        open={Boolean(refineFor)}
        sceneNumber={refineScene?.sceneNumber}
        sourceImageUrl={refineFor && liveScene[refineFor]?.image ? liveScene[refineFor].image : refineScene?.image}
        models={info.imageModels ?? []}
        initialValue={refineScene ? initialValueFor(refineScene, 'image') : undefined}
        onClose={() => setRefineFor(null)}
        onSubmit={submitRefine}
      />

      {confirmGenImage && (
        <div style={{ position: 'fixed', inset: 0, zIndex: 140, background: 'rgba(15,17,21,0.45)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16 }}
          onPointerDown={(e) => { if (e.target === e.currentTarget) setConfirmGenImage(false); }}>
          <div style={{ width: '100%', maxWidth: 440, background: 'white', borderRadius: 16, padding: '18px 20px', boxShadow: '0 18px 48px rgba(15,17,21,0.24)' }}>
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
              <AlertTriangle size={16} color="rgb(217,45,32)" />
              <strong style={{ fontSize: 14, color: 'rgb(15,17,21)' }}>重新生成图片确认</strong>
            </div>
            <p style={{ fontSize: 13, color: 'rgb(96,100,108)', lineHeight: 1.6, margin: '0 0 14px' }}>
              当前已有分镜生成了图片，重新生成会用新图覆盖原有图片，确认继续吗？
            </p>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button onClick={() => setConfirmGenImage(false)} style={{ padding: '8px 16px', borderRadius: 10, border: '1px solid rgba(0,0,0,0.08)', background: 'transparent', color: 'rgb(96,100,108)', fontSize: 13, cursor: 'pointer' }}>取消</button>
              <button onClick={() => { setConfirmGenImage(false); submitAction('review-gen-image', chosen.map((s) => s.id)); }}
                style={{ padding: '8px 18px', borderRadius: 10, border: 'none', background: 'rgb(65,118,230)', color: 'white', fontSize: 13, cursor: 'pointer' }}>确认生成</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

const paneBtn: React.CSSProperties = { padding: '5px 12px', borderRadius: 8, border: '1px solid rgba(0,0,0,0.08)', fontSize: 12, cursor: 'pointer' };
const iconBtn: React.CSSProperties = { border: 'none', background: 'transparent', color: 'rgb(152,158,168)', cursor: 'pointer', padding: 4, display: 'inline-flex', alignItems: 'center' };
const statusPill = (kind?: string): React.CSSProperties => ({
  fontSize: 11, padding: '1px 7px', borderRadius: 8, color: kind === 'active' ? 'rgb(65,118,230)' : kind === 'failed' ? 'rgb(217,45,32)' : 'rgb(15,150,80)',
  background: kind === 'active' ? 'rgba(65,118,230,0.1)' : kind === 'failed' ? 'rgba(217,45,32,0.08)' : 'rgba(15,150,80,0.08)',
});
const batchBtn: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 14px', borderRadius: 10, border: 'none', background: 'rgb(65,118,230)', color: 'white', fontSize: 13, cursor: 'pointer' };
const batchBtnGhost: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 14px', borderRadius: 10, border: '1px solid rgba(0,0,0,0.08)', background: 'transparent', color: 'rgb(96,100,108)', fontSize: 13, cursor: 'pointer' };
const rowActionBtn: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 4, padding: '4px 9px', borderRadius: 8, border: '1px solid rgba(0,0,0,0.08)', background: 'transparent', color: 'rgb(96,100,108)', fontSize: 12, cursor: 'pointer' };
