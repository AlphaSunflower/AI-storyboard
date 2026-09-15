import { memo, useState } from 'react';
import { ImageIcon, LoaderCircle, Sparkles } from 'lucide-react';
import { RichPromptInput } from '../RichPromptInput';
import type { CanvasNode } from '../../types';
import { buildReferenceImagePool } from '../../canvas/referenceImagePool';
import { submitImageGeneration } from '../../services/jobRunner';
import { useGatewayModels, normalizeModelDefault, parseModelParams, type ModelParamSpecField, type ModelParams } from '../../hooks/useGatewayModels';
import {
  DEFAULT_IMAGE_PRESET_KEY,
  buildImagePresetPrompt,
  IMAGE_GEN_TYPES,
  validateImagePresetReferences,
} from '../../canvas/imagePreset';

export { IMAGE_GEN_TYPES } from '../../canvas/imagePreset';

export interface GenFooterCallbacks { generating: boolean; onGenerate: () => void }

const PARAM_LABELS: Record<string, string> = {
  size: '尺寸',
  quality: '质量',
  style: '风格',
  aspect_ratio: '比例',
  aspectRatio: '比例',
  n: '个数',
  response_format: '响应格式',
  prompt_optimizer: '提示词优化',
  seed: '随机种子',
  negative_prompt: '负面提示词',
  steps: '采样步数',
  guidance_scale: '引导强度',
  strength: '参考强度',
  watermark: '水印',
  duration: '时长',
  resolution: '清晰度',
};

const LEGACY_KEYS: Record<string, string> = {
  aspect_ratio: 'aspectRatio',
  aspectRatio: 'aspectRatio',
  size: 'size',
  quality: 'quality',
  style: 'style',
  n: 'n',
  duration: 'duration',
  resolution: 'resolution',
};

const fieldOptions = (key: string, params: ModelParams): string[] => {
  if (key === 'size') return params.sizes ?? [];
  if (key === 'quality') return params.qualities ?? [];
  if (key === 'style') return params.styles ?? [];
  if (key === 'aspect_ratio' || key === 'aspectRatio') return params.aspectRatios ?? [];
  if (key === 'resolution') return params.resolutions ?? [];
  if (key === 'duration') return (params.durations ?? []).map(String);
  if (key === 'response_format') return ['url', 'b64_json'];
  return [];
};

const fieldLabel = (field: ModelParamSpecField) => PARAM_LABELS[field.key] ?? field.label ?? field.key;

/** 生成节点 footer（对齐参考：模型 + 比例/尺寸 + 生成按钮）。模型列表来自网关真实数据。 */
export function GenFooter({ nodeId, kind, data, updateNode, useCallbacks, genText, referenceCount = 0 }: {
  nodeId: string;
  kind: 'imageGen' | 'videoGen';
  data: Record<string, unknown>;
  updateNode: (id: string, patch: Record<string, unknown>) => void;
  useCallbacks: GenFooterCallbacks;
  genText: string;
  referenceCount?: number;
}) {
  const { models, defaultModel, loading, error: modelError } = useGatewayModels(kind === 'videoGen' ? 'video' : 'image');
  const modelId = typeof data.model === 'string' && data.model ? data.model : defaultModel;
  const currentModel = models.find((m) => m.value === modelId);
  const params = parseModelParams(currentModel?.params);
  const ratios = params.aspectRatios ?? (kind === 'videoGen' ? ['16:9', '9:16', '1:1'] : ['1:1', '16:9', '9:16', '3:4']);
  const sizes = (kind === 'videoGen' ? params.resolutions : params.sizes) ?? (kind === 'videoGen' ? ['720P', '1080P'] : ['1K', '2K']);
  const defaultRatio = normalizeModelDefault(params.aspectRatioDefault, ratios) ?? '1:1';
  const defaultSize = normalizeModelDefault(params.sizeDefault, sizes) ?? '1K';
  // 时长（生视频）：读网关能力枚举，空则用与上游 clamp(4-15) 一致的兜底档
  const durations = (params.durations as number[] | undefined) ?? (kind === 'videoGen' ? [4, 5, 6, 8, 10] : []);
  // 质量：生图读网关 qualities（gpt-image-2 真支持）；生视频上游（MiniMax/Laozhang API 文档）无 quality 字段 → 不渲染
  const qualities = (params.qualities as string[] | undefined) ?? [];
  const styles = params.styles ?? [];
  const defaultQuality = normalizeModelDefault(params.qualityDefault, qualities) ?? '';
  const defaultStyle = normalizeModelDefault(params.styleDefault, styles) ?? '';
  // 生成个数：生图读网关 n{min,max,default}（一次可生成多张）；生视频上游一次只产出 1 条、无 count → 不渲染
  const nRange = (params.n as { min?: number; max?: number; default?: number } | undefined) ?? {};
  const seedreamMax = Math.max(1, 15 - Math.max(0, referenceCount));
  const maxN = Math.max(1, Math.min(nRange.max ?? seedreamMax, seedreamMax));
  const nDefault = Math.max(1, Math.min(nRange.default ?? 1, maxN));
  const autoCount = kind === 'imageGen' && Number(data.n) === 0;
  const rawN = typeof data.n === 'number' ? data.n : nDefault;
  const manualN = Math.max(1, Math.min(rawN, maxN));
  const [lastManualN, setLastManualN] = useState(() => autoCount ? nDefault : manualN);
  const setManualCount = (next: number) => {
    const value = Math.max(1, Math.min(next, maxN));
    setLastManualN(value);
    updateNode(nodeId, { n: value });
  };
  const specFields = (params.spec?.request ?? []).filter((field) => field.show !== false);
  const specKeys = new Set(specFields.map((field) => field.key));
  const gatewayParams = data.gatewayParams && typeof data.gatewayParams === 'object'
    ? data.gatewayParams as Record<string, unknown>
    : {};

  const legacyValue = (key: string): unknown => {
    const legacyKey = LEGACY_KEYS[key];
    return legacyKey ? data[legacyKey] : undefined;
  };

  const fieldValue = (field: ModelParamSpecField): unknown => {
    if (gatewayParams[field.key] !== undefined) return gatewayParams[field.key];
    const legacy = legacyValue(field.key);
    if (legacy !== undefined) return legacy;
    if (field.default !== undefined) return field.default;
    const options = fieldOptions(field.key, params);
    return options[0];
  };

  const setSpecField = (field: ModelParamSpecField, value: unknown) => {
    const patch: Record<string, unknown> = {
      gatewayParams: { ...gatewayParams, [field.key]: value },
    };
    const legacyKey = LEGACY_KEYS[field.key];
    if (legacyKey) patch[legacyKey] = value;
    updateNode(nodeId, patch);
  };

  const onModel = (mid: string) => {
    const def = models.find((m) => m.value === mid);
    const p = parseModelParams(def?.params);
    const nextSpecFields = (p.spec?.request ?? []).filter((field) => field.show !== false);
    const patch: Record<string, unknown> = { model: mid, aspectRatio: p.aspectRatios?.[0] };
    // 生视频的分辨率档存 resolution、生图尺寸档存 size——Minimax 网关只读 resolution，存 size 会被忽略导致分辨率失效
    if (kind === 'videoGen') patch.resolution = p.resolutions?.[0];
    else {
      patch.size = normalizeModelDefault(p.sizeDefault, p.sizes);
      patch.quality = normalizeModelDefault(p.qualityDefault, p.qualities);
      patch.style = normalizeModelDefault(p.styleDefault, p.styles);
      patch.n = Math.max(1, Math.min(p.n?.default ?? 1, maxN));
    }
    patch.gatewayParams = Object.fromEntries(nextSpecFields.map((field) => {
      const options = fieldOptions(field.key, p);
      return [field.key, field.default ?? options[0]];
    }));
    updateNode(nodeId, patch);
  };

  return (
    <div className="gen-footer nodrag">
      <div className="gen-footer__row">
        <select aria-label="模型" value={modelId} onChange={(e) => onModel(e.target.value)} disabled={loading}>
          {(models.length ? models : [{ value: defaultModel || '', label: '默认模型' }]).map((m) => <option key={m.value} value={m.value}>{m.label || m.value}</option>)}
        </select>
        <div className="gen-footer__selects">
          {kind === 'videoGen' && durations.length > 0 && !specKeys.has('duration') && (
            <select aria-label="时长" value={String(data.duration ?? durations[0] ?? 5)} onChange={(e) => updateNode(nodeId, { duration: Number(e.target.value) as any })}>
              {durations.map((d) => <option key={d} value={d}>{d}s</option>)}
            </select>
          )}
          {!specKeys.has('aspect_ratio') && !specKeys.has('aspectRatio') && (
            <select aria-label="比例" value={String(data.aspectRatio ?? defaultRatio)} onChange={(e) => updateNode(nodeId, { aspectRatio: e.target.value as any })}>
              {ratios.map((r) => <option key={r} value={r}>{r}</option>)}
            </select>
          )}
          {!specKeys.has('size') && !specKeys.has('resolution') && (
            <select aria-label="尺寸" value={String((kind === 'videoGen' ? (data.resolution ?? data.size) : data.size) ?? defaultSize)} onChange={(e) => updateNode(nodeId, kind === 'videoGen' ? { resolution: e.target.value as any } : { size: e.target.value as any })}>
              {sizes.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          )}
          {kind === 'imageGen' && qualities.length > 0 && !specKeys.has('quality') && (
            <select aria-label="质量" value={String(data.quality ?? defaultQuality)} onChange={(e) => updateNode(nodeId, { quality: e.target.value as any })}>
              {qualities.map((q) => <option key={q} value={q}>{q}</option>)}
            </select>
          )}
          {kind === 'imageGen' && styles.length > 0 && !specKeys.has('style') && (
            <select aria-label="风格" value={String(data.style ?? defaultStyle)} onChange={(e) => updateNode(nodeId, { style: e.target.value })}>
              {styles.map((style) => <option key={style} value={style}>{style}</option>)}
            </select>
          )}
          {kind === 'imageGen' && !specKeys.has('n') && (
            <div className="gen-count-control nodrag">
              <button
                type="button"
                className={`gen-count-auto ${autoCount ? 'is-active' : ''}`}
                aria-pressed={autoCount}
                title="由 AI 自动决定生成张数"
                onClick={() => {
                  if (autoCount) {
                    setManualCount(lastManualN);
                  } else {
                    setLastManualN(manualN);
                    updateNode(nodeId, { n: 0 });
                  }
                }}
              >
                Auto
              </button>
              <div className="gen-count-stepper">
                <button
                  type="button"
                  aria-label="减少生成个数"
                  disabled={autoCount || manualN <= 1}
                  onClick={() => setManualCount(manualN - 1)}
                >
                  −
                </button>
                <input
                  aria-label="生成个数"
                  type="text"
                  inputMode="numeric"
                  pattern="[0-9]*"
                  min={1}
                  max={maxN}
                  disabled={autoCount}
                  value={autoCount ? 'AI' : String(manualN)}
                  onChange={(event) => {
                    const next = Number(event.target.value.replace(/\D/g, ''));
                    if (Number.isFinite(next) && next > 0) setManualCount(next);
                  }}
                />
                <button
                  type="button"
                  aria-label="增加生成个数"
                  disabled={autoCount || manualN >= maxN}
                  onClick={() => setManualCount(manualN + 1)}
                >
                  +
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
      {modelError && (
        <div className="gen-footer__notice" role="status">
          模型列表不可用：{modelError}
        </div>
      )}
      {specFields.length > 0 && (
        <div className="gen-footer__spec">
          {specFields.map((field) => {
            const label = fieldLabel(field);
            const value = fieldValue(field);
            const options = fieldOptions(field.key, params);
            if (field.type === 'bool' || field.type === 'boolean') {
              return (
                <label key={field.key} className="gen-footer__spec-check">
                  <input
                    aria-label={label}
                    type="checkbox"
                    checked={Boolean(value)}
                    onChange={(event) => setSpecField(field, event.target.checked)}
                  />
                  <span>{label}</span>
                </label>
              );
            }
            if (options.length > 0) {
              return (
                <label key={field.key} className="gen-footer__spec-field">
                  <span>{label}</span>
                  <select
                    aria-label={label}
                    value={String(value ?? options[0] ?? '')}
                    onChange={(event) => setSpecField(field, event.target.value)}
                  >
                    {options.map((option) => <option key={option} value={option}>{option}</option>)}
                  </select>
                </label>
              );
            }
            return (
              <label key={field.key} className="gen-footer__spec-field">
                <span>{label}</span>
                <input
                  aria-label={label}
                  type={field.type === 'number' || field.type === 'range' ? 'number' : 'text'}
                  min={field.min}
                  max={field.max}
                  value={String(value ?? '')}
                  onChange={(event) => setSpecField(field, field.type === 'number' || field.type === 'range' ? Number(event.target.value) : event.target.value)}
                />
              </label>
            );
          })}
        </div>
      )}
      {kind === 'imageGen' && (params.refImages || params.maxPromptChars) && (
        <div className="gen-footer__constraints">
          {params.refImages && <span>参考图 {params.refImages.min ?? 0}-{params.refImages.max ?? '∞'}</span>}
          {params.maxPromptChars && <span>提示词 ≤ {params.maxPromptChars}</span>}
        </div>
      )}
      <button
        className="node-upload node-upload--generate node-upload--footer nodrag"
        disabled={useCallbacks.generating}
        onClick={(e) => { e.stopPropagation(); useCallbacks.onGenerate(); }}
      >
        {useCallbacks.generating ? <LoaderCircle className="is-spinning" size={14} /> : <Sparkles size={14} />}
        {useCallbacks.generating ? '生成中…' : genText}
      </button>
      {useCallbacks.generating && typeof data.progressPercent === 'number' && data.progressPercent > 0 && (
        <span className="gen-footer__progress nodrag">{data.progressPercent}%</span>
      )}
    </div>
  );
}

/** imageGen 节点 body：生成类型 tabs + 富提示词 + 参考图 @引用 + 生成 footer。 */
export const ImageGenNode = memo(function ImageGenNode({ id, data, updateNode, textDraft, setTextDraft, referencePool, deleteReference, draftFeedback, setDraftFeedback }: {
  id: string;
  data: CanvasNode['data'];
  updateNode: (id: string, patch: Record<string, unknown>) => void;
  textDraft: string | undefined;
  setTextDraft: (v: string) => void;
  referencePool: ReturnType<typeof buildReferenceImagePool>;
  deleteReference: (entry: { number: number; sourceNodeId: string }) => void;
  draftFeedback: string | null;
  setDraftFeedback: (msg: string | null) => void;
}) {
  const presetKey = String(data.imagePreset ?? data.genType ?? DEFAULT_IMAGE_PRESET_KEY);
  return (
    <div className="image-gen-view">
      <div className="gen-tabs gen-tabs--type nodrag" role="group" aria-label="生成类型">
        {IMAGE_GEN_TYPES.map((t) => <button key={t.key} className={presetKey === t.key ? 'is-active' : ''} title={t.prompt ? `该类型会自动拼入提示词` : ''} onClick={(e) => { e.stopPropagation(); updateNode(id, { imagePreset: t.key }); }}>{t.label}</button>)}
      </div>
      <div className="gen-body">
        <RichPromptInput
          className="rt-input--gen"
          value={textDraft ?? data.prompt ?? ''}
          onChange={setTextDraft}
          placeholder="描述你想要生成的画面内容，@引用素材"
          minHeight={80}
          maxHeight={0}
          pool={referencePool}
          showStrip={Boolean(referencePool.count > 0)}
          onDeleteRefEntry={deleteReference}
        />
        {!referencePool.count && <div className="image-gen-empty"><ImageIcon size={26} /><strong>AI 图片节点</strong><span>连接上方输入节点作为参考图</span></div>}
      </div>
        <GenFooter nodeId={id} kind="imageGen" data={data} updateNode={updateNode} referenceCount={referencePool.count} genText="生成图片" useCallbacks={{ generating: Boolean(data.isGenerating), onGenerate: () => { const referenceError = validateImagePresetReferences(presetKey, referencePool.count); if (referenceError) { setDraftFeedback(referenceError); return; } const text = buildImagePresetPrompt(presetKey, String(textDraft ?? data.prompt ?? '')); const gatewayParams = data.gatewayParams && typeof data.gatewayParams === 'object' ? data.gatewayParams as Record<string, unknown> : {}; const opts = { sourceAssetIds: referencePool.assetIds ?? [], sourceNodeIds: referencePool.entries.map((entry) => entry.sourceNodeId), params: { ...gatewayParams, model: data.model, size: data.size, quality: data.quality, style: data.style, aspectRatio: data.aspectRatio, n: data.n } }; void submitImageGeneration(text, id, (msg) => setDraftFeedback(msg), opts).catch((error) => setDraftFeedback(error instanceof Error ? error.message : '生图提交失败')); } }} />
      {draftFeedback && <small className="node-upload-error nodrag">{draftFeedback}</small>}
    </div>
  );
});
