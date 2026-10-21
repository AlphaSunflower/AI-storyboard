import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  ChevronDown, Image as ImageIcon, Images, LoaderCircle, Maximize2, Plus, Sparkles, Upload, Video as VideoIcon,
} from 'lucide-react';
import { RichPromptInput } from '../RichPromptInput';
import { ImagePreviewOverlay } from '../ImagePreviewOverlay';
import type { CanvasNode, PersistedAsset } from '../../types';
import type { ReferenceImagePoolResult } from '../../canvas/referenceImagePool';
import {
  IMAGE_GENERATION_KIND,
  VIDEO_GENERATION_KIND,
  generationModesForKind,
  generationModeInputRule,
  mergedGenerationKind,
  resolveNodeMode,
  type MergedGenerationKind,
} from '../../canvas/generationModes';
import { useGatewayModels, parseModelParams, normalizeModelDefault, type ModelParamSpecField } from '../../hooks/useGatewayModels';
import { useCanvasStore } from '../../store/canvasStore';
import { useMediaStore } from '../../store/mediaStore';
import { useAssetPreviewUrl } from '../../hooks/useAssetPreviewUrl';
import { usePresence } from '../../hooks/usePresence';
import { createPersistedCanvasAsset, persistExistingCanvasAsset } from '../../services/canvasMedia';
import { getMediaNodeSize } from '../../utils/mediaPipeline';
import { hydrateAsset, listLibraryAssets } from '../../services/projectStorage';
import { IMAGE_GEN_TYPES } from '../../canvas/imagePreset';

// 生成节点共用面板：预览卡 + composer（模式 chips / 参考素材 / 提示词 / 设置行 / 发送）。
// 生图与生视频只差模式集合、设置项（时长 vs 数量）与提交回调，其余结构完全一致。
// 所有枚举都来自网关模型能力（useGatewayModels -> params），不硬编码档位。

// 图 4 里明确不要的两组：输出格式与码率档位（即使网关提供了也不渲染）。
const EXCLUDED_SPEC_KEY = /^(output_?format|format|bit_?rate|video_?bitrate|quality_?tier)$/i;

export type GenerationUpdateNode = (id: string, patch: Record<string, unknown>) => void;

export interface GenerationModeOption { id: string; label: string }

/** 模式 chips 行：切换 data.mode，右侧为打开参数浮层的箭头。 */
export function GenerationModeChips({ kind, mode, onChange, onExpand, showExpand = true }: {
  kind: string | undefined;
  mode: string | undefined;
  onChange: (mode: string) => void;
  onExpand?: () => void;
  showExpand?: boolean;
}) {
  const modes = generationModesForKind(kind) as readonly GenerationModeOption[];
  return (
    <div className="gen-modes nodrag">
      <div className="gen-modes__list" role="group" aria-label="生成模式">
        {modes.map((entry) => (
          <button
            key={entry.id}
            type="button"
            className={mode === entry.id ? 'is-active' : ''}
            aria-pressed={mode === entry.id}
            onClick={(event) => { event.stopPropagation(); onChange(entry.id); }}
          >
            {entry.label}
          </button>
        ))}
      </div>
      {showExpand && (
        <button type="button" className="gen-modes__expand" aria-label="参数设置" title="参数设置" onClick={(event) => { event.stopPropagation(); onExpand?.(); }}>
          <svg viewBox="0 0 16 16" width="13" height="13" aria-hidden="true"><path d="M6 2H2v4M10 14h4v-4M14 6V2h-4M2 10v4h4" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg>
        </button>
      )}
    </div>
  );
}

/** 预览卡：生成中显示进度，有结果展示最近一次落地资产，否则空态提示。 */
export function GenerationPreviewCard({ nodeId, media, generating, progress, emptyLabel }: {
  nodeId: string;
  media: 'image' | 'video';
  generating: boolean;
  progress?: number;
  emptyLabel: string;
}) {
  const latestAssetId = useCanvasStore((state) => {
    for (let index = state.generationRecords.length - 1; index >= 0; index -= 1) {
      const record = state.generationRecords[index];
      if (record.producerNodeId !== nodeId || !record.materializedAssetIds.length) continue;
      return record.materializedAssetIds[record.materializedAssetIds.length - 1];
    }
    return undefined;
  });
  const url = useAssetPreviewUrl(latestAssetId);
  const [previewOpen, setPreviewOpen] = useState(false);
  const previewPresence = usePresence(previewOpen, { duration: 140 });
  const EmptyIcon = media === 'video' ? VideoIcon : ImageIcon;
  // 只有静态图能进图片预览（视频继续留在节点内的播放器里）
  const openable = media === 'image' && Boolean(latestAssetId) && Boolean(url) && !generating;
  return (
    <>
      {/* 预览面不挂 .nodrag：生成节点整块正文就是这个框（.media-node.has-surface .gen-node 无内边距），
          一旦这里退出拖动，图片/视频生成节点就只能靠标题条挪位置。
          框内没有输入控件，整块当拖动面是安全的；点击（位移小于 nodeDragThreshold）仍会落到下面的打开按钮。 */}
      <div className="gen-preview" data-state={generating ? 'generating' : url ? 'ready' : 'empty'}>
        {url && !generating && (openable ? (
          <button
            type="button"
            className="gen-preview__open"
            aria-label="预览图片：生成结果"
            onClick={() => setPreviewOpen(true)}
          >
            <img className="gen-preview__media" src={url} alt="生成结果" decoding="async" draggable={false} />
            <span className="gen-preview__zoom" aria-hidden="true"><Maximize2 size={14} /></span>
          </button>
        ) : (
          <img className="gen-preview__media" src={url} alt="生成结果" decoding="async" draggable={false} />
        ))}
        {generating && (
          <div className="gen-preview__progress" role="status">
            <LoaderCircle className="is-spinning" size={20} />
            <strong>{typeof progress === 'number' && progress > 0 ? `${progress}%` : '生成中'}</strong>
          </div>
        )}
        {!url && !generating && (
          <div className="gen-preview__empty">
            <EmptyIcon size={26} />
            <span>{emptyLabel}</span>
          </div>
        )}
      </div>
      {previewPresence.mounted && latestAssetId && (
        <ImagePreviewOverlay
          present={previewPresence.present}
          target={{ assetId: latestAssetId, label: '生成结果' }}
          onClose={() => setPreviewOpen(false)}
        />
      )}
    </>
  );
}

function LibraryThumb({ record }: { record: PersistedAsset }) {
  const [url, setUrl] = useState('');
  useEffect(() => {
    const next = URL.createObjectURL(record.thumbnailBlob);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [record]);
  return url ? <img src={url} alt={record.name} /> : <span className="gen-ref-thumb-empty" />;
}

/** 「＋」参考素材菜单：上传本地图片 / 从本地素材库选择，选定后建图片节点并自动连线到 reference。 */
function GenerationReferencePicker({ nodeId, onClose, present = true }: { nodeId: string; onClose: () => void; present?: boolean }) {
  const nodes = useCanvasStore((state) => state.nodes);
  const addImportedImages = useCanvasStore((state) => state.addImportedImages);
  const connect = useCanvasStore((state) => state.connect);
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const [libraryAssets, setLibraryAssets] = useState<PersistedAsset[]>([]);
  const [showLibrary, setShowLibrary] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const target = nodes.find((node) => node.id === nodeId);

  useEffect(() => {
    if (!showLibrary) return;
    void listLibraryAssets().then((list) => setLibraryAssets(list.slice(0, 12))).catch(() => setLibraryAssets([]));
  }, [showLibrary]);

  const attachImage = (asset: PersistedAsset) => {
    const referenceCount = useCanvasStore.getState().edges
      .filter((edge) => edge.target === nodeId && edge.targetHandle === 'reference').length;
    const origin = target?.position ?? { x: 0, y: 0 };
    const [createdId] = addImportedImages([{
      assetId: asset.id,
      label: asset.name.replace(/\.[^.]+$/, ''),
      position: { x: origin.x - 340, y: origin.y + referenceCount * 220 },
      size: getMediaNodeSize(asset.width, asset.height),
    }]);
    if (!createdId) return;
    connect({ source: createdId, sourceHandle: 'image', target: nodeId, targetHandle: 'reference' });
  };

  const uploadLocal = async (file: File) => {
    setBusy(true);
    setError(null);
    try {
      const asset = await createPersistedCanvasAsset(file, nodeId);
      upsertAsset(asset);
      attachImage(asset);
      onClose();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '参考图上传失败');
    } finally {
      setBusy(false);
    }
  };

  const pickFromLibrary = async (record: PersistedAsset) => {
    setBusy(true);
    setError(null);
    try {
      const asset = await persistExistingCanvasAsset(hydrateAsset(record), '');
      upsertAsset(asset);
      attachImage(asset);
      onClose();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '素材写入失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="gen-ref-picker motion-presence nodrag nowheel" data-present={present} role="dialog" aria-label="添加参考素材">
      {!showLibrary ? (
        <>
          <button type="button" disabled={busy} onClick={(event) => { event.stopPropagation(); fileInput.current?.click(); }}><Upload size={13} /> 上传本地图片</button>
          <button type="button" disabled={busy} onClick={(event) => { event.stopPropagation(); setShowLibrary(true); }}><Images size={13} /> 从本地素材库选择</button>
        </>
      ) : (
        <div className="gen-ref-picker__library">
          {libraryAssets.length === 0 && <span className="gen-ref-picker__empty">素材库暂无图片</span>}
          {libraryAssets.map((record) => (
            <button key={record.id} type="button" disabled={busy} title={record.name} onClick={(event) => { event.stopPropagation(); void pickFromLibrary(record); }}>
              <LibraryThumb record={record} />
            </button>
          ))}
        </div>
      )}
      {error && <small className="gen-ref-picker__error">{error}</small>}
      <input
        ref={fileInput}
        className="canvas-file-input"
        type="file"
        accept="image/png,image/jpeg,image/webp"
        onChange={(event) => {
          const file = event.target.files?.[0];
          event.target.value = '';
          if (file) void uploadLocal(file);
        }}
      />
    </div>
  );
}

/** 单个参考缩略图（右上 × 解除引用）。 */
function ReferenceThumb({ entry, onDelete }: {
  entry: { number: number; assetId: string; sourceNodeId: string; sourceNodeName: string };
  onDelete?: (entry: { number: number; sourceNodeId: string }) => void;
}) {
  const url = useAssetPreviewUrl(entry.assetId);
  return (
    <span className="gen-ref-thumb" title={entry.sourceNodeName || `图${entry.number}`}>
      {url ? <img src={url} alt={entry.sourceNodeName} decoding="async" draggable={false} /> : <span className="gen-ref-thumb-empty" />}
      {onDelete && (
        <button
          type="button"
          aria-label={`移除参考图${entry.number}`}
          onClick={(event) => {
            event.stopPropagation();
            onDelete({ number: entry.number, sourceNodeId: entry.sourceNodeId });
          }}
        >
          ×
        </button>
      )}
    </span>
  );
}

/** 参考素材区：全能参考=缩略图＋「＋」；首尾帧=两个固定槽；视频参考=视频/图片；图生图=源图。 */
/** 引用端口按 portOrder 排序的参考条目（首尾帧靠它区分首帧/尾帧，提交时也用它排序）。 */
export function useOrderedReferenceEntries(nodeId: string, pool: ReferenceImagePoolResult) {
  const edges = useCanvasStore((state) => state.edges);
  const nodes = useCanvasStore((state) => state.nodes);
  return useMemo(() => {
    const referenceHandles = new Set(['reference', 'first', 'last', 'source']);
    const referenceEdges = edges
      .filter((edge) => edge.target === nodeId && referenceHandles.has(String(edge.targetHandle)))
      .map((edge, index) => ({ edge, order: edge.data?.portOrder ?? index }))
      .sort((left, right) => left.order - right.order);
    return referenceEdges
      .map(({ edge }) => {
        const sourceAssetId = nodes.find((node) => node.id === edge.source)?.data.assetId;
        return pool.entries.find((candidate) => candidate.sourceNodeId === edge.source)
          ?? pool.entries.find((candidate) => candidate.assetId === sourceAssetId)
          ?? null;
      })
      .filter((entry): entry is NonNullable<typeof entry> => Boolean(entry));
  }, [edges, nodeId, nodes, pool.entries]);
}

export function GenerationReferenceArea({ nodeId, kind, mode, pool, onDeleteRef }: {
  nodeId: string;
  kind: MergedGenerationKind;
  mode: string;
  pool: ReferenceImagePoolResult;
  onDeleteRef?: (entry: { number: number; sourceNodeId: string }) => void;
}) {
  const [pickerOpen, setPickerOpen] = useState(false);
  const pickerPresence = usePresence(pickerOpen, { duration: 140 });
  const rule = generationModeInputRule(kind, { mode });
  const orderedEntries = useOrderedReferenceEntries(nodeId, pool);

  const videoEntries = orderedEntries.filter((entry) => entry.mediaType === 'video');
  const imageEntries = orderedEntries.filter((entry) => entry.mediaType === 'image');

  const plusButton = (
    <span className="gen-ref-add">
      <button type="button" aria-label="添加参考素材" title="添加参考素材" onClick={(event) => { event.stopPropagation(); setPickerOpen((open) => !open); }}>
        <Plus size={14} />
      </button>
      {pickerPresence.mounted && <GenerationReferencePicker present={pickerPresence.present} nodeId={nodeId} onClose={() => setPickerOpen(false)} />}
    </span>
  );

  if (mode === 'firstLastFrame') {
    const [first, last] = orderedEntries;
    return (
      <div className="gen-refs gen-refs--frames" role="group" aria-label="首尾帧">
        <div className="gen-frame-slot" data-filled={first ? 'true' : 'false'}>
          <span className="gen-frame-slot__tag">首帧</span>
          {first ? <ReferenceThumb entry={first} onDelete={onDeleteRef} /> : <span className="gen-frame-slot__empty">连接或上传首帧</span>}
        </div>
        <div className="gen-frame-slot" data-filled={last ? 'true' : 'false'}>
          <span className="gen-frame-slot__tag">尾帧</span>
          {last ? <ReferenceThumb entry={last} onDelete={onDeleteRef} /> : <span className="gen-frame-slot__empty">连接或上传尾帧</span>}
        </div>
      </div>
    );
  }

  if (mode === 'videoReference') {
    return (
      <div className="gen-refs" role="group" aria-label="参考素材">
        {videoEntries.map((entry) => <ReferenceThumb key={entry.assetId} entry={entry} onDelete={onDeleteRef} />)}
        {imageEntries.map((entry) => <ReferenceThumb key={entry.assetId} entry={entry} onDelete={onDeleteRef} />)}
        {!videoEntries.length && <span className="gen-refs__hint">连接视频节点作为参考，或点「＋」添加图片</span>}
        {orderedEntries.length < (rule?.referenceMax ?? 9) && plusButton}
      </div>
    );
  }

  if (mode === 'image2image') {
    return (
      <div className="gen-refs" role="group" aria-label="源图片">
        {imageEntries.map((entry) => <ReferenceThumb key={entry.assetId} entry={entry} onDelete={onDeleteRef} />)}
        {!imageEntries.length && <span className="gen-refs__hint">连接源图片，或点「＋」添加（多张会一起作为参考）</span>}
        {/* 图生图不再是「只能带一张」：没有区域掩码时按整图编辑跑，多张参考图也会一起下发。 */}
        {orderedEntries.length < (rule?.referenceMax ?? 14) && plusButton}
      </div>
    );
  }

  // text2* / fullReference / batchRefine：缩略图 + 「＋」
  return (
    <div className="gen-refs" role="group" aria-label="参考素材">
      {imageEntries.map((entry) => <ReferenceThumb key={entry.assetId} entry={entry} onDelete={onDeleteRef} />)}
      {!imageEntries.length && (
        <span className="gen-refs__hint">
          {mode === 'batchRefine' ? '连接多张图片，逐张套用同一批提示词' : '连接参考素材，或点「＋」添加本地图片'}
        </span>
      )}
      {orderedEntries.length < (rule?.referenceMax ?? 9) && plusButton}
    </div>
  );
}

export interface GenerationSettings {
  loading: boolean;
  error: string | null;
  modelId: string;
  models: Array<{ value: string; label: string }>;
  onModel: (value: string) => void;
  ratios: string[];
  ratio: string;
  setRatio: (value: string) => void;
  resolutions: string[];
  resolution: string;
  setResolution: (value: string) => void;
  durations: number[];
  duration: number;
  setDuration: (value: number) => void;
  countMax: number;
  countDefault: number;
  count: number;
  autoCount: boolean;
  setCount: (value: number) => void;
  toggleAutoCount: () => void;
  templateKey: string;
  setTemplate: (value: string) => void;
  specFields: Array<{
    field: ModelParamSpecField;
    label: string;
    value: unknown;
    options: string[];
    set: (value: unknown) => void;
  }>;
}

const FIELD_LABELS: Record<string, string> = {
  prompt_optimizer: '提示词优化',
  negative_prompt: '负面提示词',
  seed: '随机种子',
  watermark: '水印',
  steps: '采样步数',
  guidance_scale: '引导强度',
  strength: '参考强度',
  quality: '质量',
  style: '风格',
};

// 生成节点的模型/参数读写：全部来自网关能力枚举，写回 data.model / gatewayParams /
// aspectRatio / resolution 或 size / duration / n，与执行链（nodeExecutionService）保持一致。
export function useGenerationSettings({ kind, nodeId, data, updateNode, referenceCount = 0 }: {
  kind: MergedGenerationKind;
  nodeId: string;
  data: CanvasNode['data'];
  updateNode: GenerationUpdateNode;
  referenceCount?: number;
}): GenerationSettings {
  const { models, loading, error } = useGatewayModels(kind === VIDEO_GENERATION_KIND ? 'video' : 'image');
  const mergedModels = models.map((model) => ({ value: model.value, label: model.label || model.value, params: model.params }));
  const modelId = typeof data.model === 'string' && data.model
    ? data.model
    : mergedModels.find((model) => model.value)?.value ?? '';
  const currentModel = mergedModels.find((model) => model.value === modelId);
  const params = useMemo(() => parseModelParams(currentModel?.params), [currentModel?.params]);

  const ratios = params.aspectRatios ?? (kind === VIDEO_GENERATION_KIND ? ['16:9', '9:16', '1:1'] : ['1:1', '16:9', '9:16', '3:4']);
  const resolutions = (kind === VIDEO_GENERATION_KIND ? params.resolutions : params.sizes)
    ?? (kind === VIDEO_GENERATION_KIND ? ['720P', '1080P'] : ['1K', '2K']);
  const durations = kind === VIDEO_GENERATION_KIND
    ? (params.durations && params.durations.length ? [...params.durations].sort((left, right) => left - right) : [4, 5, 6, 8, 10])
    : [];
  const ratio = String(data.aspectRatio ?? normalizeModelDefault(params.aspectRatioDefault, ratios) ?? ratios[0] ?? '');
  const resolution = String(
    (kind === VIDEO_GENERATION_KIND ? data.resolution ?? data.size : data.size)
    ?? normalizeModelDefault(kind === VIDEO_GENERATION_KIND ? params.resolutionDefault : params.sizeDefault, resolutions)
    ?? resolutions[0] ?? '',
  );
  const duration = Number(data.duration ?? params.durationDefault ?? durations[0] ?? 5);

  const seedreamMax = Math.max(1, 15 - Math.max(0, referenceCount));
  const countMax = Math.max(1, Math.min(params.n?.max ?? seedreamMax, seedreamMax));
  const countDefault = Math.max(1, Math.min(params.n?.default ?? 1, countMax));
  const autoCount = kind === IMAGE_GENERATION_KIND && Number(data.n) === 0;
  const rawCount = typeof data.n === 'number' ? data.n : countDefault;
  const count = Math.max(1, Math.min(rawCount, countMax));
  const [lastManualCount, setLastManualCount] = useState(() => autoCount ? countDefault : count);

  const setCount = (value: number) => {
    const next = Math.max(1, Math.min(value, countMax));
    setLastManualCount(next);
    updateNode(nodeId, { n: next });
  };

  const gatewayParams = data.gatewayParams && typeof data.gatewayParams === 'object'
    ? data.gatewayParams as Record<string, unknown>
    : {};

  const specFields = (params.spec?.request ?? [])
    .filter((field) => field.show !== false && !EXCLUDED_SPEC_KEY.test(field.key))
    .map((field) => {
      const options = Array.isArray(field.options) ? field.options.map(String) : [];
      const declared = gatewayParams[field.key];
      const legacy = field.key === 'aspect_ratio' || field.key === 'aspectRatio'
        ? data.aspectRatio
        : field.key === 'resolution'
          ? data.resolution
          : field.key === 'size'
            ? data.size
            : field.key === 'duration'
              ? data.duration
              : field.key === 'n'
                ? data.n
                : field.key === 'quality'
                  ? data.quality
                  : field.key === 'style'
                    ? data.style
                    : undefined;
      const value = declared ?? legacy ?? field.default ?? options[0];
      return {
        field,
        label: FIELD_LABELS[field.key] ?? field.label ?? field.key,
        value,
        options,
        set: (next: unknown) => {
          const patch: Record<string, unknown> = { gatewayParams: { ...gatewayParams, [field.key]: next } };
          if (field.key === 'aspect_ratio' || field.key === 'aspectRatio') patch.aspectRatio = next;
          if (field.key === 'resolution') patch.resolution = next;
          if (field.key === 'size') patch.size = next;
          if (field.key === 'duration') patch.duration = next;
          if (field.key === 'n') patch.n = next;
          if (field.key === 'quality') patch.quality = next;
          if (field.key === 'style') patch.style = next;
          updateNode(nodeId, patch);
        },
      };
    });

  const setModel = (value: string) => {
    const next = mergedModels.find((model) => model.value === value);
    const nextParams = parseModelParams(next?.params);
    const nextRatios = nextParams.aspectRatios ?? ratios;
    const nextResolutions = (kind === VIDEO_GENERATION_KIND ? nextParams.resolutions : nextParams.sizes) ?? resolutions;
    const patch: Record<string, unknown> = {
      model: value,
      aspectRatio: normalizeModelDefault(nextParams.aspectRatioDefault, nextRatios) ?? nextRatios[0],
      gatewayParams: Object.fromEntries((nextParams.spec?.request ?? [])
        .filter((field) => field.show !== false && !EXCLUDED_SPEC_KEY.test(field.key))
        .map((field) => [field.key, field.default ?? (Array.isArray(field.options) ? field.options[0] : undefined)])),
    };
    if (kind === VIDEO_GENERATION_KIND) {
      patch.resolution = normalizeModelDefault(nextParams.resolutionDefault, nextResolutions) ?? nextResolutions[0];
      patch.duration = nextParams.durationDefault ?? nextParams.durations?.[0] ?? duration;
    } else {
      patch.size = normalizeModelDefault(nextParams.sizeDefault, nextResolutions) ?? nextResolutions[0];
      if (nextParams.qualities?.length) patch.quality = normalizeModelDefault(nextParams.qualityDefault, nextParams.qualities);
      if (nextParams.styles?.length) patch.style = normalizeModelDefault(nextParams.styleDefault, nextParams.styles);
      patch.n = Math.max(1, Math.min(nextParams.n?.default ?? 1, countMax));
    }
    updateNode(nodeId, patch);
  };

  const templateKey = String(data.imagePreset ?? data.genType ?? 'custom');

  return {
    loading,
    error,
    modelId,
    models: mergedModels.map((model) => ({ value: model.value, label: model.label })),
    onModel: setModel,
    ratios,
    ratio,
    setRatio: (value) => updateNode(nodeId, { aspectRatio: value }),
    resolutions,
    resolution,
    setResolution: (value) => updateNode(nodeId, kind === VIDEO_GENERATION_KIND ? { resolution: value } : { size: value }),
    durations,
    duration,
    setDuration: (value) => updateNode(nodeId, { duration: value }),
    countMax,
    countDefault,
    count,
    autoCount,
    setCount,
    toggleAutoCount: () => {
      if (autoCount) setCount(lastManualCount);
      else updateNode(nodeId, { n: 0 });
    },
    templateKey,
    setTemplate: (value) => updateNode(nodeId, { imagePreset: value }),
    specFields,
  };
}

const RATIO_GLYPHS: Record<string, { width: number; height: number }> = {
  '16:9': { width: 18, height: 10 },
  '9:16': { width: 10, height: 18 },
  '1:1': { width: 14, height: 14 },
  '4:3': { width: 16, height: 12 },
  '3:4': { width: 12, height: 16 },
  '21:9': { width: 20, height: 9 },
};

function RatioGlyph({ ratio }: { ratio: string }) {
  const glyph = RATIO_GLYPHS[ratio];
  return glyph
    ? <i className="gen-ratio-glyph" style={{ width: glyph.width, height: glyph.height }} aria-hidden="true" />
    : <i className="gen-ratio-glyph gen-ratio-glyph--text" aria-hidden="true">{ratio}</i>;
}

/** 参数浮层：时长滑块 / 分辨率 / 宽高比 / 数量 / 提示词模板 / 其余网关字段（不含输出格式与码率档位）。 */
export function GenerationSettingsPopover({ kind, settings, showTemplate = false, showCount = false, present = true }: {
  kind: MergedGenerationKind;
  settings: GenerationSettings;
  showTemplate?: boolean;
  showCount?: boolean;
  present?: boolean;
}) {
  const durationIndex = Math.max(0, settings.durations.indexOf(settings.duration));
  return (
    <div className="gen-settings motion-presence nodrag nowheel" data-present={present} role="dialog" aria-label="生成参数设置">
      {kind === VIDEO_GENERATION_KIND && settings.durations.length > 0 && (
        <section className="gen-settings__group">
          <header><span>时长</span><b>{settings.duration}s</b></header>
          <input
            aria-label="时长"
            type="range"
            min={0}
            max={Math.max(0, settings.durations.length - 1)}
            step={1}
            value={durationIndex}
            onChange={(event) => settings.setDuration(settings.durations[Number(event.target.value)] ?? settings.duration)}
          />
          <div className="gen-settings__ticks">
            {settings.durations.map((value) => <span key={value} className={value === settings.duration ? 'is-active' : ''}>{value}s</span>)}
          </div>
        </section>
      )}

      {settings.resolutions.length > 0 && (
        <section className="gen-settings__group">
          <header><span>分辨率</span></header>
          <div className="gen-segmented" role="group" aria-label="分辨率">
            {settings.resolutions.map((value) => (
              <button key={value} type="button" className={value === settings.resolution ? 'is-active' : ''} aria-pressed={value === settings.resolution} onClick={() => settings.setResolution(value)}>
                {value}
              </button>
            ))}
          </div>
        </section>
      )}

      {settings.ratios.length > 0 && (
        <section className="gen-settings__group">
          <header><span>宽高比</span></header>
          <div className="gen-segmented gen-segmented--ratio" role="group" aria-label="宽高比">
            {settings.ratios.map((value) => (
              <button key={value} type="button" className={value === settings.ratio ? 'is-active' : ''} aria-pressed={value === settings.ratio} onClick={() => settings.setRatio(value)}>
                <RatioGlyph ratio={value} />
                <em>{value}</em>
              </button>
            ))}
          </div>
        </section>
      )}

      {showCount && kind === IMAGE_GENERATION_KIND && (
        <section className="gen-settings__group">
          <header><span>生成个数</span></header>
          <div className="gen-count-control">
            <button
              type="button"
              className={`gen-count-auto ${settings.autoCount ? 'is-active' : ''}`}
              aria-pressed={settings.autoCount}
              title="由 AI 自动决定生成张数"
              onClick={settings.toggleAutoCount}
            >
              Auto
            </button>
            <div className="gen-count-stepper">
              <button type="button" aria-label="减少生成个数" disabled={settings.autoCount || settings.count <= 1} onClick={() => settings.setCount(settings.count - 1)}>−</button>
              <input
                aria-label="生成个数"
                type="text"
                inputMode="numeric"
                pattern="[0-9]*"
                min={1}
                max={settings.countMax}
                disabled={settings.autoCount}
                value={settings.autoCount ? 'AI' : String(settings.count)}
                onChange={(event) => {
                  const next = Number(event.target.value.replace(/\D/g, ''));
                  if (Number.isFinite(next) && next > 0) settings.setCount(next);
                }}
              />
              <button type="button" aria-label="增加生成个数" disabled={settings.autoCount || settings.count >= settings.countMax} onClick={() => settings.setCount(settings.count + 1)}>+</button>
            </div>
          </div>
        </section>
      )}

      {showTemplate && kind === IMAGE_GENERATION_KIND && (
        <section className="gen-settings__group">
          <header><span>提示词模板</span></header>
          <div className="gen-settings__field">
            <select aria-label="提示词模板" value={settings.templateKey} onChange={(event) => settings.setTemplate(event.target.value)}>
              {IMAGE_GEN_TYPES.map((type) => <option key={type.key} value={type.key}>{type.label}</option>)}
            </select>
          </div>
        </section>
      )}

      {settings.specFields.length > 0 && (
        <section className="gen-settings__group">
          <header><span>模型参数</span></header>
          <div className="gen-settings__fields">
            {settings.specFields.map(({ field, label, value, options, set }) => {
              if (field.type === 'bool' || field.type === 'boolean' || typeof value === 'boolean') {
                return (
                  <label key={field.key} className="gen-switch">
                    <input aria-label={label} type="checkbox" checked={Boolean(value)} onChange={(event) => set(event.target.checked)} />
                    <span>{label}</span>
                  </label>
                );
              }
              if (options.length > 0) {
                return (
                  <label key={field.key} className="gen-settings__field">
                    <span>{label}</span>
                    <select aria-label={label} value={String(value ?? options[0] ?? '')} onChange={(event) => set(event.target.value)}>
                      {options.map((option) => <option key={option} value={option}>{option}</option>)}
                    </select>
                  </label>
                );
              }
              return (
                <label key={field.key} className="gen-settings__field">
                  <span>{label}</span>
                  <input
                    aria-label={label}
                    type={field.type === 'number' || field.type === 'range' ? 'number' : 'text'}
                    min={field.min}
                    max={field.max}
                    value={String(value ?? '')}
                    onChange={(event) => set(field.type === 'number' || field.type === 'range' ? Number(event.target.value) : event.target.value)}
                  />
                </label>
              );
            })}
          </div>
        </section>
      )}
    </div>
  );
}

/**
 * 参数行：模型下拉 + 比例 / 分辨率 / 时长(或批量数量) 独立 chips，右侧为发送区。
 * 每个 chip 都打开同一个参数浮层（对齐参考的 selected-settings 行）。
 */
export function GenerationSettingsRow({ kind, settings, onExpand, expanded, children }: {
  kind: MergedGenerationKind;
  settings: GenerationSettings;
  onExpand: () => void;
  expanded: boolean;
  children?: ReactNode;
}) {
  const open = (event: React.MouseEvent) => { event.stopPropagation(); onExpand(); };
  return (
    <div className="gen-settings-row nodrag">
      <div className="gen-settings-row__chips">
        <select
          className="gen-chip gen-chip--model"
          aria-label="模型"
          value={settings.modelId}
          disabled={settings.loading}
          onChange={(event) => settings.onModel(event.target.value)}
        >
          {(settings.models.length ? settings.models : [{ value: settings.modelId || '', label: '默认模型' }])
            .map((model) => <option key={model.value} value={model.value}>{model.label}</option>)}
        </select>
        {settings.ratios.length > 0 && (
          <button type="button" className="gen-chip" aria-expanded={expanded} title="宽高比" onClick={open}>
            比例-{settings.ratio}
          </button>
        )}
        {settings.resolutions.length > 0 && (
          <button type="button" className="gen-chip" aria-expanded={expanded} title="分辨率" onClick={open}>
            {settings.resolution}
          </button>
        )}
        {kind === VIDEO_GENERATION_KIND && settings.durations.length > 0 && (
          <button type="button" className="gen-chip" aria-expanded={expanded} title="时长" onClick={open}>
            {settings.duration}s
          </button>
        )}
        {kind === IMAGE_GENERATION_KIND && (
          <button type="button" className="gen-chip" aria-expanded={expanded} title="生成个数" onClick={open}>
            批量 {settings.autoCount ? 'AI' : settings.count}
          </button>
        )}
      </div>
      <div className="gen-settings-row__actions">{children}</div>
      {settings.error && <span className="gen-settings-row__error">模型列表不可用：{settings.error}</span>}
    </div>
  );
}

/**
 * 生成节点的 composer（对应参考里的 bottom-toolbar）：模式 chips + 参数浮层 + 参考素材 +
 * 提示词 + 参数行 + 发送。节点本体只放预览/占位，composer 由节点用 NodeToolbar 挂到节点下方。
 */
export function GenerationComposer({
  nodeId,
  kind,
  mode,
  onModeChange,
  pool,
  onDeleteRef,
  textDraft,
  setTextDraft,
  placeholder,
  showStrip,
  settings,
  generating,
  genLabel,
  onGenerate,
  showTemplate = false,
  showCount = false,
  leading,
  body,
}: {
  nodeId: string;
  kind: MergedGenerationKind;
  mode: string;
  onModeChange: (mode: string) => void;
  pool: ReferenceImagePoolResult;
  onDeleteRef?: (entry: { number: number; sourceNodeId: string }) => void;
  textDraft: string | undefined;
  setTextDraft: (value: string) => void;
  placeholder: string;
  showStrip: boolean;
  settings: GenerationSettings;
  generating: boolean;
  genLabel: string;
  onGenerate: () => void;
  showTemplate?: boolean;
  showCount?: boolean;
  leading?: ReactNode;
  body?: ReactNode;
}) {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const settingsPresence = usePresence(settingsOpen, { duration: 140 });
  return (
    <div className="gen-composer nodrag nowheel">
      <GenerationModeChips
        kind={kind}
        mode={mode}
        onChange={onModeChange}
        onExpand={() => setSettingsOpen((value) => !value)}
      />
      {leading}
      {body ?? (
        <>
          <GenerationReferenceArea nodeId={nodeId} kind={kind} mode={mode} pool={pool} onDeleteRef={onDeleteRef} />
          <RichPromptInput
            className="rt-input--gen"
            value={textDraft ?? ''}
            onChange={setTextDraft}
            placeholder={placeholder}
            minHeight={72}
            maxHeight={0}
            pool={pool}
            showStrip={showStrip}
            onDeleteRefEntry={onDeleteRef}
          />
        </>
      )}
      {settingsPresence.mounted && (
        <GenerationSettingsPopover present={settingsPresence.present} kind={kind} settings={settings} showCount={showCount} showTemplate={showTemplate} />
      )}
      <GenerationSettingsRow
        kind={kind}
        settings={settings}
        expanded={settingsOpen}
        onExpand={() => setSettingsOpen((value) => !value)}
      >
        <GenerationSendButton generating={generating} label={genLabel} onClick={onGenerate} />
      </GenerationSettingsRow>
    </div>
  );
}

/** 圆形发送按钮（生成中为 spinner）。 */
export function GenerationSendButton({ generating, label, disabled, onClick }: {
  generating: boolean;
  label: string;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className="gen-send nodrag"
      aria-label={label}
      title={label}
      disabled={generating || disabled}
      onClick={(event) => { event.stopPropagation(); onClick(); }}
    >
      {generating ? <LoaderCircle className="is-spinning" size={15} /> : <svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true"><path d="M8 13V3M8 3l-4 4M8 3l4 4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" /></svg>}
    </button>
  );
}

export function generationModeOf(kind: string | undefined, data: CanvasNode['data']): string {
  return resolveNodeMode(kind, data) ?? '';
}
