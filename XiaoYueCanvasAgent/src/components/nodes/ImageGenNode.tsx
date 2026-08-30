import { memo } from 'react';
import { ImageIcon, LoaderCircle, Sparkles } from 'lucide-react';
import { RichPromptInput } from '../RichPromptInput';
import type { CanvasNode } from '../../types';
import { buildReferenceImagePool } from '../../canvas/referenceImagePool';
import { submitImageGeneration } from '../../services/jobRunner';
import { useGatewayModels, parseModelParams } from '../../hooks/useGatewayModels';

// AI 图片生成类型（对齐参考 STYLE_PRESETS，默认四视图而非经典生图）
export const IMAGE_GEN_TYPES = [
  { key: 'portrait', label: '人物四视图', prompt: '纯白背景，全身正面站立形象，双手自然下垂至身体两侧。从左到右依次生成【正脸头肩特写，全身正面视图，全身侧面视图，全身背面视图】，生成在一张图上。四格保持角色外形服装完全一致。' },
  { key: 'storyboard', label: '故事板', prompt: '故事板提示：创建一个电影制作板/视觉规划表，比例16:9，展示短片或商业广告的完整。包含清晰的镜头编号、镜头大小、运动方式、动作和情绪进展。' },
  { key: 'multi-grid', label: '多宫格', prompt: '多宫格摄影作品集：建立一致性锚点参考图0，再生成 3x5 摄影预览网格图（15个不同视角分镜），人物特征、服装、环境和光影完全一致。' },
  { key: 'custom', label: '自定义', prompt: '' },
] as const;

export interface GenFooterCallbacks { generating: boolean; onGenerate: () => void }

/** 生成节点 footer（对齐参考：模型 + 比例/尺寸 + 生成按钮）。模型列表来自网关真实数据。 */
export function GenFooter({ nodeId, kind, data, updateNode, useCallbacks, genText }: {
  nodeId: string;
  kind: 'imageGen' | 'videoGen';
  data: Record<string, unknown>;
  updateNode: (id: string, patch: Record<string, unknown>) => void;
  useCallbacks: GenFooterCallbacks;
  genText: string;
}) {
  const { models, defaultModel, loading } = useGatewayModels(kind === 'videoGen' ? 'video' : 'image');
  const modelId = typeof data.model === 'string' && data.model ? data.model : defaultModel;
  const currentModel = models.find((m) => m.value === modelId);
  const params = parseModelParams(currentModel?.params);
  const ratios = params.aspectRatios ?? (kind === 'videoGen' ? ['16:9', '9:16', '1:1'] : ['1:1', '16:9', '9:16', '3:4']);
  const sizes = (kind === 'videoGen' ? params.resolutions : params.sizes) ?? (kind === 'videoGen' ? ['720P', '1080P'] : ['1K', '2K']);
  // 时长（生视频）：读网关能力枚举，空则用与上游 clamp(4-15) 一致的兜底档
  const durations = (params.durations as number[] | undefined) ?? (kind === 'videoGen' ? [4, 5, 6, 8, 10] : []);
  // 质量：生图读网关 qualities（gpt-image-2 真支持）；生视频上游（MiniMax/Laozhang API 文档）无 quality 字段 → 不渲染
  const qualities = (params.qualities as string[] | undefined) ?? [];
  // 生成个数：生图读网关 n{min,max,default}（一次可生成多张）；生视频上游一次只产出 1 条、无 count → 不渲染
  const nRange = (params.n as { min?: number; max?: number; default?: number } | undefined) ?? {};
  const nDefault = nRange.default ?? 1;

  const onModel = (mid: string) => {
    const def = models.find((m) => m.value === mid);
    const p = parseModelParams(def?.params);
    const patch: Record<string, unknown> = { model: mid, aspectRatio: p.aspectRatios?.[0] };
    // 生视频的分辨率档存 resolution、生图尺寸档存 size——Minimax 网关只读 resolution，存 size 会被忽略导致分辨率失效
    if (kind === 'videoGen') patch.resolution = p.resolutions?.[0]; else patch.size = p.sizes?.[0];
    updateNode(nodeId, patch);
  };

  return (
    <div className="gen-footer nodrag">
      <div className="gen-footer__row">
        <select aria-label="模型" value={modelId} onChange={(e) => onModel(e.target.value)} disabled={loading}>
          {(models.length ? models : [{ value: defaultModel || '', label: '默认模型' }]).map((m) => <option key={m.value} value={m.value}>{m.label || m.value}</option>)}
        </select>
        <div className="gen-footer__selects">
          {kind === 'videoGen' && durations.length > 0 && (
            <select aria-label="时长" value={String(data.duration ?? durations[0] ?? 5)} onChange={(e) => updateNode(nodeId, { duration: Number(e.target.value) as any })}>
              {durations.map((d) => <option key={d} value={d}>{d}s</option>)}
            </select>
          )}
          <select aria-label="比例" value={String(data.aspectRatio ?? ratios[0] ?? '1:1')} onChange={(e) => updateNode(nodeId, { aspectRatio: e.target.value as any })}>
            {ratios.map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
          <select aria-label="尺寸" value={String((kind === 'videoGen' ? (data.resolution ?? data.size) : data.size) ?? sizes[0] ?? '1K')} onChange={(e) => updateNode(nodeId, kind === 'videoGen' ? { resolution: e.target.value as any } : { size: e.target.value as any })}>
            {sizes.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
          {kind === 'imageGen' && qualities.length > 0 && (
            <select aria-label="质量" value={String(data.quality ?? qualities[0] ?? '高清')} onChange={(e) => updateNode(nodeId, { quality: e.target.value as any })}>
              {qualities.map((q) => <option key={q} value={q}>{q}</option>)}
            </select>
          )}
          {kind === 'imageGen' && (nRange.max === undefined || nRange.max > 1) && (
            <label className="gen-footer__n nodrag">个数
              <input aria-label="生成个数" type="number" min={nRange.min ?? 1} max={nRange.max ?? 10} value={String(data.n ?? nDefault)} onChange={(e) => updateNode(nodeId, { n: Number(e.target.value) as any })} />
            </label>
          )}
        </div>
      </div>
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

/** imageGen 节点 body：生成类型 tabs + 风格 tabs + 富提示词 + 参考图 @引用 + 生成 footer。 */
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
  return (
    <div className="image-gen-view">
      <div className="gen-tabs gen-tabs--type nodrag" role="group" aria-label="生成类型">
        {IMAGE_GEN_TYPES.map((t) => <button key={t.key} className={(data.genType ?? 'portrait') === t.key ? 'is-active' : ''} title={t.prompt ? `该类型会自动拼入提示词` : ''} onClick={(e) => { e.stopPropagation(); updateNode(id, { genType: t.key }); }}>{t.label}</button>)}
      </div>
      <div className="gen-tabs nodrag" role="group" aria-label="风格">
        {['2D动漫', '3D国漫', '真人'].map((s) => <button key={s} className={(data.styleType ?? '2D动漫') === s ? 'is-active' : ''} onClick={(e) => { e.stopPropagation(); updateNode(id, { styleType: s as any }); }}>{s}</button>)}
      </div>
      <div className="gen-body">
        <RichPromptInput
          className="rt-input--gen"
          value={data.prompt ?? textDraft ?? ''}
          onChange={(v) => { setTextDraft(v); updateNode(id, { prompt: v }); }}
          placeholder="描述你想要生成的画面内容，@引用素材"
          minHeight={80}
          maxHeight={0}
          pool={referencePool}
          showStrip={Boolean(referencePool.count > 0)}
          onDeleteRefEntry={deleteReference}
        />
        {!referencePool.count && <div className="image-gen-empty"><ImageIcon size={26} /><strong>AI 图片节点</strong><span>连接上方输入节点作为参考图</span></div>}
      </div>
      <GenFooter nodeId={id} kind="imageGen" data={data} updateNode={updateNode} genText="生成图片" useCallbacks={{ generating: Boolean(data.isGenerating), onGenerate: () => { const text = String(data.prompt ?? textDraft ?? ''); const opts = { sourceAssetIds: referencePool.assetIds ?? [], params: { model: data.model, size: data.size, quality: data.quality, aspectRatio: data.aspectRatio, genType: data.genType, n: data.n } }; void submitImageGeneration(text, id, (msg) => setDraftFeedback(msg), opts).catch((error) => setDraftFeedback(error instanceof Error ? error.message : '生图提交失败')); } }} />
      {draftFeedback && <small className="node-upload-error nodrag">{draftFeedback}</small>}
    </div>
  );
});
