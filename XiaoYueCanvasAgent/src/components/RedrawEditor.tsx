import { useEffect, useMemo, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent } from 'react';
import {
  ArrowUp, Brush, ChevronDown, Eraser, ImagePlus, Redo2, RectangleHorizontal, RotateCcw,
  Undo2, Upload, X,
} from 'lucide-react';
import { jobAdapter } from '../services/capabilities';
import { useObjectUrl } from '../hooks/useObjectUrl';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { useAgentStore } from '../store/agentStore';
import type { CanvasNode, MediaAsset, Point, RedrawMaskDocument, RedrawMaskMark } from '../types';
import { createRedrawJobRequest, rasterizeRedrawMask, type RedrawReference } from '../utils/redrawMask';
import { createId } from '../utils/id';

type MaskTool = 'brush' | 'rectangle' | 'eraser';

interface RedrawEditorProps {
  asset: MediaAsset;
  node: CanvasNode;
  panelStyle: CSSProperties;
  toolbarStyle: CSSProperties;
  onClose: () => void;
  onFeedback: (message: string) => void;
}

const pointFromClient = (clientX: number, clientY: number, box: DOMRect): Point => {
  return {
    x: Math.max(0, Math.min(1, (clientX - box.left) / box.width)),
    y: Math.max(0, Math.min(1, (clientY - box.top) / box.height)),
  };
};

const createNativeBrushCursor = (diameter: number, erasing: boolean) => {
  const visibleDiameter = Math.max(2, Math.min(100, Math.round(diameter)));
  const size = visibleDiameter + 8;
  const center = size / 2;
  const radius = visibleDiameter / 2;
  const eraseMark = erasing
    ? `<path d="M${center - 3} ${center}h6M${center} ${center - 3}v6" stroke="white" stroke-width="1"/>`
    : '';
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}"><circle cx="${center}" cy="${center}" r="${radius}" fill="rgba(255,255,255,.06)" stroke="rgba(0,0,0,.72)" stroke-width="3"/><circle cx="${center}" cy="${center}" r="${radius}" fill="none" stroke="white" stroke-width="1.25"/>${eraseMark}</svg>`;
  return `url("data:image/svg+xml,${encodeURIComponent(svg)}") ${center} ${center}, crosshair`;
};

function MaskMark({ mark, imageWidth, imageHeight }: { mark: RedrawMaskMark; imageWidth: number; imageHeight: number }) {
  const color = mark.operation === 'paint' ? 'white' : 'black';
  if (mark.type === 'rectangle') {
    return <rect x={Math.min(mark.start.x, mark.end.x) * imageWidth} y={Math.min(mark.start.y, mark.end.y) * imageHeight} width={Math.abs(mark.end.x - mark.start.x) * imageWidth} height={Math.abs(mark.end.y - mark.start.y) * imageHeight} fill={color} />;
  }
  const lineWidth = mark.width * Math.min(imageWidth, imageHeight);
  if (mark.points.length === 1) return <circle cx={mark.points[0].x * imageWidth} cy={mark.points[0].y * imageHeight} r={lineWidth / 2} fill={color} />;
  return <polyline points={mark.points.map((point) => `${point.x * imageWidth},${point.y * imageHeight}`).join(' ')} fill="none" stroke={color} strokeWidth={lineWidth} strokeLinecap="round" strokeLinejoin="round" />;
}

function RedrawMaskOverlay({ marks, tool, width, imageWidth, imageHeight, onCommit, onGestureStart }: {
  marks: RedrawMaskMark[];
  tool: MaskTool;
  width: number;
  imageWidth: number;
  imageHeight: number;
  onCommit: (marks: RedrawMaskMark[]) => void;
  onGestureStart: () => void;
}) {
  const marksRef = useRef(marks);
  const activeIdRef = useRef<string | null>(null);
  const activeMarkRef = useRef<RedrawMaskMark | null>(null);
  const geometryRef = useRef<DOMRect | null>(null);
  const activeDotRef = useRef<SVGCircleElement>(null);
  const activeBrushRef = useRef<SVGPolylineElement>(null);
  const activeRectangleRef = useRef<SVGRectElement>(null);
  const nativeCursor = useMemo(() => tool === 'rectangle' ? 'crosshair' : createNativeBrushCursor(width, tool === 'eraser'), [tool, width]);
  marksRef.current = marks;

  const setVisible = (element: SVGElement | null, visible: boolean) => {
    if (element) element.style.display = visible ? '' : 'none';
  };

  const clearActivePreview = () => {
    setVisible(activeDotRef.current, false);
    setVisible(activeBrushRef.current, false);
    setVisible(activeRectangleRef.current, false);
  };

  const renderActivePreview = (mark: RedrawMaskMark) => {
    clearActivePreview();
    const color = mark.operation === 'paint' ? 'white' : 'black';
    if (mark.type === 'rectangle') {
      const rectangle = activeRectangleRef.current;
      if (!rectangle) return;
      rectangle.setAttribute('x', String(Math.min(mark.start.x, mark.end.x) * imageWidth));
      rectangle.setAttribute('y', String(Math.min(mark.start.y, mark.end.y) * imageHeight));
      rectangle.setAttribute('width', String(Math.abs(mark.end.x - mark.start.x) * imageWidth));
      rectangle.setAttribute('height', String(Math.abs(mark.end.y - mark.start.y) * imageHeight));
      rectangle.setAttribute('fill', color);
      setVisible(rectangle, true);
      return;
    }

    const lineWidth = mark.width * Math.min(imageWidth, imageHeight);
    if (mark.points.length === 1) {
      const dot = activeDotRef.current;
      if (!dot) return;
      dot.setAttribute('cx', String(mark.points[0].x * imageWidth));
      dot.setAttribute('cy', String(mark.points[0].y * imageHeight));
      dot.setAttribute('r', String(lineWidth / 2));
      dot.setAttribute('fill', color);
      setVisible(dot, true);
      return;
    }

    const brush = activeBrushRef.current;
    if (!brush) return;
    brush.setAttribute('points', mark.points.map((point) => `${point.x * imageWidth},${point.y * imageHeight}`).join(' '));
    brush.setAttribute('stroke', color);
    brush.setAttribute('stroke-width', String(lineWidth));
    setVisible(brush, true);
  };

  const begin = (event: ReactPointerEvent<SVGSVGElement>) => {
    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    const box = event.currentTarget.getBoundingClientRect();
    geometryRef.current = box;
    const start = pointFromClient(event.clientX, event.clientY, box);
    const id = createId();
    const normalizedWidth = Math.min(1, width / Math.max(1, Math.min(box.width, box.height)));
    const mark: RedrawMaskMark = tool === 'rectangle'
      ? { id, type: 'rectangle', operation: 'paint', start, end: start }
      : { id, type: 'brush', operation: tool === 'eraser' ? 'erase' : 'paint', points: [start], width: normalizedWidth };
    onGestureStart();
    activeIdRef.current = id;
    activeMarkRef.current = mark;
    renderActivePreview(mark);
  };

  const move = (event: ReactPointerEvent<SVGSVGElement>) => {
    const box = geometryRef.current ?? event.currentTarget.getBoundingClientRect();
    const id = activeIdRef.current;
    if (!id) return;
    const mark = activeMarkRef.current;
    if (!mark || mark.id !== id) return;
    if (mark.type === 'rectangle') {
      mark.end = pointFromClient(event.clientX, event.clientY, box);
    } else {
      const nativeEvent = event.nativeEvent;
      const samples = nativeEvent.getCoalescedEvents?.() ?? [nativeEvent];
      for (const sample of samples.length ? samples : [nativeEvent]) {
        const nextPoint = pointFromClient(sample.clientX, sample.clientY, box);
        const previous = mark.points.at(-1)!;
        const screenDistance = Math.hypot((nextPoint.x - previous.x) * box.width, (nextPoint.y - previous.y) * box.height);
        if (screenDistance >= 1) mark.points.push(nextPoint);
      }
    }
    renderActivePreview(mark);
  };

  const end = () => {
    const mark = activeMarkRef.current;
    activeIdRef.current = null;
    activeMarkRef.current = null;
    clearActivePreview();
    if (!mark) return;
    marksRef.current = [...marksRef.current, mark];
    onCommit(marksRef.current);
  };
  return <svg className={`redraw-mask-overlay nodrag redraw-mask-overlay--${tool}`} viewBox={`0 0 ${imageWidth} ${imageHeight}`} preserveAspectRatio="none" style={{ cursor: nativeCursor }} onPointerDown={begin} onPointerMove={move} onPointerUp={end} onPointerCancel={end}>
    <defs><mask id="active-redraw-mask"><rect width={imageWidth} height={imageHeight} fill="black" />{marks.map((mark) => <MaskMark key={mark.id} mark={mark} imageWidth={imageWidth} imageHeight={imageHeight} />)}<circle ref={activeDotRef} cx="0" cy="0" r="0" style={{ display: 'none' }} /><polyline ref={activeBrushRef} points="" fill="none" stroke="white" strokeWidth="1" strokeLinecap="round" strokeLinejoin="round" style={{ display: 'none' }} /><rect ref={activeRectangleRef} x="0" y="0" width="0" height="0" fill="white" style={{ display: 'none' }} /></mask></defs>
    <rect width={imageWidth} height={imageHeight} fill="rgba(255,255,255,.68)" mask="url(#active-redraw-mask)" />
  </svg>;
}

function ReferencePreview({ reference, onRemove }: { reference: RedrawReference; onRemove: () => void }) {
  const storedAsset = useMediaStore((state) => reference.type === 'asset' ? state.assets[reference.assetId] : undefined);
  const blob = reference.type === 'file' ? reference.file : storedAsset?.thumbnailBlob;
  const url = useObjectUrl(blob);
  const label = reference.type === 'file' ? reference.file.name : reference.label;
  return <div className="redraw-reference-preview" title={label}>{url ? <img src={url} alt={label} /> : <ImagePlus size={16} />}<button aria-label="移除参考图" onClick={onRemove}><X size={11} /></button></div>;
}

function CanvasReferenceOption({ node, onSelect }: { node: CanvasNode; onSelect: () => void }) {
  const asset = useMediaStore((state) => node.data.assetId ? state.assets[node.data.assetId] : undefined);
  const metadata = useMediaStore((state) => node.data.assetId ? state.metadata[node.data.assetId] : undefined);
  const url = useObjectUrl(asset?.thumbnailBlob);
  return <button className="redraw-canvas-reference" onClick={onSelect}>{url ? <img src={url} alt="" /> : <ImagePlus size={15} />}<span>{node.data.label}</span><small>{metadata ? `${metadata.width}×${metadata.height}` : '画布图片'}</small></button>;
}

export function RedrawEditor({ asset, node, panelStyle, toolbarStyle, onClose, onFeedback }: RedrawEditorProps) {
  const canvasNodes = useCanvasStore((state) => state.nodes);
  const [tool, setTool] = useState<MaskTool>('brush');
  const [brushWidth, setBrushWidth] = useState(30);
  const [marks, setMarks] = useState<RedrawMaskMark[]>([]);
  const [past, setPast] = useState<RedrawMaskMark[][]>([]);
  const [future, setFuture] = useState<RedrawMaskMark[][]>([]);
  const [prompt, setPrompt] = useState('');
  const [reference, setReference] = useState<RedrawReference | null>(null);
  const [referenceMenuOpen, setReferenceMenuOpen] = useState(false);
  const [canvasPickerOpen, setCanvasPickerOpen] = useState(false);
  const [modelTier, setModelTier] = useState<'normal' | 'pro'>('normal');
  const [resolution, setResolution] = useState<'1K' | '2K' | '4K'>('2K');
  const [count, setCount] = useState<1 | 2 | 3 | 4>(1);
  const [submitting, setSubmitting] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const eligibleCanvasNodes = canvasNodes.filter((candidate) => candidate.id !== node.id && candidate.data.kind === 'image' && candidate.data.assetId);
  const mask: RedrawMaskDocument = { version: 1, sourceAssetId: asset.id, marks };

  const remember = () => {
    setPast((history) => [...history, structuredClone(marks)].slice(-40));
    setFuture([]);
  };
  const undo = () => {
    const previous = past.at(-1);
    if (!previous) return;
    setFuture((history) => [structuredClone(marks), ...history].slice(0, 40));
    setMarks(previous);
    setPast((history) => history.slice(0, -1));
  };
  const redo = () => {
    const next = future[0];
    if (!next) return;
    setPast((history) => [...history, structuredClone(marks)].slice(-40));
    setMarks(next);
    setFuture((history) => history.slice(1));
  };

  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); onClose(); return; }
      if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 'z') return;
      event.preventDefault(); event.stopPropagation();
      if (event.shiftKey) redo(); else undo();
    };
    window.addEventListener('keydown', handleKey, true);
    return () => window.removeEventListener('keydown', handleKey, true);
  });

  const selectFile = (file: File | undefined) => {
    if (!file) return;
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) { onFeedback('参考图仅支持 PNG、JPEG 或 WebP'); return; }
    if (file.size > 10 * 1024 * 1024) { onFeedback('参考图不能超过 10MB'); return; }
    setReference({ type: 'file', file });
    setReferenceMenuOpen(false);
  };

  const cycleResolution = () => setResolution((current) => current === '1K' ? '2K' : current === '2K' ? '4K' : '1K');
  const submit = async () => {
    if (submitting) return;
    let request;
    try {
      const canvas = useCanvasStore.getState();
      const projectId = canvas.projectId ?? 'local-project';
      request = createRedrawJobRequest(asset.id, prompt, mask, { count, modelTier, resolution }, reference, {
        clientRequestId: createId(),
        conversationId: useAgentStore.getState().ensureSession(projectId).conversationId,
        context: { projectId, nodeIds: canvas.nodes.map((item) => item.id), selectedNodeId: node.id },
      });
    } catch (error) {
      onFeedback(error instanceof Error ? error.message : '重绘参数无效');
      return;
    }
    setSubmitting(true);
    try {
      const maskBlob = await rasterizeRedrawMask(mask, asset.width, asset.height);
      const capabilities = await jobAdapter.getCapabilities();
      const capability = capabilities.find((item) => item.id === 'image.redraw');
      if (!capability?.available) throw new Error(capability?.unavailableReason ?? '未配置图像执行服务');
      await jobAdapter.submit({
        ...request,
        params: {
          ...request.params,
          maskImage: { blob: maskBlob, name: `${asset.id}-redraw-mask.png`, mimeType: 'image/png', bytes: maskBlob.size },
        },
      });
    } catch (error) {
      onFeedback(error instanceof Error ? error.message : '未配置图像执行服务');
    } finally {
      setSubmitting(false);
    }
  };

  return <div className="redraw-editor" onPointerDown={(event) => event.stopPropagation()}>
    <RedrawMaskOverlay marks={marks} tool={tool} width={brushWidth} imageWidth={asset.width} imageHeight={asset.height} onGestureStart={remember} onCommit={setMarks} />

    <div className="redraw-toolstrip nodrag nowheel" style={toolbarStyle}>
      <button title="退出重绘" className="is-close" onClick={onClose}><X size={18} /></button><i />
      <div className="redraw-tool-group"><button title="画笔" className={tool === 'brush' ? 'is-active' : ''} onClick={() => setTool('brush')}><Brush size={17} /></button><button title="框选" className={tool === 'rectangle' ? 'is-active' : ''} onClick={() => setTool('rectangle')}><RectangleHorizontal size={17} /></button><button title="橡皮擦" className={tool === 'eraser' ? 'is-active' : ''} onClick={() => setTool('eraser')}><Eraser size={17} /></button></div><i />
      <label className="redraw-width" title={`线宽 ${brushWidth}`}><span style={{ height: Math.max(2, brushWidth / 9) }} /><input aria-label="蒙版画笔大小" type="range" min="1" max="100" value={brushWidth} onChange={(event) => setBrushWidth(Number(event.target.value))} /></label><i />
      <button title="撤销" disabled={!past.length} onClick={undo}><Undo2 size={17} /></button><button title="重做" disabled={!future.length} onClick={redo}><Redo2 size={17} /></button>
    </div>

    <div className="redraw-prompt-panel nodrag nowheel" style={panelStyle} onKeyDown={(event) => event.stopPropagation()}>
      <textarea aria-label="重绘要求" value={prompt} onChange={(event) => setPrompt(event.target.value)} placeholder="描述你想改变什么..." />
      <div className="redraw-prompt-footer">
        <div className="redraw-reference-control">
          <input ref={fileInputRef} type="file" accept="image/png,image/jpeg,image/webp" hidden onChange={(event) => { selectFile(event.target.files?.[0]); event.target.value = ''; }} />
          {reference ? <ReferencePreview reference={reference} onRemove={() => setReference(null)} /> : <button title="添加参考图" onClick={() => setReferenceMenuOpen((open) => !open)}><ImagePlus size={17} /><ChevronDown size={12} /></button>}
          {referenceMenuOpen && <div className="redraw-reference-menu"><button onClick={() => fileInputRef.current?.click()}><Upload size={15} /> 从设备上传</button><button onClick={() => { setCanvasPickerOpen(true); setReferenceMenuOpen(false); }}><ImagePlus size={15} /> 从画布选择</button></div>}
        </div>
        <span className={marks.length ? 'has-mask' : ''}>{marks.length ? `已绘制 ${marks.length} 处蒙版` : '绘制蒙版以重绘'}</span>
        <div className="redraw-output-options">
          {modelTier === 'pro' && <button onClick={cycleResolution}>{resolution}</button>}
          <button title="生成数量" onClick={() => setCount((current) => (current === 4 ? 1 : current + 1) as 1 | 2 | 3 | 4)}>×{count}</button>
          <button className={modelTier === 'pro' ? 'is-pro' : ''} onClick={() => setModelTier((tier) => tier === 'normal' ? 'pro' : 'normal')}>{modelTier === 'pro' ? 'PRO' : 'NORMAL'}</button>
          <button className="redraw-submit" title="生成重绘结果" disabled={submitting || !prompt.trim()} onClick={() => void submit()}>{submitting ? <RotateCcw className="is-spinning" size={17} /> : <ArrowUp size={17} />}</button>
        </div>
      </div>
    </div>

    {canvasPickerOpen && <div className="redraw-canvas-picker nodrag nowheel" role="dialog" aria-label="从画布选择参考图" style={panelStyle}><header><div><strong>从画布选择参考图</strong><small>参考图只参与本次重绘请求</small></div><button onClick={() => setCanvasPickerOpen(false)}><X size={16} /></button></header><div>{eligibleCanvasNodes.length ? eligibleCanvasNodes.map((candidate) => <CanvasReferenceOption key={candidate.id} node={candidate} onSelect={() => { setReference({ type: 'asset', assetId: candidate.data.assetId!, label: candidate.data.label }); setCanvasPickerOpen(false); }} />) : <p>画布上没有其他可用图片</p>}</div></div>}
  </div>;
}
