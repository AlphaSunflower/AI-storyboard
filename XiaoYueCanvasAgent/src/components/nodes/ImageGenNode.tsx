import { memo, useState } from 'react';
import { NodeToolbar, Position } from '@xyflow/react';
import { Maximize2 } from 'lucide-react';
import type { CanvasNode, RedrawMaskMark } from '../../types';
import { buildReferenceImagePool } from '../../canvas/referenceImagePool';
import { submitImageGeneration, submitImageMultiAngle, submitImageRedraw } from '../../services/jobRunner';
import { useMediaStore } from '../../store/mediaStore';
import { useAssetPreviewUrl } from '../../hooks/useAssetPreviewUrl';
import { ImagePreviewOverlay } from '../ImagePreviewOverlay';
import {
  DEFAULT_IMAGE_PRESET_KEY,
  buildImagePresetPrompt,
  validateImagePresetReferences,
} from '../../canvas/imagePreset';
import {
  IMAGE_GENERATION_KIND,
  VIDEO_GENERATION_KIND,
  resolveNodeMode,
  type MergedGenerationKind,
} from '../../canvas/generationModes';
import { BatchRefineFields, useBatchRefine } from './batchRefinePanel';
import {
  GenerationComposer,
  GenerationPreviewCard,
  GenerationSendButton,
  GenerationSettingsPopover,
  GenerationSettingsRow,
  useGenerationSettings,
} from './generationPanel';

export { IMAGE_GEN_TYPES } from '../../canvas/imagePreset';

export interface GenFooterCallbacks { generating: boolean; onGenerate: () => void }

/** 图生图的源图 + 蒙版预览（保留原实现，配色交给新面板的令牌）。 */
function RedrawReferencePreview({
  assetId,
  label,
  marks = [],
}: {
  assetId: string;
  label: string;
  marks?: RedrawMaskMark[];
}) {
  const url = useAssetPreviewUrl(assetId);
  const asset = useMediaStore((state) => state.assets[assetId] ?? state.metadata[assetId]);
  const [previewOpen, setPreviewOpen] = useState(false);
  const paintMarks = marks.filter((mark) => mark.operation === 'paint');
  const aspect = asset && asset.width > 0 && asset.height > 0 ? asset.width / asset.height : 1;
  return (
    <>
      <div className="redraw-reference-preview-card" title={`${label} · ${paintMarks.length} 个区域`}>
        <button
          type="button"
          className="redraw-reference-preview-frame"
          style={{ aspectRatio: `${aspect}` }}
          aria-label={`预览图片：${label}`}
          disabled={!url}
          onClick={() => setPreviewOpen(true)}
        >
          {url ? <img src={url} alt={label} decoding="async" draggable={false} /> : <span className="reference-strip__thumb-empty" />}
          <svg className="redraw-reference-preview-mask" viewBox="0 0 1 1" preserveAspectRatio="none" aria-hidden="true">
            {paintMarks.map((mark) => {
              const color = mark.color ?? '#ff5f5f';
              if (mark.type === 'rectangle') {
                return <rect
                  key={mark.id}
                  x={Math.min(mark.start.x, mark.end.x)}
                  y={Math.min(mark.start.y, mark.end.y)}
                  width={Math.abs(mark.end.x - mark.start.x)}
                  height={Math.abs(mark.end.y - mark.start.y)}
                  fill={color}
                  fillOpacity=".22"
                  stroke={color}
                  strokeWidth=".012"
                  vectorEffect="non-scaling-stroke"
                />;
              }
              if (mark.points.length === 1) {
                return <circle
                  key={mark.id}
                  cx={mark.points[0].x}
                  cy={mark.points[0].y}
                  r={Math.max(.006, mark.width / 2)}
                  fill={color}
                  fillOpacity=".55"
                />;
              }
              return <polyline
                key={mark.id}
                points={mark.points.map((point) => `${point.x},${point.y}`).join(' ')}
                fill="none"
                stroke={color}
                strokeWidth={Math.max(.008, mark.width)}
                strokeLinecap="round"
                strokeLinejoin="round"
                opacity=".72"
              />;
            })}
          </svg>
          <span className="redraw-reference-preview-zoom" aria-hidden="true"><Maximize2 size={13} /></span>
        </button>
        <div className="redraw-reference-preview-meta">
          <strong>{label}</strong>
          <span>{paintMarks.length ? `@1 · ${paintMarks.length} 个区域` : '@1 源图片'}</span>
        </div>
      </div>
      {previewOpen && (
        <ImagePreviewOverlay target={{ assetId, label }} onClose={() => setPreviewOpen(false)} />
      )}
    </>
  );
}

/**
 * 兼容壳：重绘执行弹窗与批量完善节点继续复用「模型 + 参数 + 发送」这一段。
 * 合并后的生成节点自己组合 composer，不再走这里。
 */
export function GenFooter({
  nodeId,
  kind,
  data,
  updateNode,
  useCallbacks,
  genText,
  referenceCount = 0,
}: {
  nodeId: string;
  kind: 'imageGen' | 'videoGen';
  data: Record<string, unknown>;
  updateNode: (id: string, patch: Record<string, unknown>) => void;
  useCallbacks: GenFooterCallbacks;
  genText: string;
  referenceCount?: number;
  [key: string]: unknown;
}) {
  const [expanded, setExpanded] = useState(false);
  const mergedKind: MergedGenerationKind = kind === 'videoGen' ? VIDEO_GENERATION_KIND : IMAGE_GENERATION_KIND;
  const settings = useGenerationSettings({
    kind: mergedKind,
    nodeId,
    data: data as CanvasNode['data'],
    updateNode,
    referenceCount,
  });
  return (
    <div className="gen-footer nodrag">
      {expanded && <GenerationSettingsPopover kind={mergedKind} settings={settings} showCount showTemplate />}
      <GenerationSettingsRow kind={mergedKind} settings={settings} expanded={expanded} onExpand={() => setExpanded((value) => !value)}>
        <GenerationSendButton generating={useCallbacks.generating} label={genText} onClick={useCallbacks.onGenerate} />
      </GenerationSettingsRow>
    </div>
  );
}

/** imageGeneration 节点 panel：预览卡 + composer（模式 chips / 参考素材 / 提示词 / 设置行 / 发送）。 */
export const ImageGenNode = memo(function ImageGenNode({ id, data, updateNode, textDraft, setTextDraft, referencePool, deleteReference, draftFeedback, setDraftFeedback }: {
  id: string;
  data: CanvasNode['data'];
  /** composer 由 React Flow 默认规则显示（单选本节点时），selected 仅用于测试注入。 */
  selected?: boolean;
  updateNode: (id: string, patch: Record<string, unknown>) => void;
  textDraft: string | undefined;
  setTextDraft: (v: string) => void;
  referencePool: ReturnType<typeof buildReferenceImagePool>;
  deleteReference: (entry: { number: number; sourceNodeId: string }) => void;
  draftFeedback: string | null;
  setDraftFeedback: (msg: string | null) => void;
}) {
  const mode = resolveNodeMode(IMAGE_GENERATION_KIND, data) ?? 'text2image';
  const presetKey = String(data.imagePreset ?? data.genType ?? DEFAULT_IMAGE_PRESET_KEY);
  const multiAngle = data.multiAngle;
  const isImageToImage = mode === 'image2image';
  const isBatchRefine = mode === 'batchRefine';
  const settings = useGenerationSettings({
    kind: IMAGE_GENERATION_KIND,
    nodeId: id,
    data,
    updateNode,
    referenceCount: referencePool.count,
  });
  const batchState = useBatchRefine({
    node: { id, type: 'media', position: { x: 0, y: 0 }, data } as CanvasNode,
    updateNode,
    referencePool,
  });
  const redrawReferenceEntry = isImageToImage
    ? referencePool.getByAssetId(String(data.redraw?.sourceAssetId ?? '')) ?? referencePool.entries[0]
    : undefined;

  const runImageToImage = () => {
    if (multiAngle) {
      if (!referencePool.entries[0]) {
        setDraftFeedback('多角度节点缺少源图片');
        return;
      }
      void submitImageMultiAngle(
        String(textDraft ?? data.prompt ?? ''),
        id,
        multiAngle,
        setDraftFeedback,
        {
          sourceAssetIds: referencePool.assetIds ?? [],
          sourceNodeIds: referencePool.entries.map((entry) => entry.sourceNodeId),
        },
      ).catch((error) => setDraftFeedback(error instanceof Error ? error.message : '多角度提交失败'));
      return;
    }
    if (!data.redraw) {
      setDraftFeedback('该图生图节点缺少区域重绘数据，请从图片节点重新发起');
      return;
    }
    const sourceEntry = referencePool.entries[0];
    const sourceAsset = sourceEntry ? useMediaStore.getState().assets[sourceEntry.assetId] : undefined;
    if (!sourceAsset) {
      setDraftFeedback('图生图节点缺少源图片');
      return;
    }
    const gatewayParams = data.gatewayParams && typeof data.gatewayParams === 'object'
      ? data.gatewayParams as Record<string, unknown>
      : {};
    void submitImageRedraw(
      String(textDraft ?? data.prompt ?? ''),
      id,
      data.redraw,
      { width: sourceAsset.width, height: sourceAsset.height },
      setDraftFeedback,
      {
        sourceAssetIds: referencePool.assetIds ?? [],
        sourceNodeIds: referencePool.entries.map((entry) => entry.sourceNodeId),
        params: {
          ...gatewayParams,
          model: data.model,
          size: data.size,
          quality: data.quality,
          style: data.style,
          n: data.n,
        },
      },
    ).catch((error) => setDraftFeedback(error instanceof Error ? error.message : '重绘提交失败'));
  };

  const generate = () => {
    if (isBatchRefine) {
      batchState.submit();
      return;
    }
    if (isImageToImage) {
      runImageToImage();
      return;
    }
    const referenceError = validateImagePresetReferences(presetKey, referencePool.count);
    if (referenceError) {
      setDraftFeedback(referenceError);
      return;
    }
    const text = buildImagePresetPrompt(presetKey, String(textDraft ?? data.prompt ?? ''));
    const gatewayParams = data.gatewayParams && typeof data.gatewayParams === 'object'
      ? data.gatewayParams as Record<string, unknown>
      : {};
    const opts = {
      sourceAssetIds: referencePool.assetIds ?? [],
      sourceNodeIds: referencePool.entries.map((entry) => entry.sourceNodeId),
      params: {
        ...gatewayParams,
        model: data.model,
        size: data.size,
        quality: data.quality,
        style: data.style,
        aspectRatio: data.aspectRatio,
        n: data.n,
      },
    };
    void submitImageGeneration(text, id, (msg) => setDraftFeedback(msg), opts)
      .catch((error) => setDraftFeedback(error instanceof Error ? error.message : '生图提交失败'));
  };

  const genText = multiAngle ? '生成多角度' : isBatchRefine ? '批量完善图片' : isImageToImage ? '执行重绘' : '生成图片';
  const generating = isBatchRefine ? batchState.busy : Boolean(data.isGenerating);

  return (
    <div className="gen-node image-gen-view">
      <GenerationPreviewCard
        nodeId={id}
        media="image"
        generating={generating}
        progress={typeof data.progressPercent === 'number' ? data.progressPercent : undefined}
        emptyLabel="输入提示词生成图片"
      />
      <NodeToolbar
        nodeId={id}
        position={Position.Bottom}
        align="center"
        offset={14}
        className="gen-toolbar nodrag nowheel"
        onPointerDown={(event) => event.stopPropagation()}
      >
        <GenerationComposer
          nodeId={id}
          kind={IMAGE_GENERATION_KIND}
          mode={mode}
          onModeChange={(next) => updateNode(id, { mode: next })}
          pool={referencePool}
          onDeleteRef={deleteReference}
          textDraft={String(textDraft ?? data.prompt ?? '')}
          setTextDraft={setTextDraft}
          placeholder={
            multiAngle ? '多角度提示词已配置，可继续调整'
              : isImageToImage ? '区域修改意图已拼接，可继续调整'
                : '描述你想要生成的画面内容，@引用素材'
          }
          showStrip={!isImageToImage && Boolean(referencePool.count > 0)}
          settings={settings}
          generating={generating}
          genLabel={genText}
          onGenerate={generate}
          showCount
          showTemplate={mode === 'text2image' || mode === 'fullReference'}
          leading={isImageToImage && redrawReferenceEntry ? (
            <RedrawReferencePreview
              assetId={redrawReferenceEntry.assetId}
              label={redrawReferenceEntry.sourceNodeName || '源图片'}
              marks={data.redraw?.marks}
            />
          ) : undefined}
          body={isBatchRefine ? <BatchRefineFields state={batchState} referencePool={referencePool} /> : undefined}
        />
      </NodeToolbar>
      {draftFeedback && <small className="node-upload-error nodrag">{draftFeedback}</small>}
    </div>
  );
});
