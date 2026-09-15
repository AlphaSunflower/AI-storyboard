import { memo, useEffect, useRef, useState } from 'react';
import { Handle, NodeResizer, Position, type NodeProps } from '@xyflow/react';
import { Pause, Play, Upload, Volume2, VolumeX } from 'lucide-react';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { getAsset, getAssetVariant } from '../services/projectStorage';
import { useObjectUrl } from '../hooks/useObjectUrl';
import { useViewportVisibility } from '../hooks/useViewportVisibility';
import type { CanvasNode, VideoGenerationParams } from '../types';
import { getVideoNodeSize } from '../utils/mediaPipeline';
import { createPersistedCanvasAsset } from '../services/canvasMedia';

const defaultParams: VideoGenerationParams = { model: 'Seedance 2.0 Mini', mode: '全能参考', aspectRatio: '16:9', resolution: '1080p', durationSeconds: 12, variations: 1 };

function VideoNodeComponent({ id, data, selected }: NodeProps<CanvasNode>) {
  const updateNode = useCanvasStore((state) => state.updateNode);
  const updateNodeGeometry = useCanvasStore((state) => state.updateNodeGeometry);
  const asset = useMediaStore((state) => data.assetId ? state.assets[data.assetId] : undefined);
  const metadata = useMediaStore((state) => data.assetId ? state.metadata[data.assetId] : undefined);
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const visibility = useViewportVisibility<HTMLElement>(id, selected);
  const videoRef = useRef<HTMLVideoElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [lazyBlob, setLazyBlob] = useState<Blob>();
  const [playing, setPlaying] = useState(false);
  const [muted, setMuted] = useState(true);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const videoBlob = asset?.originalBlob ?? lazyBlob;
  const videoUrl = useObjectUrl(videoBlob, visibility.visible);
  const posterUrl = useObjectUrl(asset?.previewBlob, visibility.visible);

  useEffect(() => {
    if (!data.assetId || asset || !metadata || !visibility.visible) return;
    let cancelled = false;
    void getAsset(data.assetId).then((loaded) => { if (!cancelled && loaded) upsertAsset(loaded); }).catch(() => undefined);
    return () => { cancelled = true; };
  }, [asset, data.assetId, metadata, upsertAsset, visibility.visible]);
  useEffect(() => {
    if (!data.assetId || asset || !metadata || !visibility.visible) return;
    let cancelled = false;
    void getAssetVariant(data.assetId, 'original').then((blob) => { if (!cancelled && blob) setLazyBlob(blob); }).catch(() => undefined);
    return () => { cancelled = true; };
  }, [asset, data.assetId, metadata, visibility.visible]);
  useEffect(() => { setTime(0); setPlaying(false); }, [id, videoUrl]);
  const playToggle = () => {
    if (!videoRef.current) return;
    if (videoRef.current.paused) { void videoRef.current.play(); setPlaying(true); } else { videoRef.current.pause(); setPlaying(false); }
  };
  const seek = (next: number) => { if (videoRef.current) videoRef.current.currentTime = next; setTime(next); };
  return <article ref={visibility.ref} className={`media-node media-node--video ${selected ? 'is-selected' : ''}`}>
    <div className="node-floating-title node-drag-handle"><span className="video-node-dot" />{selected ? <input className="nodrag" aria-label="视频节点标题" key={data.label} defaultValue={data.label} onBlur={(event) => updateNode(id, { label: event.target.value })} /> : <span>{data.label}</span>}</div>
    <div className="node-card video-node-card">
      {!metadata ? <div className="empty-image-node"><span className="video-empty-glyph">▶</span><strong>空视频节点</strong><span>导入真实视频后可播放和编排</span></div> : !videoUrl ? <div className="media-placeholder"><span>正在加载视频…</span></div> : <div className="video-player-shell">
        <video ref={videoRef} src={videoUrl} muted={muted} playsInline preload="metadata" poster={posterUrl ?? undefined} onLoadedMetadata={(event) => setDuration(event.currentTarget.duration || metadata.durationSeconds || 0)} onTimeUpdate={(event) => setTime(event.currentTarget.currentTime)} onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)} />
        <div className="video-gradient" />
        <button className="video-big-play nodrag" onClick={playToggle} aria-label={playing ? '暂停' : '播放'}>{playing ? <Pause size={22} /> : <Play size={22} fill="currentColor" />}</button>
        <div className="video-controls nodrag" onPointerDown={(event) => event.stopPropagation()}><button onClick={playToggle} aria-label={playing ? '暂停' : '播放'}>{playing ? <Pause size={15} /> : <Play size={15} fill="currentColor" />}</button><span>{formatTime(time)}</span><input aria-label="视频进度" type="range" min="0" max={duration || 1} step="0.01" value={Math.min(time, duration || 1)} onChange={(event) => seek(Number(event.target.value))} /><span>{formatTime(duration || metadata.durationSeconds || 0)}</span><button onClick={() => setMuted((value) => !value)} aria-label={muted ? '打开声音' : '静音'}>{muted ? <VolumeX size={15} /> : <Volume2 size={15} />}</button></div>
      </div>}
    </div>
    {selected && !metadata && <><input ref={fileInputRef} className="canvas-file-input nodrag" type="file" accept="video/mp4,video/webm,video/quicktime" onChange={(event) => { const file = event.target.files?.[0]; event.target.value = ''; if (!file) return; void createPersistedCanvasAsset(file, id).then((nextAsset) => { upsertAsset(nextAsset); useCanvasStore.getState().attachAssetToNode(id, { assetId: nextAsset.id, label: file.name.replace(/\.[^.]+$/, ''), size: getVideoNodeSize(nextAsset.width, nextAsset.height) }); updateNode(id, { videoParams: defaultParams, videoPrompt: '', videoReferences: [] }); }).catch((error) => window.dispatchEvent(new CustomEvent('morrow:notice', { detail: error instanceof Error ? error.message : '视频导入失败' }))); }} /><button className="node-upload nodrag" onClick={() => fileInputRef.current?.click()}><Upload size={16} /> 上传视频</button></>}
    <Handle className="node-handle" id="origin" type="target" position={Position.Left} /><Handle className="node-handle" id="video" type="source" position={Position.Right} />
    <NodeResizer
      nodeId={id}
      isVisible={selected}
      minWidth={280}
      maxWidth={800}
      minHeight={180}
      maxHeight={800}
      color="#d7ff54"
      handleClassName="node-resizer-handle"
      lineClassName="node-resizer-line"
      onResize={(_, params) => updateNodeGeometry(id, { width: params.width, height: params.height })}
    />
  </article>;
}

const formatTime = (seconds: number) => `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${Math.floor(seconds % 60).toString().padStart(2, '0')}`;
export const VideoNode = memo(VideoNodeComponent);
