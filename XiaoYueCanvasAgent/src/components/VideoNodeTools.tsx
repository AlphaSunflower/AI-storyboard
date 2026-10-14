import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { NodeToolbar, Position } from '@xyflow/react';
import {
  Download,
  Film,
  FlipHorizontal2,
  FolderPlus,
  Gauge,
  LoaderCircle,
  Maximize2,
  Palette,
  RotateCcw,
  X,
} from 'lucide-react';
import type { CanvasNode, MediaOperation } from '../types';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { persistExistingCanvasAsset } from '../services/canvasMedia';
import { processVideoRequest, type VideoProcessRequest } from '../services/videoProcessingService';
import { getAsset, saveAssetToLibrary } from '../services/projectStorage';
import {
  createImageAsset,
  createVideoAsset,
  getMediaNodeSize,
  getVideoNodeSize,
} from '../utils/mediaPipeline';
import { useObjectUrl } from '../hooks/useObjectUrl';
import { usePresence } from '../hooks/usePresence';
import { createId } from '../utils/id';
import {
  EMPTY_VIDEO_COLOR_PARAMS,
  formatFrameTimestamp,
  type VideoColorParams,
  type VideoFramePosition,
} from '../canvas/videoProcessing';

type VideoTool = 'frame' | 'color' | 'speed';

const downloadBlob = (blob: Blob, name: string) => {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
};

const operationAt = (operation: MediaOperation): MediaOperation => ({
  ...operation,
  at: Date.now(),
});

const colorFilter = (params: VideoColorParams) => [
  `brightness(${100 + params.brightness}%)`,
  `contrast(${100 + params.contrast}%)`,
  `saturate(${100 + params.saturation}%)`,
  `hue-rotate(${params.hue}deg)`,
  `sepia(${Math.max(0, params.temperature) / 180}%)`,
].join(' ');

function ToolRange({
  label,
  value,
  min,
  max,
  step = 1,
  suffix = '',
  onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step?: number;
  suffix?: string;
  onChange: (value: number) => void;
}) {
  return (
    <label className="image-range">
      <span>{label}</span>
      <input
        aria-label={label}
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        style={{ '--range-progress': `${((value - min) / (max - min)) * 100}%` } as React.CSSProperties}
        onChange={(event) => onChange(Number(event.target.value))}
      />
      <b>{Number.isInteger(value) ? value : value.toFixed(2)}{suffix}</b>
    </label>
  );
}

export function VideoNodeTools({ node, present = true }: { node: CanvasNode; present?: boolean }) {
  const asset = useMediaStore((state) => node.data.assetId ? state.assets[node.data.assetId] : undefined);
  const attachAssetToNode = useCanvasStore((state) => state.attachAssetToNode);
  const createDerivedNodes = useCanvasStore((state) => state.createDerivedNodes);
  const [activeTool, setActiveTool] = useState<VideoTool | null>(null);
  // 显示/隐藏动效：工具条随选中态进出场，三个参数面板与进度条各自进退场
  const framePanel = usePresence(activeTool === 'frame');
  const colorPanel = usePresence(activeTool === 'color');
  const speedPanel = usePresence(activeTool === 'speed');
  const [frameTime, setFrameTime] = useState(0);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [processing, setProcessing] = useState(false);
  const [progress, setProgress] = useState(0);
  const feedbackBar = usePresence(Boolean(processing || feedback), { duration: 140 });
  const [fullscreen, setFullscreen] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const lastColor = asset?.operation?.type === 'videoColor' ? asset.operation.params : undefined;
  const lastSpeed = asset?.operation?.type === 'videoSpeed' ? Number(asset.operation.params.factor) : 1;
  const appliedSpeed = asset?.operation?.type === 'videoSpeed' ? Number(asset.operation.params.factor) || 1 : 1;
  const speedBaseDuration = asset?.operation?.type === 'videoSpeed'
    ? Number(asset.operation.params.sourceDurationSeconds) || asset.durationSeconds
    : asset?.durationSeconds;
  const [color, setColor] = useState<VideoColorParams>(() => ({
    brightness: Number(lastColor?.brightness ?? 0),
    contrast: Number(lastColor?.contrast ?? 0),
    saturation: Number(lastColor?.saturation ?? 0),
    temperature: Number(lastColor?.temperature ?? 0),
    hue: Number(lastColor?.hue ?? 0),
  }));
  const [speed, setSpeed] = useState(Number.isFinite(lastSpeed) ? lastSpeed : 1);
  const fullscreenUrl = useObjectUrl(asset?.originalBlob, fullscreen);
  const previewFilter = useMemo(() => colorFilter(color), [color]);

  useEffect(() => {
    setActiveTool(null);
    setFrameTime(0);
    setColor({ ...EMPTY_VIDEO_COLOR_PARAMS });
    setSpeed(asset?.operation?.type === 'videoSpeed' ? Number(asset.operation.params.factor) || 1 : 1);
  }, [asset?.id, asset?.operation]);

  useEffect(() => {
    if (!asset) return;
    const selector = `.react-flow__node[data-id="${node.id}"] video`;
    const videos = Array.from(document.querySelectorAll<HTMLVideoElement>(selector));
    videos.forEach((video) => { video.style.filter = activeTool === 'color' ? previewFilter : ''; });
    return () => videos.forEach((video) => { video.style.filter = ''; });
  }, [activeTool, asset, node.id, previewFilter]);

  if (!asset) return null;

  const notify = (message: string) => {
    setFeedback(message);
    window.setTimeout(() => setFeedback((current) => current === message ? null : current), 2400);
  };

  const persistResult = async (
    request: VideoProcessRequest,
    operation: MediaOperation,
  ) => {
    const controller = new AbortController();
    abortRef.current = controller;
    setProcessing(true);
    setProgress(0);
    try {
      const result = await processVideoRequest(request, {
        signal: controller.signal,
        onProgress: setProgress,
      });
      const file = new File([result.blob], result.fileName, { type: result.blob.type });
      const local = operation.type === 'videoFrame'
        ? await createImageAsset(file, createId(), { derivedFrom: asset.id, operation })
        : await createVideoAsset(file, createId(), { derivedFrom: asset.id, operation });
      const persisted = await persistExistingCanvasAsset(local, node.id);
      return persisted;
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
      setProcessing(false);
      setProgress(0);
    }
  };

  const applyOverwrite = async (
    request: VideoProcessRequest,
    operation: MediaOperation,
    successMessage: string,
  ) => {
    try {
      const persisted = await persistResult(request, operationAt(operation));
      attachAssetToNode(node.id, {
        assetId: persisted.id,
        label: node.data.label,
        size: getVideoNodeSize(persisted.width, persisted.height),
        operation: persisted.operation,
      });
      setActiveTool(null);
      notify(successMessage);
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') notify('已取消视频处理');
      else notify(error instanceof Error ? error.message : '视频处理失败');
    }
  };

  const extractFrame = async (position: VideoFramePosition, detail: string) => {
    const operation: MediaOperation = {
      type: 'videoFrame',
      params: position.kind === 'custom'
        ? { kind: position.kind, timestampSeconds: position.timestampSeconds }
        : { kind: position.kind },
    };
    try {
      const persisted = await persistResult({
        type: 'extractFrame',
        source: asset.originalBlob,
        fileName: asset.name,
        position,
        durationSeconds: asset.durationSeconds,
      }, operationAt(operation));
      createDerivedNodes(node.id, [{
        data: {
          assetId: persisted.id,
          label: `${node.data.label} · ${detail}`,
          operation: persisted.operation,
        },
        size: getMediaNodeSize(persisted.width, persisted.height),
        edgeData: { operation: 'videoFrame', operationDetail: detail },
      }]);
      setActiveTool(null);
      notify(`已提取${detail}`);
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') notify('已取消视频处理');
      else notify(error instanceof Error ? error.message : '提取帧失败');
    }
  };

  const seekFramePreview = (seconds: number) => {
    setFrameTime(seconds);
    const video = document.querySelector<HTMLVideoElement>(
      `.react-flow__node[data-id="${node.id}"] video`,
    );
    if (!video) return;
    if (!video.paused) video.pause();
    video.currentTime = seconds;
  };

  const openFrameTool = () => {
    const next = activeTool === 'frame' ? null : 'frame';
    setActiveTool(next);
    if (next !== 'frame') return;
    const video = document.querySelector<HTMLVideoElement>(
      `.react-flow__node[data-id="${node.id}"] video`,
    );
    if (video && Number.isFinite(video.currentTime)) seekFramePreview(video.currentTime);
  };

  const resolveSpeedSourceAsset = async () => {
    let current = asset;
    const visited = new Set<string>();
    while (current.operation?.type !== 'videoSpeed' && current.derivedFrom && !visited.has(current.derivedFrom)) {
      visited.add(current.derivedFrom);
      const parent = await getAsset(current.derivedFrom);
      if (!parent) break;
      current = parent;
    }
    if (current.operation?.type === 'videoSpeed') {
      const sourceId = String(current.operation.params.sourceAssetId ?? current.derivedFrom ?? current.id);
      return await getAsset(sourceId) ?? current;
    }
    return asset;
  };

  return (
    <>
      <NodeToolbar
        nodeId={node.id}
        isVisible
        position={Position.Top}
        align="center"
        offset={12}
        className="image-result-toolbar video-result-toolbar motion-presence nodrag nowheel"
        data-present={present}
        onPointerDown={(event) => event.stopPropagation()}
      >
        <button className={activeTool === 'frame' ? 'is-active' : ''} disabled={processing} title="提取帧" onClick={openFrameTool}><Film size={19} /></button>
        <button disabled={processing} title="水平翻转" onClick={() => void applyOverwrite({ type: 'flip', source: asset.originalBlob, fileName: asset.name }, { type: 'videoFlip', params: {} }, '已完成水平翻转')}><FlipHorizontal2 size={19} /></button>
        <button className={activeTool === 'color' ? 'is-active' : ''} disabled={processing} title="调色" onClick={() => setActiveTool(activeTool === 'color' ? null : 'color')}><Palette size={19} /></button>
        <button className={activeTool === 'speed' ? 'is-active' : ''} disabled={processing} title="变速" onClick={() => setActiveTool(activeTool === 'speed' ? null : 'speed')}><Gauge size={19} /></button>
        <i />
        <button disabled={processing} title="保存到本地素材库" onClick={() => void saveAssetToLibrary(asset.id).then(() => notify('真实素材已保存到跨项目素材库')).catch((error) => notify(error.message))}><FolderPlus size={19} /></button>
        <button disabled={processing} title="下载当前真实文件" onClick={() => downloadBlob(asset.originalBlob, asset.name)}><Download size={19} /></button>
        <button disabled={processing} title="全屏查看" onClick={() => setFullscreen(true)}><Maximize2 size={19} /></button>
      </NodeToolbar>

      {feedbackBar.mounted && (
        <NodeToolbar nodeId={node.id} isVisible position={Position.Top} align="center" offset={52} className="image-tool-feedback video-tool-feedback motion-presence" data-present={feedbackBar.present} role="status">
          {processing && <LoaderCircle className="video-tool-spinner" size={14} />}
          <span>{processing ? `处理中 ${Math.round(progress * 100)}%` : feedback}</span>
          {processing && <button className="nodrag" onClick={() => abortRef.current?.abort()}>取消处理</button>}
        </NodeToolbar>
      )}

      {framePanel.mounted && (
        <NodeToolbar nodeId={node.id} isVisible position={Position.Bottom} align="center" offset={12} className="image-edit-panel video-frame-panel motion-presence nodrag nowheel" data-present={framePanel.present} onPointerDown={(event) => event.stopPropagation()}>
          <header><div><Film size={15} /><strong>提取视频帧</strong><small>输出全分辨率 PNG</small></div><button onClick={() => setActiveTool(null)}><X size={16} /></button></header>
          <div className="video-frame-actions">
            <button disabled={processing} onClick={() => void extractFrame({ kind: 'first' }, '首帧')}>首帧</button>
            <button disabled={processing} onClick={() => void extractFrame({ kind: 'last' }, '尾帧')}>尾帧</button>
          </div>
          <div className="video-frame-scrubber">
            <div><span>任意帧</span><b>{formatFrameTimestamp(frameTime)}</b></div>
            <input
              className="nodrag"
              aria-label="任意帧"
              type="range"
              min={0}
              max={asset.durationSeconds ?? 1}
              step={0.01}
              value={Math.min(frameTime, asset.durationSeconds ?? 1)}
              disabled={!asset.durationSeconds}
              onChange={(event) => seekFramePreview(Number(event.target.value))}
            />
            <button
              disabled={processing || !asset.durationSeconds}
              onClick={() => {
                const detail = formatFrameTimestamp(frameTime);
                void extractFrame({ kind: 'custom', timestampSeconds: frameTime }, detail);
              }}
            >
              提取任意帧
            </button>
          </div>
        </NodeToolbar>
      )}

      {colorPanel.mounted && (
        <NodeToolbar nodeId={node.id} isVisible position={Position.Bottom} align="center" offset={12} className="image-edit-panel image-edit-panel--pixels video-color-panel motion-presence nodrag nowheel" data-present={colorPanel.present} onPointerDown={(event) => event.stopPropagation()}>
          <header><div><Palette size={15} /><strong>颜色调整</strong><small>预览后覆盖当前视频</small></div><button onClick={() => setActiveTool(null)}><X size={16} /></button></header>
          <div className="pixel-controls">
            <ToolRange label="亮度" value={color.brightness} min={-100} max={100} onChange={(value) => setColor((current) => ({ ...current, brightness: value }))} />
            <ToolRange label="对比度" value={color.contrast} min={-100} max={100} onChange={(value) => setColor((current) => ({ ...current, contrast: value }))} />
            <ToolRange label="饱和度" value={color.saturation} min={-100} max={100} onChange={(value) => setColor((current) => ({ ...current, saturation: value }))} />
            <ToolRange label="色温" value={color.temperature} min={-100} max={100} onChange={(value) => setColor((current) => ({ ...current, temperature: value }))} />
            <ToolRange label="色相" value={color.hue} min={-180} max={180} suffix="°" onChange={(value) => setColor((current) => ({ ...current, hue: value }))} />
          </div>
          <footer className="image-tool-footer">
            <button className="image-tool-reset" onClick={() => setColor({ ...EMPTY_VIDEO_COLOR_PARAMS })}><RotateCcw size={13} /> 恢复默认值</button>
            <span>本地处理</span>
            <button
              className="image-tool-overwrite"
              disabled={processing || Object.values(color).every((value) => value === 0)}
              onClick={() => void applyOverwrite({ type: 'color', source: asset.originalBlob, fileName: asset.name, params: color }, { type: 'videoColor', params: { ...color } }, '已用调色结果覆盖原视频')}
            >
              覆盖原视频
            </button>
          </footer>
        </NodeToolbar>
      )}

      {speedPanel.mounted && (
        <NodeToolbar nodeId={node.id} isVisible position={Position.Bottom} align="center" offset={12} className="image-edit-panel video-speed-panel motion-presence nodrag nowheel" data-present={speedPanel.present} onPointerDown={(event) => event.stopPropagation()}>
          <header><div><Gauge size={15} /><strong>视频变速</strong><small>画面与声音同步，保持音调</small></div><button onClick={() => setActiveTool(null)}><X size={16} /></button></header>
          <div className="pixel-controls">
            <ToolRange label="倍数" value={speed} min={0.5} max={2} step={0.01} suffix="×" onChange={setSpeed} />
          </div>
          <footer className="image-tool-footer">
            <button className="image-tool-reset" onClick={() => setSpeed(1)}><RotateCcw size={13} /> 1.0×</button>
            <span>{speedBaseDuration ? `预计 ${(speedBaseDuration / speed).toFixed(1)} 秒` : '本地处理'}</span>
            <button
              className="image-tool-overwrite"
              disabled={processing || Math.abs(speed - appliedSpeed) < 0.001}
              onClick={() => void resolveSpeedSourceAsset()
                .then((sourceAsset) => applyOverwrite(
                  { type: 'speed', source: sourceAsset.originalBlob, fileName: sourceAsset.name, factor: speed },
                  {
                    type: 'videoSpeed',
                    params: {
                      factor: speed,
                      sourceAssetId: sourceAsset.id,
                      sourceDurationSeconds: sourceAsset.durationSeconds ?? 0,
                    },
                  },
                  `已按 ${speed.toFixed(2)}× 覆盖原视频`,
                ))
                .catch((error) => notify(error instanceof Error ? error.message : '无法读取原始视频'))}
            >
              覆盖原视频
            </button>
          </footer>
        </NodeToolbar>
      )}

      {fullscreen && createPortal(
        <div className="image-fullscreen nodrag nowheel" role="dialog" onClick={(event) => { if (event.target === event.currentTarget) setFullscreen(false); }}>
          {fullscreenUrl ? <video className="fullscreen-media" src={fullscreenUrl} controls autoPlay /> : <div className="fullscreen-media-loading">正在解码视频…</div>}
          <header><strong>{node.data.label}</strong><span>{asset.width} × {asset.height}</span></header>
          <button onClick={() => setFullscreen(false)} aria-label="关闭全屏查看"><X size={20} /></button>
        </div>,
        document.body,
      )}
    </>
  );
}
