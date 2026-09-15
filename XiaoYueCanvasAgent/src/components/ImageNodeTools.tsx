import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { createPortal } from 'react-dom';
import { NodeToolbar, Position, useReactFlow } from '@xyflow/react';
import {
  ArrowUp, Crop, Download, Eraser, Expand, FolderPlus, Grid3X3, Lightbulb, Maximize2, MoreHorizontal,
  MousePointer2, MoveUpRight, PenLine, RectangleHorizontal, Rotate3D, RotateCcw, Scissors, SlidersHorizontal,
  Sparkles, Type, X,
} from 'lucide-react';
import { getAssets, saveAsset, saveAssets, saveAssetToLibrary } from '../services/projectStorage';
import { useObjectUrl } from '../hooks/useObjectUrl';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import type { AnnotationDocument, AnnotationShape, CanvasNode, Point } from '../types';
import {
  annotationToSvg, createAnnotatedImageAsset, createCroppedImageAsset, createPixelAdjustedAsset,
  createSplitImageAssets, disposeRuntimeAsset, getMediaNodeSize,
} from '../utils/mediaPipeline';
import { zipInWorker } from '../utils/archiveWorkerClient';
import { RedrawEditor } from './RedrawEditor';
import { createId } from '../utils/id';

type ImageTool = 'crop' | 'redraw' | 'annotation' | 'more' | 'pixels';
type AnnotationMode = 'select' | 'brush' | 'arrow' | 'rectangle' | 'text';
const EMPTY_ASSET_IDS: string[] = [];

const cropRatios: Record<string, number | null> = {
  原图比例: null, '1 : 1': 1, '4 : 3': 4 / 3, '3 : 4': 3 / 4, '16 : 9': 16 / 9, '9 : 16': 9 / 16,
};

function ToolRange({ label, value, min, max, step = 1, suffix = '', onChange }: { label: string; value: number; min: number; max: number; step?: number; suffix?: string; onChange: (value: number) => void }) {
  return <label className="image-range"><span>{label}</span><input type="range" min={min} max={max} step={step} value={value} style={{ '--range-progress': `${((value - min) / (max - min)) * 100}%` } as React.CSSProperties} onChange={(event) => onChange(Number(event.target.value))} /><b>{Number.isInteger(value) ? value : value.toFixed(1)}{suffix}</b></label>;
}

function CropOverlay({ crop }: { crop: { x: number; y: number; width: number; height: number } }) {
  return <div className="crop-node-overlay nodrag"><div className="crop-selection" style={{ left: `${crop.x * 100}%`, top: `${crop.y * 100}%`, width: `${crop.width * 100}%`, height: `${crop.height * 100}%` }}><i /><i /><i /><i /><span className="crop-rule crop-rule--one" /><span className="crop-rule crop-rule--two" /></div></div>;
}

const shapeBounds = (shape: AnnotationShape) => {
  if (shape.type === 'brush') {
    const xs = shape.points.map((point) => point.x); const ys = shape.points.map((point) => point.y);
    return { x: Math.min(...xs), y: Math.min(...ys), width: Math.max(...xs) - Math.min(...xs), height: Math.max(...ys) - Math.min(...ys) };
  }
  if (shape.type === 'text') return { x: shape.point.x, y: shape.point.y - shape.fontSize, width: Math.max(.08, shape.text.length * shape.fontSize * .55), height: shape.fontSize };
  return { x: Math.min(shape.start.x, shape.end.x), y: Math.min(shape.start.y, shape.end.y), width: Math.abs(shape.end.x - shape.start.x), height: Math.abs(shape.end.y - shape.start.y) };
};

const moveShape = (shape: AnnotationShape, dx: number, dy: number): AnnotationShape => {
  const clamp = (value: number) => Math.max(0, Math.min(1, value));
  if (shape.type === 'brush') return { ...shape, points: shape.points.map((point) => ({ x: clamp(point.x + dx), y: clamp(point.y + dy) })) };
  if (shape.type === 'text') return { ...shape, point: { x: clamp(shape.point.x + dx), y: clamp(shape.point.y + dy) } };
  return { ...shape, start: { x: clamp(shape.start.x + dx), y: clamp(shape.start.y + dy) }, end: { x: clamp(shape.end.x + dx), y: clamp(shape.end.y + dy) } };
};

function AnnotationOverlay({ shapes, mode, color, width, text, selectedId, onShapesChange, onGestureStart, onSelect }: {
  shapes: AnnotationShape[]; mode: AnnotationMode; color: string; width: number; text: string; selectedId: string | null;
  onShapesChange: (shapes: AnnotationShape[]) => void; onGestureStart: (shapes: AnnotationShape[]) => void; onSelect: (id: string | null) => void;
}) {
  const activeRef = useRef<AnnotationShape | null>(null);
  const dragRef = useRef<{ id: string; point: Point; original: AnnotationShape } | null>(null);
  const shapesRef = useRef(shapes);
  shapesRef.current = shapes;
  const point = (event: ReactPointerEvent<SVGSVGElement>) => { const box = event.currentTarget.getBoundingClientRect(); return { x: Math.max(0, Math.min(1, (event.clientX - box.left) / box.width)), y: Math.max(0, Math.min(1, (event.clientY - box.top) / box.height)) }; };
  const hit = (next: Point) => [...shapes].reverse().find((shape) => { const box = shapeBounds(shape); const pad = .025; return next.x >= box.x - pad && next.x <= box.x + box.width + pad && next.y >= box.y - pad && next.y <= box.y + box.height + pad; });
  const begin = (event: ReactPointerEvent<SVGSVGElement>) => {
    event.stopPropagation(); event.currentTarget.setPointerCapture(event.pointerId); const start = point(event);
    if (mode === 'select') { const target = hit(start); onSelect(target?.id ?? null); if (target) { onGestureStart(shapesRef.current); dragRef.current = { id: target.id, point: start, original: target }; } return; }
    const base = { id: createId(), color, width: width / 1000 };
    const shape: AnnotationShape = mode === 'brush' ? { ...base, type: 'brush', points: [start] } : mode === 'arrow' ? { ...base, type: 'arrow', start, end: start } : mode === 'rectangle' ? { ...base, type: 'rectangle', start, end: start } : { ...base, type: 'text', point: start, text: text.trim() || '标注', fontSize: .055 };
    onGestureStart(shapesRef.current); activeRef.current = shape; onSelect(shape.id);
    shapesRef.current = [...shapesRef.current, shape]; onShapesChange(shapesRef.current);
    if (mode === 'text') activeRef.current = null;
  };
  const move = (event: ReactPointerEvent<SVGSVGElement>) => {
    const nextPoint = point(event);
    if (dragRef.current) { const { id, point: start, original } = dragRef.current; shapesRef.current = shapesRef.current.map((shape) => shape.id === id ? moveShape(original, nextPoint.x - start.x, nextPoint.y - start.y) : shape); onShapesChange(shapesRef.current); return; }
    const active = activeRef.current; if (!active) return;
    const next: AnnotationShape = active.type === 'brush' ? { ...active, points: [...active.points, nextPoint] } : active.type === 'text' ? active : { ...active, end: nextPoint };
    activeRef.current = next; shapesRef.current = shapesRef.current.map((shape) => shape.id === next.id ? next : shape); onShapesChange(shapesRef.current);
  };
  const end = () => { activeRef.current = null; dragRef.current = null; };
  return <svg className="annotation-overlay nodrag" viewBox="0 0 1000 1000" preserveAspectRatio="none" onPointerDown={begin} onPointerMove={move} onPointerUp={end} onPointerCancel={end}>
    <defs><marker id="annotation-arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="context-stroke" /></marker></defs>
    {shapes.map((shape) => {
      const selected = shape.id === selectedId; const strokeWidth = shape.width * 1000;
      if (shape.type === 'brush') return <polyline key={shape.id} className={selected ? 'is-selected' : ''} points={shape.points.map((item) => `${item.x * 1000},${item.y * 1000}`).join(' ')} fill="none" stroke={shape.color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />;
      if (shape.type === 'rectangle') return <rect key={shape.id} className={selected ? 'is-selected' : ''} x={Math.min(shape.start.x, shape.end.x) * 1000} y={Math.min(shape.start.y, shape.end.y) * 1000} width={Math.abs(shape.end.x - shape.start.x) * 1000} height={Math.abs(shape.end.y - shape.start.y) * 1000} fill="none" stroke={shape.color} strokeWidth={strokeWidth} />;
      if (shape.type === 'arrow') return <line key={shape.id} className={selected ? 'is-selected' : ''} x1={shape.start.x * 1000} y1={shape.start.y * 1000} x2={shape.end.x * 1000} y2={shape.end.y * 1000} stroke={shape.color} strokeWidth={strokeWidth} markerEnd="url(#annotation-arrow)" />;
      return <text key={shape.id} className={selected ? 'is-selected' : ''} x={shape.point.x * 1000} y={shape.point.y * 1000} fill={shape.color} fontSize={shape.fontSize * 1000} fontWeight="600">{shape.text}</text>;
    })}
  </svg>;
}

const downloadBlob = (blob: Blob, name: string) => { const url = URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = name; document.body.appendChild(anchor); anchor.click(); anchor.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000); };

export function ImageNodeTools({ node }: { node: CanvasNode }) {
  const { fitView } = useReactFlow();
  const asset = useMediaStore((state) => node.data.assetId ? state.assets[node.data.assetId] : undefined);
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const upsertAssets = useMediaStore((state) => state.upsertAssets);
  const createDerivedNodes = useCanvasStore((state) => state.createDerivedNodes);
  const splitAssetIds = useCanvasStore((state) => state.derivedAssetIdsBySource[node.id] ?? EMPTY_ASSET_IDS);
  const [activeTool, setActiveTool] = useState<ImageTool | null>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [fullscreen, setFullscreen] = useState(false);
  const [isApplying, setIsApplying] = useState(false);
  const [cropRatio, setCropRatio] = useState('原图比例'); const [cropZoom, setCropZoom] = useState(1); const [cropPanX, setCropPanX] = useState(0); const [cropPanY, setCropPanY] = useState(0);
  const [brightness, setBrightness] = useState(0); const [contrast, setContrast] = useState(0); const [saturation, setSaturation] = useState(0);
  const [annotationMode, setAnnotationMode] = useState<AnnotationMode>('brush'); const [annotationColor, setAnnotationColor] = useState('#d7ff54'); const [annotationWidth, setAnnotationWidth] = useState(8); const [annotationText, setAnnotationText] = useState('标注');
  const [shapes, setShapes] = useState<AnnotationShape[]>(node.data.annotation?.shapes ?? []); const [shapePast, setShapePast] = useState<AnnotationShape[][]>([]); const [selectedShapeId, setSelectedShapeId] = useState<string | null>(null);
  const [splitMatrixHover, setSplitMatrixHover] = useState({ rows: 1, columns: 1 }); const [isSplitMatrixOpen, setIsSplitMatrixOpen] = useState(false);
  const splitCloseTimerRef = useRef<number | null>(null);
  const pixelPreviewUrl = useObjectUrl(asset?.previewBlob, activeTool === 'pixels');
  const fullscreenUrl = useObjectUrl(asset?.originalBlob, fullscreen);

  const notify = (message: string) => { setFeedback(message); window.setTimeout(() => setFeedback(null), 1800); };
  const rememberShapes = (snapshot: AnnotationShape[]) => setShapePast((history) => [...history, structuredClone(snapshot)].slice(-40));
  const updateShapes = (next: AnnotationShape[]) => { rememberShapes(shapes); setShapes(next); };
  useEffect(() => {
    setShapes(structuredClone(node.data.annotation?.shapes ?? []));
    setShapePast([]); setSelectedShapeId(null); setActiveTool(null);
  }, [node.id, node.data.annotation]);
  useEffect(() => {
    if (!activeTool && !fullscreen) return;
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { setActiveTool(null); setFullscreen(false); setIsSplitMatrixOpen(false); }
      if ((event.key === 'Delete' || event.key === 'Backspace') && selectedShapeId) { event.preventDefault(); updateShapes(shapes.filter((shape) => shape.id !== selectedShapeId)); setSelectedShapeId(null); }
    };
    addEventListener('keydown', close); return () => removeEventListener('keydown', close);
  }, [activeTool, fullscreen, selectedShapeId, shapes]);

  const sourceRatio = asset ? asset.width / asset.height : 1;
  const crop = useMemo(() => {
    const targetRatio = cropRatios[cropRatio] ?? sourceRatio; let width = 1; let height = 1;
    if (targetRatio > sourceRatio) height = sourceRatio / targetRatio; else width = targetRatio / sourceRatio;
    width /= cropZoom; height /= cropZoom; const travelX = 1 - width; const travelY = 1 - height;
    return { x: travelX / 2 + cropPanX / 100 * travelX / 2, y: travelY / 2 + cropPanY / 100 * travelY / 2, width, height };
  }, [cropPanX, cropPanY, cropRatio, cropZoom, sourceRatio]);

  const persistDerived = async (nextAsset: NonNullable<typeof asset>, label: string, annotation?: AnnotationDocument) => {
    try {
      await saveAsset(nextAsset); upsertAsset(nextAsset);
      createDerivedNodes(node.id, [{ data: { assetId: nextAsset.id, label, operation: nextAsset.operation, annotation }, size: getMediaNodeSize(nextAsset.width, nextAsset.height) }]);
      requestAnimationFrame(() => void fitView({ padding: .2, duration: 360 }));
    } catch (error) { disposeRuntimeAsset(nextAsset); throw error; }
  };
  const applyCrop = async () => { if (!asset || isApplying) return; setIsApplying(true); try { const next = await createCroppedImageAsset(asset, crop); await persistDerived(next, `${node.data.label} · 裁剪`); setActiveTool(null); notify('已创建真实裁剪派生节点'); } catch (error) { notify(error instanceof Error ? error.message : '裁剪失败'); } finally { setIsApplying(false); } };
  const applyPixels = async () => { if (!asset || isApplying) return; setIsApplying(true); try { const next = await createPixelAdjustedAsset(asset, { brightness, contrast, saturation }); await persistDerived(next, `${node.data.label} · 像素调整`); setActiveTool(null); notify('已编码新的像素版本'); } catch (error) { notify(error instanceof Error ? error.message : '像素处理失败'); } finally { setIsApplying(false); } };
  const applyAnnotation = async () => { if (!asset || !shapes.length || isApplying) return; setIsApplying(true); try { const document: AnnotationDocument = { version: 1, sourceAssetId: asset.id, shapes }; const next = await createAnnotatedImageAsset(asset, document); await persistDerived(next, `${node.data.label} · 标注`, document); setActiveTool(null); notify('已创建可编辑标注派生节点'); } catch (error) { notify(error instanceof Error ? error.message : '标注合成失败'); } finally { setIsApplying(false); } };
  const applySplit = async (rows: number, columns: number) => {
    if (!asset || isApplying) return;
    setActiveTool(null);
    setIsApplying(true);
    const persisted: NonNullable<typeof asset>[] = [];
    let failure: unknown = null;
    try {
      await createSplitImageAssets(asset, rows, columns, async (batch) => {
        await saveAssets(batch);
        persisted.push(...batch);
      });
    } catch (error) {
      failure = error;
    }

    if (persisted.length) {
      upsertAssets(persisted);
      createDerivedNodes(node.id, persisted.map((slice, index) => ({
        data: { assetId: slice.id, label: `${node.data.label} · ${Math.floor(index / columns) + 1}-${index % columns + 1}`, operation: slice.operation },
        size: getMediaNodeSize(slice.width, slice.height),
      })), { columns });
      requestAnimationFrame(() => void fitView({ padding: .18, duration: 420 }));
    }

    if (failure) notify(`${failure instanceof Error ? failure.message : '切分失败'}${persisted.length ? `，已保留 ${persisted.length} 个成功切片` : ''}`);
    else notify(`已创建 ${persisted.length} 个真实切片`);
    setIsApplying(false);
  };
  const downloadSlices = async () => {
    if (!splitAssetIds.length) return;
    const splitAssets = await getAssets(splitAssetIds);
    if (!splitAssets.length) return;
    const files: Record<string, Uint8Array> = {};
    for (const [index, item] of splitAssets.entries()) files[`${String(index + 1).padStart(2, '0')}-${item.name}`] = new Uint8Array(await item.originalBlob.arrayBuffer());
    const archive = await zipInWorker(files);
    downloadBlob(new Blob([archive as Uint8Array<ArrayBuffer>], { type: 'application/zip' }), `${node.data.label}-切片.zip`);
  };

  const clearSplitTimer = () => { if (splitCloseTimerRef.current !== null) { clearTimeout(splitCloseTimerRef.current); splitCloseTimerRef.current = null; } };
  const closeSplitSoon = () => { clearSplitTimer(); splitCloseTimerRef.current = window.setTimeout(() => { setIsSplitMatrixOpen(false); setSplitMatrixHover({ rows: 1, columns: 1 }); }, 160); };

  if (!asset) return null;
  return <>
    {activeTool === 'crop' && <CropOverlay crop={crop} />}
    {activeTool === 'pixels' && pixelPreviewUrl && <div className="pixel-node-preview"><img src={pixelPreviewUrl} alt="" decoding="async" style={{ filter: `brightness(${100 + brightness}%) contrast(${100 + contrast}%) saturate(${100 + saturation}%)` }} /></div>}
    {activeTool === 'annotation' && <AnnotationOverlay shapes={shapes} mode={annotationMode} color={annotationColor} width={annotationWidth} text={annotationText} selectedId={selectedShapeId} onGestureStart={rememberShapes} onShapesChange={setShapes} onSelect={setSelectedShapeId} />}
    {activeTool === 'redraw' && <RedrawEditor asset={asset} node={node} onClose={() => setActiveTool(null)} onFeedback={notify} />}

    {activeTool !== 'redraw' && <NodeToolbar nodeId={node.id} isVisible position={Position.Top} align="center" offset={12} className="image-result-toolbar nodrag nowheel" onPointerDown={(event) => event.stopPropagation()}>
      <button className={activeTool === 'crop' ? 'is-active' : ''} title="裁剪" onClick={() => setActiveTool(activeTool === 'crop' ? null : 'crop')}><Crop size={19} /></button>
      <button disabled title="多角度 · 未配置执行服务"><Rotate3D size={19} /></button>
      <button title="重绘" onClick={() => setActiveTool('redraw')}><PenLine size={19} /></button>
      <button disabled title="重新布光 · 未配置执行服务"><Lightbulb size={19} /></button>
      <button className={activeTool === 'more' ? 'is-active' : ''} title="浏览全部" onClick={() => setActiveTool(activeTool === 'more' ? null : 'more')}><MoreHorizontal size={19} /></button><i />
      <button title="保存到本地素材库" onClick={() => void saveAssetToLibrary(asset.id).then(() => notify('真实素材已保存到跨项目素材库')).catch((error) => notify(error.message))}><FolderPlus size={19} /></button>
      <button title="下载当前真实文件" onClick={() => downloadBlob(asset.originalBlob, asset.name)}><Download size={19} /></button>
      <button title="全屏查看" onClick={() => setFullscreen(true)}><Maximize2 size={19} /></button>
    </NodeToolbar>}
    {feedback && <NodeToolbar nodeId={node.id} isVisible position={Position.Top} align="center" offset={52} className="image-tool-feedback" role="status">{feedback}</NodeToolbar>}

    {activeTool === 'crop' && <NodeToolbar nodeId={node.id} isVisible position={Position.Bottom} align="center" offset={12} className="image-edit-panel image-edit-panel--crop nodrag nowheel" onPointerDown={(event) => event.stopPropagation()}><header><div><Crop size={15} /><strong>裁剪图片</strong><small>应用后创建派生节点</small></div><button onClick={() => setActiveTool(null)}><X size={16} /></button></header><div className="crop-controls"><div className="crop-ratios">{Object.keys(cropRatios).map((ratio) => <button className={cropRatio === ratio ? 'is-active' : ''} onClick={() => setCropRatio(ratio)} key={ratio}>{ratio}</button>)}</div><ToolRange label="缩放" value={cropZoom} min={1} max={2.5} step={.05} suffix="×" onChange={setCropZoom} /><ToolRange label="水平" value={cropPanX} min={-100} max={100} suffix="%" onChange={setCropPanX} /><ToolRange label="垂直" value={cropPanY} min={-100} max={100} suffix="%" onChange={setCropPanY} /></div><footer className="image-tool-footer"><button className="image-tool-reset" onClick={() => { setCropRatio('原图比例'); setCropZoom(1); setCropPanX(0); setCropPanY(0); }}><RotateCcw size={13} /> 重置</button><span>本地处理</span><button className="image-tool-apply" disabled={isApplying} onClick={() => void applyCrop()}>{isApplying ? '处理中…' : '应用'} <ArrowUp size={14} /></button></footer></NodeToolbar>}

    {activeTool === 'pixels' && <NodeToolbar nodeId={node.id} isVisible position={Position.Bottom} align="center" offset={12} className="image-edit-panel image-edit-panel--pixels nodrag nowheel" onPointerDown={(event) => event.stopPropagation()}><header><div><SlidersHorizontal size={15} /><strong>调整像素</strong><small>预览后编码真实文件</small></div><button onClick={() => setActiveTool(null)}><X size={16} /></button></header><div className="pixel-controls"><ToolRange label="曝光" value={brightness} min={-50} max={50} onChange={setBrightness} /><ToolRange label="对比度" value={contrast} min={-50} max={50} onChange={setContrast} /><ToolRange label="饱和度" value={saturation} min={-100} max={100} onChange={setSaturation} /></div><footer className="image-tool-footer"><button className="image-tool-reset" onClick={() => { setBrightness(0); setContrast(0); setSaturation(0); }}><RotateCcw size={13} /> 重置</button><span>本地处理</span><button className="image-tool-apply" disabled={isApplying} onClick={() => void applyPixels()}>{isApplying ? '处理中…' : '应用'} <ArrowUp size={14} /></button></footer></NodeToolbar>}

    {activeTool === 'annotation' && <NodeToolbar nodeId={node.id} isVisible position={Position.Bottom} align="center" offset={12} className="annotation-toolbar nodrag nowheel" onPointerDown={(event) => event.stopPropagation()}><div className="annotation-tools"><button className={annotationMode === 'select' ? 'is-active' : ''} onClick={() => setAnnotationMode('select')}><MousePointer2 size={16} /> 选择</button><button className={annotationMode === 'brush' ? 'is-active' : ''} onClick={() => setAnnotationMode('brush')}><PenLine size={16} /> 画笔</button><button className={annotationMode === 'arrow' ? 'is-active' : ''} onClick={() => setAnnotationMode('arrow')}><MoveUpRight size={16} /> 箭头</button><button className={annotationMode === 'rectangle' ? 'is-active' : ''} onClick={() => setAnnotationMode('rectangle')}><RectangleHorizontal size={16} /> 矩形</button><button className={annotationMode === 'text' ? 'is-active' : ''} onClick={() => setAnnotationMode('text')}><Type size={16} /> 文字</button></div><input className="annotation-color" type="color" aria-label="标注颜色" value={annotationColor} onChange={(event) => setAnnotationColor(event.target.value)} /><input className="annotation-size" type="range" min="2" max="24" value={annotationWidth} onChange={(event) => setAnnotationWidth(Number(event.target.value))} />{annotationMode === 'text' && <input className="annotation-text-input" value={annotationText} onChange={(event) => setAnnotationText(event.target.value)} aria-label="标注文字" />}<button disabled={!shapePast.length} onClick={() => { const previous = shapePast.at(-1); if (previous) { setShapes(previous); setShapePast(shapePast.slice(0, -1)); } }}>撤销</button><button disabled={!shapes.length} onClick={() => { setShapePast([...shapePast, shapes]); setShapes([]); setSelectedShapeId(null); }}>清空</button><button disabled={!shapes.length} onClick={() => downloadBlob(new Blob([annotationToSvg({ version: 1, sourceAssetId: asset.id, shapes }, asset.width, asset.height)], { type: 'image/svg+xml' }), `${asset.name.replace(/\.[^.]+$/, '')}-annotation.svg`)}>SVG</button><button className="image-tool-apply" disabled={!shapes.length || isApplying} onClick={() => void applyAnnotation()}>{isApplying ? '处理中…' : '应用'} <ArrowUp size={14} /></button></NodeToolbar>}

    {activeTool === 'more' && <NodeToolbar nodeId={node.id} isVisible position={Position.Bottom} align="center" offset={12} className="image-more-menu nodrag nowheel" onPointerDown={(event) => event.stopPropagation()}>
      <button disabled title="未配置图像执行服务"><Expand size={16} /><span>扩图</span><small>服务未连接</small></button><button disabled title="未配置图像执行服务"><Eraser size={16} /><span>擦除</span><small>服务未连接</small></button><button onClick={() => setActiveTool('annotation')}><PenLine size={16} /><span>标注</span></button><button disabled title="未配置图像执行服务"><Sparkles size={16} /><span>增强</span><small>服务未连接</small></button><button onClick={() => setActiveTool('pixels')}><SlidersHorizontal size={16} /><span>调整像素</span></button><button disabled title="未配置图像执行服务"><Scissors size={16} /><span>抠图</span><small>服务未连接</small></button>{splitAssetIds.length > 0 && <button onClick={() => void downloadSlices()}><Download size={16} /><span>下载全部切片</span><small>{splitAssetIds.length} 项 ZIP</small></button>}
      <div className={`quick-split-row${isSplitMatrixOpen ? ' is-matrix-open' : ''}`} onMouseEnter={() => { clearSplitTimer(); setIsSplitMatrixOpen(true); }} onMouseLeave={closeSplitSoon} onClick={() => setIsSplitMatrixOpen(true)}><Grid3X3 size={16} /><span>快速切分</span><div className="quick-split-presets">{[2, 3, 4].map((size) => <button disabled={isApplying} key={size} onClick={(event) => { event.stopPropagation(); void applySplit(size, size); }}>{size}×{size}</button>)}</div><button className="quick-split-trigger" onClick={(event) => { event.stopPropagation(); setIsSplitMatrixOpen(!isSplitMatrixOpen); }}>›</button><div className="quick-split-flyout" onMouseEnter={clearSplitTimer} onMouseLeave={closeSplitSoon} onClick={(event) => event.stopPropagation()}><div className="quick-split-flyout-card"><div className="quick-split-matrix" onMouseLeave={() => setSplitMatrixHover({ rows: 1, columns: 1 })}>{Array.from({ length: 64 }, (_, index) => { const row = Math.floor(index / 8) + 1; const column = index % 8 + 1; return <button className={row <= splitMatrixHover.rows && column <= splitMatrixHover.columns ? 'is-within-selection' : ''} disabled={isApplying} key={`${row}-${column}`} aria-label={`切分为 ${row}×${column}`} onMouseEnter={() => setSplitMatrixHover({ rows: row, columns: column })} onFocus={() => setSplitMatrixHover({ rows: row, columns: column })} onClick={() => void applySplit(row, column)} />; })}</div><strong>{splitMatrixHover.rows}×{splitMatrixHover.columns}</strong></div></div></div>
    </NodeToolbar>}

    {fullscreen && createPortal(<div className="image-fullscreen nodrag nowheel" role="dialog" onClick={(event) => { if (event.target === event.currentTarget) setFullscreen(false); }}>{fullscreenUrl ? <img className="fullscreen-media" src={fullscreenUrl} alt={asset.name} decoding="async" /> : <div className="fullscreen-media-loading">正在解码原图…</div>}<header><strong>{node.data.label}</strong><span>{asset.width} × {asset.height}</span></header><button onClick={() => setFullscreen(false)}><X size={20} /></button></div>, document.body)}
  </>;
}
