import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { Handle, Position, useStore, type NodeProps } from '@xyflow/react';
import { Camera, Download, FolderPlus, Maximize2, Pause, Play, Plus, Pin, Search, Star, Upload, Volume2, VolumeX, X } from 'lucide-react';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { getAsset, getAssetVariant, saveAsset, saveAssetToLibrary } from '../services/projectStorage';
import { useObjectUrl } from '../hooks/useObjectUrl';
import { useViewportVisibility } from '../hooks/useViewportVisibility';
import type { CanvasNode, VideoClip, VideoGenerationParams } from '../types';
import { createImageAsset, createVideoAsset, getMediaNodeSize, getVideoNodeSize } from '../utils/mediaPipeline';
import { createId } from '../utils/id';

const defaultParams: VideoGenerationParams = { model: 'Seedance 2.0 Mini', mode: '全能参考', aspectRatio: '16:9', resolution: '1080p', durationSeconds: 12, variations: 1 };

const downloadBlob = (blob: Blob, name: string) => {
  const url = URL.createObjectURL(blob); const anchor = document.createElement('a');
  anchor.href = url; anchor.download = name; document.body.appendChild(anchor); anchor.click(); anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
};

function VideoNodeComponent({ id, data, selected, width, height, positionAbsoluteX, positionAbsoluteY }: NodeProps<CanvasNode>) {
  const updateNode = useCanvasStore((state) => state.updateNode);
  const setNodePinned = useCanvasStore((state) => state.setNodePinned);
  const createDerivedNodes = useCanvasStore((state) => state.createDerivedNodes);
  const canvasNodes = useCanvasStore((state) => state.nodes);
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
  const [activePanel, setActivePanel] = useState<'prompt' | 'settings' | 'playlist'>('prompt');
  const [showReferences, setShowReferences] = useState(false);
  const [promptDraft, setPromptDraft] = useState(data.videoPrompt ?? data.prompt);
  const params = data.videoParams ?? defaultParams;
  const references = data.videoReferences ?? [];
  const playlist = data.playlist ?? [];
  const playlistEnabled = Boolean(data.playlistEnabled || playlist.length);
  const videoBlob = asset?.originalBlob ?? lazyBlob;
  const videoUrl = useObjectUrl(videoBlob, visibility.visible);
  const posterUrl = useObjectUrl(asset?.previewBlob, visibility.visible);
  const zoom = useStore((state) => state.transform[2]);
  const viewportX = useStore((state) => state.transform[0]);
  const viewportY = useStore((state) => state.transform[1]);
  const canvasWidth = useStore((state) => state.width);
  const canvasHeight = useStore((state) => state.height);
  const inverseZoom = 1 / zoom;

  const floatingWidth = Math.min(430, Math.max(280, canvasWidth - 24));
  const nodeCenterX = viewportX + (positionAbsoluteX + (width ?? 420) / 2) * zoom;
  const unclampedLeft = nodeCenterX - floatingWidth / 2;
  const clampedLeft = Math.min(Math.max(12, unclampedLeft), Math.max(12, canvasWidth - floatingWidth - 12));
  const floatingShift = (clampedLeft - unclampedLeft) / zoom;
  const nodeScreenTop = viewportY + positionAbsoluteY * zoom;
  const nodeScreenBottom = nodeScreenTop + (height ?? 236) * zoom;
  const panelScreenHeight = activePanel === 'prompt' ? (showReferences ? 350 : 245) : activePanel === 'settings' ? 180 : 118;
  const topSafeArea = 84;
  const bottomSafeArea = canvasWidth <= 720 ? 148 : 88;
  const toolbarGap = 12;
  const panelGap = 62;
  const spaceAbove = Math.max(0, nodeScreenTop - panelGap - topSafeArea);
  const spaceBelow = Math.max(0, canvasHeight - bottomSafeArea - nodeScreenBottom - panelGap);
  const floatAbove = spaceAbove >= panelScreenHeight || (spaceAbove > spaceBelow && spaceBelow < panelScreenHeight);
  const panelMaxHeight = Math.max(92, Math.min(panelScreenHeight, floatAbove ? spaceAbove : spaceBelow));
  const floatingStyle = {
    '--inverse-zoom': inverseZoom,
    '--floating-shift': `${floatingShift}px`,
    '--video-floating-width': `${floatingWidth}px`,
    '--video-toolbar-gap': `${toolbarGap / zoom}px`,
    '--video-panel-gap': `${panelGap / zoom}px`,
    '--video-panel-max-height': `${panelMaxHeight}px`,
  } as React.CSSProperties;

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
  useEffect(() => { setPromptDraft(data.videoPrompt ?? data.prompt); }, [data.prompt, data.videoPrompt, id]);

  const imageReferences = useMemo(() => canvasNodes.filter((candidate) => candidate.id !== id && candidate.data.kind === 'image' && candidate.data.assetId), [canvasNodes, id]);
  const updateParams = (patch: Partial<VideoGenerationParams>) => updateNode(id, { videoParams: { ...params, ...patch } });
  const toggleReference = (referenceId: string) => updateNode(id, { videoReferences: references.includes(referenceId) ? references.filter((item) => item !== referenceId) : [...references, referenceId] });
  const addClip = () => {
    const clip: VideoClip = { id: createId(), assetId: data.assetId ?? '', startSeconds: 0, endSeconds: duration || params.durationSeconds };
    updateNode(id, { playlistEnabled: true, playlist: [...playlist, clip] });
  };
  const openPlaylist = () => {
    if (!playlistEnabled) updateNode(id, { playlistEnabled: true, playlist: [] });
    setActivePanel('playlist');
  };
  const playToggle = () => {
    if (!videoRef.current) return;
    if (videoRef.current.paused) { void videoRef.current.play(); setPlaying(true); } else { videoRef.current.pause(); setPlaying(false); }
  };
  const seek = (next: number) => { if (videoRef.current) videoRef.current.currentTime = next; setTime(next); };
  const noticeUnavailable = () => window.dispatchEvent(new CustomEvent('morrow:notice', { detail: '未配置视频执行服务，Generate 暂不可用' }));
  const noticeAnalysisUnavailable = () => window.dispatchEvent(new CustomEvent('morrow:notice', { detail: '视频解析需要配置执行服务' }));
  const captureFrame = async () => {
    const video = videoRef.current;
    if (!video || !video.videoWidth || !video.videoHeight) return;
    try {
      const canvas = document.createElement('canvas'); canvas.width = video.videoWidth; canvas.height = video.videoHeight;
      const context = canvas.getContext('2d'); if (!context) throw new Error('浏览器无法提取视频帧');
      context.drawImage(video, 0, 0);
      const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob((result) => result ? resolve(result) : reject(new Error('视频帧编码失败')), 'image/png'));
      const frame = await createImageAsset(new File([blob], `${data.label}-${formatTime(time).replace(':', '-')}.png`, { type: 'image/png' }));
      await saveAsset(frame); upsertAsset(frame);
      createDerivedNodes(id, [{ data: { assetId: frame.id, label: `${data.label} · ${formatTime(time)} 帧` }, size: getMediaNodeSize(frame.width, frame.height) }]);
      window.dispatchEvent(new CustomEvent('morrow:notice', { detail: '已创建当前帧图片节点' }));
    } catch (error) { window.dispatchEvent(new CustomEvent('morrow:notice', { detail: error instanceof Error ? error.message : '提取视频帧失败' })); }
  };

  return <article ref={visibility.ref} className={`media-node media-node--video ${selected ? 'is-selected' : ''}`}>
    <div className="node-floating-title"><span className="video-node-dot" />{selected ? <input className="nodrag" aria-label="视频节点标题" key={data.label} defaultValue={data.label} onBlur={(event) => updateNode(id, { label: event.target.value })} /> : <span>{data.label}</span>}</div>
    <div className="node-card video-node-card">
      {!metadata ? <div className="empty-image-node"><span className="video-empty-glyph">▶</span><strong>空视频节点</strong><span>导入真实视频后可播放和编排</span></div> : !videoUrl ? <div className="media-placeholder"><span>正在加载视频…</span></div> : <div className="video-player-shell">
        <video ref={videoRef} src={videoUrl} muted={muted} playsInline preload="metadata" poster={posterUrl ?? undefined} onLoadedMetadata={(event) => setDuration(event.currentTarget.duration || metadata.durationSeconds || 0)} onTimeUpdate={(event) => setTime(event.currentTarget.currentTime)} onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)} />
        <div className="video-gradient" />
        <button className="video-big-play nodrag" onClick={playToggle} aria-label={playing ? '暂停' : '播放'}>{playing ? <Pause size={22} /> : <Play size={22} fill="currentColor" />}</button>
        <div className="video-controls nodrag" onPointerDown={(event) => event.stopPropagation()}><button onClick={playToggle} aria-label={playing ? '暂停' : '播放'}>{playing ? <Pause size={15} /> : <Play size={15} fill="currentColor" />}</button><span>{formatTime(time)}</span><input aria-label="视频进度" type="range" min="0" max={duration || 1} step="0.01" value={Math.min(time, duration || 1)} onChange={(event) => seek(Number(event.target.value))} /><span>{formatTime(duration || metadata.durationSeconds || 0)}</span><button onClick={() => setMuted((value) => !value)} aria-label={muted ? '打开声音' : '静音'}>{muted ? <VolumeX size={15} /> : <Volume2 size={15} />}</button></div>
      </div>}
    </div>
    {selected && !metadata && <><input ref={fileInputRef} className="canvas-file-input nodrag" type="file" accept="video/mp4,video/webm,video/quicktime" onChange={(event) => { const file = event.target.files?.[0]; event.target.value = ''; if (!file) return; void createVideoAsset(file).then(async (nextAsset) => { await saveAsset(nextAsset); upsertAsset(nextAsset); useCanvasStore.getState().attachAssetToNode(id, { assetId: nextAsset.id, label: file.name.replace(/\.[^.]+$/, ''), size: getVideoNodeSize(nextAsset.width, nextAsset.height) }); updateNode(id, { videoParams: defaultParams, videoPrompt: '', videoReferences: [] }); }).catch((error) => window.dispatchEvent(new CustomEvent('morrow:notice', { detail: error instanceof Error ? error.message : '视频导入失败' }))); }} /><button className="node-upload nodrag" onClick={() => fileInputRef.current?.click()}><Upload size={16} /> 上传视频</button></>}
    {selected && metadata && <div className={`video-node-toolbar nodrag nowheel ${floatAbove ? 'is-above' : ''}`} style={floatingStyle} onPointerDown={(event) => event.stopPropagation()}><button title="提取当前帧" onClick={() => void captureFrame()}><Camera size={16} /></button><button title="保存到素材库" onClick={() => void saveAssetToLibrary(metadata.id)}><FolderPlus size={16} /></button><button title="下载视频" onClick={() => asset && downloadBlob(asset.originalBlob, asset.name)}><Download size={16} /></button><button className={data.starred ? 'is-active' : ''} title="收藏" onClick={() => updateNode(id, { starred: !data.starred })}><Star size={16} fill={data.starred ? 'currentColor' : 'none'} /></button><button className={data.pinned ? 'is-active' : ''} title="固定节点" onClick={() => setNodePinned(id, !data.pinned)}><Pin size={16} /></button><button title="解析" onClick={noticeAnalysisUnavailable}><Search size={16} /></button><button title="全屏查看" onClick={() => videoRef.current?.requestFullscreen?.()}><Maximize2 size={16} /></button><i /><button className="video-generate-button" title={`未配置视频执行服务 · ${params.variations} 个结果`} onClick={noticeUnavailable}>Generate <small>{params.variations}×</small></button></div>}
    {selected && metadata && <section className={`video-editor-panel nodrag nowheel ${floatAbove ? 'is-above' : ''} ${showReferences ? 'has-reference-picker' : ''}`} style={floatingStyle} onPointerDown={(event) => event.stopPropagation()}>
      <div className="video-editor-tabs"><button className={activePanel === 'prompt' ? 'is-active' : ''} onClick={() => setActivePanel('prompt')}>分镜提示词</button><button className={activePanel === 'settings' ? 'is-active' : ''} onClick={() => setActivePanel('settings')}>生成参数</button><button className={activePanel === 'playlist' ? 'is-active' : ''} onClick={openPlaylist}>{playlistEnabled ? 'Playlist' : '+ Playlist'}</button></div>
      {activePanel === 'settings' && <div className="video-params-grid"><label>模型<select value={params.model} onChange={(event) => updateParams({ model: event.target.value })}><option>Seedance 2.0 Mini</option><option>Seedance 2.0</option></select></label><label>模式<select value={params.mode} onChange={(event) => updateParams({ mode: event.target.value })}><option>全能参考</option><option>文本生成</option></select></label><label>画幅<select value={params.aspectRatio} onChange={(event) => updateParams({ aspectRatio: event.target.value as VideoGenerationParams['aspectRatio'] })}><option>16:9</option><option>9:16</option><option>1:1</option><option>4:3</option></select></label><label>分辨率<select value={params.resolution} onChange={(event) => updateParams({ resolution: event.target.value as VideoGenerationParams['resolution'] })}><option>1080p</option><option>720p</option></select></label><label>时长<select value={params.durationSeconds} onChange={(event) => updateParams({ durationSeconds: Number(event.target.value) })}><option value={5}>5s</option><option value={8}>8s</option><option value={12}>12s</option><option value={16}>16s</option></select></label><label>生成数量<select value={params.variations} onChange={(event) => updateParams({ variations: Number(event.target.value) })}><option value={1}>1×</option><option value={2}>2×</option><option value={4}>4×</option></select></label></div>}
      {activePanel === 'prompt' && <><textarea className="video-prompt-editor" value={promptDraft} placeholder="描述视频分镜、镜头运动、主体动作和时间段…" onChange={(event) => setPromptDraft(event.target.value)} onBlur={() => { if (promptDraft !== data.videoPrompt) updateNode(id, { videoPrompt: promptDraft, prompt: promptDraft }); }} /><div className="video-reference-row"><button onClick={() => setShowReferences((value) => !value)}><Plus size={14} /> 添加主体 <span>{references.length}</span></button>{references.map((referenceId) => <button key={referenceId} className="video-reference-chip" onClick={() => toggleReference(referenceId)}>{canvasNodes.find((item) => item.id === referenceId)?.data.label ?? '参考'} <X size={11} /></button>)}</div>{showReferences && <div className="video-reference-picker">{imageReferences.length ? imageReferences.map((reference) => <button key={reference.id} className={references.includes(reference.id) ? 'is-selected' : ''} onClick={() => toggleReference(reference.id)}><Search size={13} /><span>{reference.data.label}</span></button>) : <small>画布中暂无可用图片节点</small>}</div>}</>}
      {activePanel === 'playlist' && <div className="video-playlist-content"><header><strong>Playlist</strong><button onClick={addClip}><Plus size={13} /> 添加片段</button></header><div className="video-timeline"><div className="video-timeline-ruler">{[0, 10, 20, 30, 40, 50].map((mark) => <span key={mark} style={{ left: `${Math.min(100, mark / Math.max(60, duration || 60) * 100)}%` }}>{formatTime(mark)}</span>)}</div>{playlist.length ? playlist.map((clip) => <div className="video-clip" key={clip.id} style={{ width: `${Math.max(12, ((clip.endSeconds ?? duration) - clip.startSeconds) / Math.max(duration, 1) * 100)}%` }}><span>{formatTime(clip.startSeconds)}</span></div>) : <small>尚未添加片段</small>}</div></div>}
    </section>}
    <Handle className="node-handle" id="left" type="target" position={Position.Left} /><Handle className="node-handle" id="right" type="source" position={Position.Right} />
    {selected && <><button className="node-side-plus node-side-plus--left nodrag" onPointerDown={(event) => event.stopPropagation()} onClick={() => useCanvasStore.getState().extendNode(id, 'video', 'left')} aria-label="向左添加视频节点"><Plus size={18} /></button><button className="node-side-plus node-side-plus--right nodrag" onPointerDown={(event) => event.stopPropagation()} onClick={() => useCanvasStore.getState().extendNode(id, 'video', 'right')} aria-label="向右添加视频节点"><Plus size={18} /></button></>}
  </article>;
}

const formatTime = (seconds: number) => `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${Math.floor(seconds % 60).toString().padStart(2, '0')}`;
export const VideoNode = memo(VideoNodeComponent);
