import { useEffect, useMemo, useState } from 'react';
import { Clapperboard, Copy, Image as ImageIcon, Maximize2, Trash2, Video } from 'lucide-react';
import { operationLabel } from '../canvas/operationLabels';
import { useObjectUrl } from '../hooks/useObjectUrl';
import { getAsset } from '../services/projectStorage';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import type { CanvasNode, MediaOperation } from '../types';
import { ImagePreviewOverlay, type ImagePreviewTarget } from './ImagePreviewOverlay';

interface LineageStep {
  id: string;
  label: string;
  operationType?: MediaOperation['type'];
}

// 递归 derivedFrom，构建从当前节点一路回溯到源头的血缘链。
const buildLineage = (nodes: CanvasNode[], startId: string): LineageStep[] => {
  const chain: LineageStep[] = [];
  const visited = new Set<string>();
  let current = nodes.find((node) => node.id === startId);
  while (current && !visited.has(current.id)) {
    visited.add(current.id);
    chain.push({ id: current.id, label: current.data.label, operationType: current.data.operation?.type });
    const sourceId = current.data.derivedFrom;
    current = sourceId ? nodes.find((node) => node.id === sourceId) : undefined;
  }
  return chain;
};

const formatTime = (at?: number) => (at ? new Date(at).toLocaleString('zh-CN', { hour12: false }) : undefined);

const fieldInput = 'w-full rounded-lg border border-[var(--border-dark)] bg-[rgba(20,20,20,0.55)] px-3 text-foreground outline-none transition focus:border-[var(--acid)] focus:ring-2 focus:ring-[rgba(244,244,245,0.18)]';

function FrameAssetProperty({ assetId, label }: { assetId?: string; label: string }) {
  const asset = useMediaStore((state) => assetId ? state.assets[assetId] : undefined);
  const assetError = useMediaStore((state) => assetId ? state.assetErrors[assetId] : undefined);
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const markAssetError = useMediaStore((state) => state.markAssetError);
  const clearAssetError = useMediaStore((state) => state.clearAssetError);
  const previewUrl = useObjectUrl(
    asset?.previewBlob ?? asset?.thumbnailBlob,
    Boolean(asset),
  );

  useEffect(() => {
    if (!assetId || asset) return;
    clearAssetError(assetId);
    void getAsset(assetId).then((loaded) => {
      if (loaded) upsertAsset(loaded);
      else markAssetError(assetId, '本地资产缓存已丢失，请重新上传或重新生成');
    }).catch((error) => {
      markAssetError(assetId, error instanceof Error ? error.message : '资产加载失败');
    });
  }, [asset, assetId, clearAssetError, markAssetError, upsertAsset]);

  return (
    <section className="rounded-xl border border-[var(--border-dark)] bg-[rgba(244,244,245,0.04)] p-3">
      <header className="flex items-center justify-between gap-2">
        <span className="text-[12px] text-foreground">{label}</span>
        {asset && <small className="font-mono text-[10px] text-muted-foreground">{asset.width} × {asset.height}</small>}
      </header>
      <div className="mt-2 grid aspect-[4/3] w-full place-items-center overflow-hidden rounded-lg border border-[var(--border-dark)] bg-[rgba(16,16,16,0.72)]">
        {assetError
          ? <span className="px-3 text-center text-[11px] text-muted-foreground">{assetError}</span>
          : previewUrl
            ? <img src={previewUrl} alt={label} className="max-h-full max-w-full object-contain" />
            : <ImageIcon size={22} className="text-muted-foreground" />}
      </div>
    </section>
  );
}

function ShotInspectorFields({
  nodeId,
  data,
  updateNode,
}: {
  nodeId: string;
  data: CanvasNode['data'];
  updateNode: (nodeId: string, patch: Partial<CanvasNode['data']>) => void;
}) {
  return (
    <section className="inspector-section">
      <header className="inspector-section__head">
        <Clapperboard size={15} />
        <strong>镜头信息</strong>
      </header>
      <label className="inspector-field">
        <span>画面描述</span>
        <textarea
          aria-label="画面描述"
          rows={4}
          defaultValue={String(data.sceneDescription ?? data.prompt ?? '')}
          onBlur={(event) => updateNode(nodeId, { sceneDescription: event.target.value, prompt: event.target.value })}
        />
      </label>
      <div className="inspector-field-grid">
        <label className="inspector-field">
          <span>景别</span>
          <input
            aria-label="景别"
            defaultValue={String(data.shotType ?? '')}
            onBlur={(event) => updateNode(nodeId, { shotType: event.target.value })}
          />
        </label>
        <label className="inspector-field">
          <span>运镜</span>
          <input
            aria-label="运镜"
            defaultValue={String(data.cameraMovement ?? '')}
            onBlur={(event) => updateNode(nodeId, { cameraMovement: event.target.value })}
          />
        </label>
        <label className="inspector-field">
          <span>时长（秒）</span>
          <input
            aria-label="时长（秒）"
            type="number"
            min={1}
            defaultValue={Number(data.durationSeconds ?? 3)}
            onBlur={(event) => updateNode(nodeId, { durationSeconds: Number(event.target.value) || 1 })}
          />
        </label>
      </div>
      <label className="inspector-field">
        <span><ImageIcon size={12} />图片提示词</span>
        <textarea
          aria-label="图片提示词"
          rows={4}
          defaultValue={String(data.imagePrompt ?? '')}
          onBlur={(event) => updateNode(nodeId, { imagePrompt: event.target.value })}
        />
      </label>
      <label className="inspector-field">
        <span><Video size={12} />视频提示词</span>
        <textarea
          aria-label="视频提示词"
          rows={4}
          defaultValue={String(data.videoPrompt ?? '')}
          onBlur={(event) => updateNode(nodeId, { videoPrompt: event.target.value })}
        />
      </label>
      <label className="inspector-field">
        <span>负面提示词</span>
        <textarea
          aria-label="负面提示词"
          rows={2}
          defaultValue={String(data.negativePrompt ?? '')}
          onBlur={(event) => updateNode(nodeId, { negativePrompt: event.target.value })}
        />
      </label>
    </section>
  );
}

export function InspectorPanel() {
  const selectedNodeId = useCanvasStore((state) => state.selectedNodeId);
  const nodes = useCanvasStore((state) => state.nodes);
  const nodeData = useCanvasStore((state) => state.nodes.find((item) => item.id === state.selectedNodeId)?.data);
  const assetId = nodeData?.assetId;
  const asset = useMediaStore((state) => assetId ? state.assets[assetId] : undefined);
  const assetError = useMediaStore((state) => assetId ? state.assetErrors[assetId] : undefined);
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const markAssetError = useMediaStore((state) => state.markAssetError);
  const clearAssetError = useMediaStore((state) => state.clearAssetError);
  const previewUrl = useObjectUrl(asset?.previewBlob ?? asset?.thumbnailBlob, Boolean(asset));
  // 视频节点（或挂着视频结果的节点）在属性面板直接播放原始文件
  const isVideo = asset?.kind === 'video';
  const videoUrl = useObjectUrl(asset?.originalBlob, isVideo);
  const [previewTarget, setPreviewTarget] = useState<ImagePreviewTarget | null>(null);
  const updateNode = useCanvasStore((state) => state.updateNode);
  const duplicate = useCanvasStore((state) => state.duplicateSelected);
  const remove = useCanvasStore((state) => state.deleteSelected);
  const selectNode = useCanvasStore((state) => state.selectNode);

  const lineage = useMemo(() => (selectedNodeId ? buildLineage(nodes, selectedNodeId) : []), [nodes, selectedNodeId]);

  useEffect(() => {
    if (!assetId || asset) return;
    clearAssetError(assetId);
    void getAsset(assetId).then((loaded) => {
      if (loaded) upsertAsset(loaded);
      else markAssetError(assetId, '本地资产缓存已丢失，请重新上传或重新生成');
    }).catch((error) => {
      markAssetError(assetId, error instanceof Error ? error.message : '资产加载失败');
    });
  }, [asset, assetId, clearAssetError, markAssetError, upsertAsset]);

  useEffect(() => {
    setPreviewTarget(null);
  }, [selectedNodeId]);

  if (!selectedNodeId || !nodeData) {
    return (
      <div className="grid h-full place-items-center gap-2 p-6 text-center">
        <span className="h-8 w-8 rounded-full border border-[var(--border-dark)] bg-[rgba(244,244,245,0.06)]" />
        <h3 className="text-[15px] font-semibold text-foreground">尚未选择节点</h3>
        <p className="max-w-[240px] text-[13px] leading-relaxed text-muted-foreground">选择图片或文字节点后，可以编辑名称和说明。</p>
      </div>
    );
  }

  const params = nodeData.operation && Object.keys(nodeData.operation.params).length > 0
    ? nodeData.operation.params
    : null;

  return (
    <>
    <div className="flex flex-col gap-3 p-4" key={selectedNodeId}>
      <div className="flex items-center gap-3 rounded-xl border border-[var(--border-dark)] bg-[rgba(244,244,245,0.05)] p-3">
        {/* 节点分类不再用色相区分：统一中性点，类型靠下面的 kind 文本读 */}
        <span className="h-3 w-3 shrink-0 rounded-full bg-[rgba(244,244,245,0.6)]" />
        <div className="min-w-0">
          <small className="block font-mono text-[10px] tracking-[0.12em] text-muted-foreground">{nodeData.kind.toUpperCase()} NODE</small>
          <strong className="block truncate text-sm text-foreground">{selectedNodeId.slice(0, 22)}…</strong>
        </div>
      </div>

      <label className="flex flex-col gap-1.5 text-[13px] text-muted-foreground">节点名称
        <input defaultValue={nodeData.label} onBlur={(event) => updateNode(selectedNodeId, { label: event.target.value })} className={`h-9 ${fieldInput}`} />
      </label>
      {(isVideo || (nodeData.kind === 'video' && nodeData.assetId)) && (
        <section className="rounded-xl border border-[var(--border-dark)] bg-[rgba(244,244,245,0.04)] p-3">
          <header className="flex items-center justify-between gap-3">
            <span className="flex items-center gap-2 text-[12px] text-foreground"><Video size={14} className="text-acid" />视频预览</span>
            {asset && <small className="font-mono text-[10px] text-muted-foreground">{asset.width} × {asset.height}{asset.durationSeconds ? ` · ${asset.durationSeconds.toFixed(1)}s` : ''}</small>}
          </header>
          <div className="mt-2 overflow-hidden rounded-lg border border-[var(--border-dark)] bg-[rgba(16,16,16,0.72)]">
            {assetError ? (
              <span className="grid aspect-video w-full place-items-center px-4 text-center text-[12px] text-muted-foreground">{assetError}</span>
            ) : videoUrl ? (
              <video className="aspect-video w-full" src={videoUrl} controls playsInline preload="metadata" aria-label={`视频预览：${nodeData.label}`} />
            ) : (
              <span className="grid aspect-video w-full place-items-center text-[12px] text-muted-foreground">视频加载中…</span>
            )}
          </div>
        </section>
      )}
      {!isVideo && nodeData.kind === 'image' && nodeData.assetId && (
        <section className="rounded-xl border border-[var(--border-dark)] bg-[rgba(244,244,245,0.04)] p-3">
          <header className="flex items-center justify-between gap-3">
            <span className="flex items-center gap-2 text-[12px] text-foreground"><ImageIcon size={14} className="text-acid" />图片预览</span>
            {asset && <small className="font-mono text-[10px] text-muted-foreground">{asset.width} × {asset.height}</small>}
          </header>
          <button
            type="button"
            disabled={!previewUrl}
            aria-label={`预览图片：${nodeData.label}`}
            onClick={() => setPreviewTarget({ assetId: nodeData.assetId!, label: nodeData.label })}
            className="group relative mt-2 block w-full overflow-hidden rounded-lg border border-[var(--border-dark)] bg-[rgba(16,16,16,0.72)] disabled:cursor-wait"
          >
            {assetError ? (
              <span className="grid aspect-[4/3] w-full place-items-center px-4 text-center text-[12px] text-muted-foreground">{assetError}</span>
            ) : previewUrl ? (
              <img src={previewUrl} alt={nodeData.label} className="aspect-[4/3] w-full object-cover transition duration-200 group-hover:scale-[1.025]" />
            ) : (
              <span className="grid aspect-[4/3] w-full place-items-center text-muted-foreground">图片加载中…</span>
            )}
            <span className="absolute right-2 top-2 grid h-7 w-7 place-items-center rounded-md bg-[rgba(9,9,9,0.76)] text-acid opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100">
              <Maximize2 size={14} />
            </span>
          </button>
        </section>
      )}
      {nodeData.videoMode === 'firstLastFrame' && (
        <div className="grid grid-cols-2 gap-2">
          <FrameAssetProperty assetId={typeof nodeData.firstFrameAssetId === 'string' ? nodeData.firstFrameAssetId : undefined} label="首帧图片" />
          <FrameAssetProperty assetId={typeof nodeData.lastFrameAssetId === 'string' ? nodeData.lastFrameAssetId : undefined} label="尾帧图片" />
        </div>
      )}
      {nodeData.kind === 'shot' ? (
        <ShotInspectorFields nodeId={selectedNodeId} data={nodeData} updateNode={updateNode} />
      ) : (
        <label className="flex flex-col gap-1.5 text-[13px] text-muted-foreground">说明
          <textarea defaultValue={nodeData.prompt} rows={7} onBlur={(event) => updateNode(selectedNodeId, { prompt: event.target.value })} className={`${fieldInput} p-3`} />
        </label>
      )}

      {nodeData.operation && (
        <div className="rounded-xl border border-[var(--border-dark)] bg-[rgba(244,244,245,0.04)] p-3">
          <small className="font-mono text-[10px] tracking-[0.08em] text-muted-foreground">操作记录</small>
          <div className="mt-1 font-medium text-foreground">{operationLabel(nodeData.operation.type)}</div>
          {nodeData.operation.at && <div className="mt-0.5 text-[11px] text-muted-foreground">{formatTime(nodeData.operation.at)}</div>}
          {params && <code className="mt-2 block whitespace-pre-wrap break-all text-[11px] leading-relaxed text-muted-foreground">{JSON.stringify(params)}</code>}
        </div>
      )}

      {lineage.length > 1 && (
        <div className="rounded-xl border border-[var(--border-dark)] bg-[rgba(244,244,245,0.04)] p-3">
          <small className="font-mono text-[10px] tracking-[0.08em] text-muted-foreground">血缘链</small>
          <ol className="mt-2 list-none space-y-1.5 p-0">
            <li className="font-medium text-foreground">{lineage[0].label}</li>
            {lineage.slice(1).map((step, index) => {
              const op = operationLabel(lineage[index].operationType);
              return (
                <li key={step.id} className="flex items-center gap-2">
                  <span className="min-w-[18px] text-right font-mono text-[11px] text-muted-foreground">{op ?? '←'}</span>
                  <button type="button" onClick={() => selectNode(step.id)} className="cursor-pointer border-0 border-b border-dotted border-[var(--border-dark)] bg-transparent p-0 text-[13px] text-acid">{step.label}</button>
                </li>
              );
            })}
          </ol>
        </div>
      )}

      {nodeData.assetId && <p className="text-xs leading-relaxed text-muted-foreground">该节点引用真实本地资产。编辑操作会创建派生版本，不会覆盖原图。</p>}
      <div className="flex gap-2">
        <button onClick={duplicate} className="flex h-9 flex-1 items-center justify-center gap-2 rounded-lg bg-acid font-medium text-ink transition hover:brightness-105"><Copy size={15} /> 复制节点</button>
        <button onClick={remove} className="flex h-9 flex-1 items-center justify-center gap-2 rounded-lg border border-[rgba(138,138,138,0.4)] bg-[rgba(138,138,138,0.1)] font-medium text-destructive transition hover:bg-[rgba(138,138,138,0.18)]"><Trash2 size={15} /> 删除</button>
      </div>
    </div>
    {previewTarget && <ImagePreviewOverlay target={previewTarget} onClose={() => setPreviewTarget(null)} />}
    </>
  );
}
