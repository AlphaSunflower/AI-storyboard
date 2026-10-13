import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { createPortal } from 'react-dom';
import { useReactFlow } from '@xyflow/react';
import {
  Brush,
  Check,
  MousePointer2,
  Redo2,
  RectangleHorizontal,
  Sparkles,
  Trash2,
  Undo2,
  X,
} from 'lucide-react';
import { useCanvasStore } from '../store/canvasStore';
import type { CanvasNode, MediaAsset, Point, RedrawMaskDocument, RedrawMaskMark } from '../types';
import { submitImageRedraw } from '../services/jobRunner';
import { jobAdapter } from '../services/capabilities';
import {
  REDRAW_REGION_COLORS,
  buildRedrawPrompt,
  rasterizeRedrawMask,
  redrawPaintMarks,
  redrawRegionColor,
  validateRedrawDocument,
} from '../utils/redrawMask';
import { createId } from '../utils/id';
import { GenFooter } from './nodes/ImageGenNode';
import { useObjectUrl } from '../hooks/useObjectUrl';
import { buildReferenceToken } from '../canvas/referenceTokenEditing';

type MaskTool = 'select' | 'brush' | 'rectangle';

interface RedrawEditorProps {
  asset: MediaAsset;
  node: CanvasNode;
  onClose: () => void;
  onFeedback: (message: string) => void;
}

const pointFromClient = (clientX: number, clientY: number, box: DOMRect): Point => ({
  x: Math.max(0, Math.min(1, (clientX - box.left) / box.width)),
  y: Math.max(0, Math.min(1, (clientY - box.top) / box.height)),
});

const markAnchor = (mark: RedrawMaskMark): Point => {
  if (mark.type === 'rectangle') {
    return {
      x: (mark.start.x + mark.end.x) / 2,
      y: (mark.start.y + mark.end.y) / 2,
    };
  }
  const points = mark.points.length ? mark.points : [{ x: .5, y: .5 }];
  return {
    x: points.reduce((sum, point) => sum + point.x, 0) / points.length,
    y: points.reduce((sum, point) => sum + point.y, 0) / points.length,
  };
};

function ContainedRedrawStage({
  imageAspect,
  children,
}: {
  imageAspect: number;
  children: (size: { width: number; height: number }) => React.ReactNode;
}) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [host, setHost] = useState({ width: 0, height: 0 });
  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    const sync = () => setHost({ width: host.clientWidth, height: host.clientHeight });
    sync();
    const observer = new ResizeObserver(sync);
    observer.observe(host);
    return () => observer.disconnect();
  }, []);
  const frame = useMemo(() => {
    if (!host.width || !host.height) return null;
    const hostAspect = host.width / host.height;
    return hostAspect > imageAspect
      ? {
        left: (host.width - host.height * imageAspect) / 2,
        top: 0,
        width: host.height * imageAspect,
        height: host.height,
      }
      : {
        left: 0,
        top: (host.height - host.width / imageAspect) / 2,
        width: host.width,
        height: host.width / imageAspect,
      };
  }, [host, imageAspect]);
  return <div ref={hostRef} className="redraw-fullscreen-stage">
    {frame && <div className="redraw-fullscreen-frame" style={frame}>{children(frame)}</div>}
  </div>;
}

function MaskMark({ mark, imageWidth, imageHeight }: { mark: RedrawMaskMark; imageWidth: number; imageHeight: number }) {
  const color = mark.operation === 'paint' ? 'white' : 'black';
  if (mark.type === 'rectangle') {
    return <rect x={Math.min(mark.start.x, mark.end.x) * imageWidth} y={Math.min(mark.start.y, mark.end.y) * imageHeight} width={Math.abs(mark.end.x - mark.start.x) * imageWidth} height={Math.abs(mark.end.y - mark.start.y) * imageHeight} fill={color} />;
  }
  const lineWidth = mark.width * Math.min(imageWidth, imageHeight);
  if (mark.points.length === 1) return <circle cx={mark.points[0].x * imageWidth} cy={mark.points[0].y * imageHeight} r={lineWidth / 2} fill={color} />;
  return <polyline points={mark.points.map((point) => `${point.x * imageWidth},${point.y * imageHeight}`).join(' ')} fill="none" stroke={color} strokeWidth={lineWidth} strokeLinecap="round" strokeLinejoin="round" />;
}

function RegionMark({
  mark,
  imageWidth,
  imageHeight,
  selected,
  hovered,
  interactive,
  onHover,
  onSelect,
}: {
  mark: RedrawMaskMark;
  imageWidth: number;
  imageHeight: number;
  selected: boolean;
  hovered: boolean;
  interactive: boolean;
  onHover: (id: string | null) => void;
  onSelect: (id: string) => void;
}) {
  if (mark.operation !== 'paint') return null;
  const color = mark.color || REDRAW_REGION_COLORS[0];
  const active = selected || hovered;
  const opacity = selected ? .42 : hovered ? .34 : .22;
  const pointerProps = {
    onPointerEnter: () => onHover(mark.id),
    onPointerLeave: () => onHover(null),
    onPointerDown: interactive ? (event: ReactPointerEvent<SVGElement>) => {
      event.preventDefault();
      event.stopPropagation();
      onSelect(mark.id);
    } : undefined,
  };
  const label = mark.intent?.trim() || '未填写修改意图';
  const className = `redraw-region-mark${selected ? ' is-selected' : ''}${hovered ? ' is-hovered' : ''}`;
  if (mark.type === 'rectangle') {
    return <g {...pointerProps} className={className} data-region-id={mark.id} style={{ pointerEvents: 'all', cursor: interactive ? 'pointer' : undefined }}>
      <title>{label}</title>
      <rect x={Math.min(mark.start.x, mark.end.x) * imageWidth} y={Math.min(mark.start.y, mark.end.y) * imageHeight} width={Math.abs(mark.end.x - mark.start.x) * imageWidth} height={Math.abs(mark.end.y - mark.start.y) * imageHeight} fill={color} fillOpacity={opacity} stroke={color} strokeWidth={active ? 3 : 2} strokeDasharray={selected ? '7 4' : undefined} />
    </g>;
  }
  const lineWidth = Math.max(2, mark.width * Math.min(imageWidth, imageHeight));
  const points = mark.points.map((point) => `${point.x * imageWidth},${point.y * imageHeight}`).join(' ');
  return <g {...pointerProps} className={className} data-region-id={mark.id} style={{ pointerEvents: 'stroke', cursor: interactive ? 'pointer' : undefined }}>
    <title>{label}</title>
    {mark.points.length === 1
      ? <circle cx={mark.points[0].x * imageWidth} cy={mark.points[0].y * imageHeight} r={lineWidth / 2} fill={color} fillOpacity={opacity} stroke={active ? color : 'transparent'} strokeWidth={active ? 3 : 0} />
      : <><polyline points={points} fill="none" stroke={color} strokeOpacity={opacity} strokeWidth={lineWidth} strokeLinecap="round" strokeLinejoin="round" /><polyline points={points} fill="none" stroke={color} strokeOpacity={active ? 1 : .72} strokeWidth={active ? Math.max(3, lineWidth * .5) : 2} strokeLinecap="round" strokeLinejoin="round" strokeDasharray={selected ? '7 4' : undefined} /></>}
  </g>;
}

function RedrawMaskOverlay({
  marks,
  tool,
  width,
  imageWidth,
  imageHeight,
  selectedId,
  onHover,
  onSelect,
  onCommit,
  onGestureStart,
}: {
  marks: RedrawMaskMark[];
  tool: MaskTool;
  width: number;
  imageWidth: number;
  imageHeight: number;
  selectedId: string | null;
  onHover: (id: string | null) => void;
  onSelect: (id: string) => void;
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
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const maskId = `active-redraw-mask-${createId()}`;
  const paintCount = marks.filter((mark) => mark.operation === 'paint').length;
  const nextColor = redrawRegionColor(paintCount);
  const nativeCursor = tool === 'select' ? 'default' : 'crosshair';
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
    const color = mark.operation === 'erase' ? '#ffffff' : nextColor;
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
    if (tool === 'select') {
      onSelect('');
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    const box = event.currentTarget.getBoundingClientRect();
    geometryRef.current = box;
    const start = pointFromClient(event.clientX, event.clientY, box);
    const id = createId();
    const normalizedWidth = Math.min(1, width / Math.max(1, Math.min(box.width, box.height)));
    const mark: RedrawMaskMark = tool === 'rectangle'
      ? { id, type: 'rectangle', operation: 'paint', start, end: start, color: nextColor, createdAt: Date.now() }
      : { id, type: 'brush', operation: 'paint', points: [start], width: normalizedWidth, color: nextColor, createdAt: Date.now() };
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
    const unchangedRectangle = mark.type === 'rectangle'
      && Math.abs(mark.end.x - mark.start.x) < .002
      && Math.abs(mark.end.y - mark.start.y) < .002;
    if (unchangedRectangle) return;
    marksRef.current = [...marksRef.current, mark];
    onCommit(marksRef.current);
  };

  return <svg
    className={`redraw-mask-overlay nodrag redraw-mask-overlay--${tool}`}
    viewBox={`0 0 ${imageWidth} ${imageHeight}`}
    preserveAspectRatio="none"
    style={{ cursor: nativeCursor }}
    onPointerDown={begin}
    onPointerMove={move}
    onPointerUp={end}
    onPointerCancel={end}
  >
    <defs>
      <mask id={maskId}>
        <rect width={imageWidth} height={imageHeight} fill="black" />
        {marks.map((mark) => <MaskMark key={mark.id} mark={mark} imageWidth={imageWidth} imageHeight={imageHeight} />)}
        <circle ref={activeDotRef} cx="0" cy="0" r="0" style={{ display: 'none' }} />
        <polyline ref={activeBrushRef} points="" fill="none" stroke="white" strokeWidth="1" strokeLinecap="round" strokeLinejoin="round" style={{ display: 'none' }} />
        <rect ref={activeRectangleRef} x="0" y="0" width="0" height="0" fill="white" style={{ display: 'none' }} />
      </mask>
    </defs>
    <rect width={imageWidth} height={imageHeight} fill="rgba(255,255,255,.68)" mask={`url(#${maskId})`} />
    <g>{marks.map((mark) => <RegionMark key={mark.id} mark={mark} imageWidth={imageWidth} imageHeight={imageHeight} selected={selectedId === mark.id} hovered={hoveredId === mark.id} interactive={tool === 'select'} onHover={(id) => { setHoveredId(id); onHover(id); }} onSelect={onSelect} />)}</g>
  </svg>;
}

function RegionEditor({
  mark,
  index,
  intent,
  color,
  onIntent,
  onColor,
  onConfirm,
  onCancel,
  onDelete,
}: {
  mark: RedrawMaskMark;
  index: number;
  intent: string;
  color: string;
  onIntent: (value: string) => void;
  onColor: (value: string) => void;
  onConfirm: () => void;
  onCancel: () => void;
  onDelete: () => void;
}) {
  const anchor = markAnchor(mark);
  const verticalPlacement = anchor.y < .36 ? 'is-below' : 'is-above';
  const horizontalPlacement = anchor.x < .24 ? 'is-left' : anchor.x > .76 ? 'is-right' : '';
  return <div className={`redraw-region-editor ${verticalPlacement} ${horizontalPlacement} nodrag nowheel`} style={{ left: `${anchor.x * 100}%`, top: `${anchor.y * 100}%` }} onPointerDown={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}>
    <header><strong>修改 {index + 1}</strong><button type="button" onClick={onCancel} title="关闭"><X size={15} /></button></header>
    <textarea autoFocus aria-label={`修改 ${index + 1} 的意图`} value={intent} placeholder="希望对这个区域做什么..." onChange={(event) => onIntent(event.target.value)} onKeyDown={(event) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onCancel();
      } else if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        if (intent.trim()) onConfirm();
      }
    }} />
    <div className="redraw-region-colors" aria-label={`修改 ${index + 1} 的颜色`}>
      {REDRAW_REGION_COLORS.map((option) => <button key={option} type="button" className={option === color ? 'is-active' : ''} style={{ '--region-color': option } as React.CSSProperties} aria-label={`颜色 ${option}`} title={option} onClick={() => onColor(option)} />)}
    </div>
    <footer>
      <button type="button" className="redraw-region-delete" onClick={onDelete}><Trash2 size={14} />删除</button>
      <button type="button" className="redraw-region-check" disabled={!intent.trim()} onClick={onConfirm} title="确认修改意图"><Check size={15} />确认</button>
    </footer>
  </div>;
}

export function RedrawEditor({ asset, node, onClose, onFeedback }: RedrawEditorProps) {
  const { fitView } = useReactFlow();
  const assetUrl = useObjectUrl(asset.originalBlob ?? asset.previewBlob);
  const [tool, setTool] = useState<MaskTool>('rectangle');
  const [brushWidth, setBrushWidth] = useState(30);
  const [marks, setMarks] = useState<RedrawMaskMark[]>([]);
  const [past, setPast] = useState<RedrawMaskMark[][]>([]);
  const [future, setFuture] = useState<RedrawMaskMark[][]>([]);
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingNew, setEditingNew] = useState(false);
  const [draftIntent, setDraftIntent] = useState('');
  const [draftColor, setDraftColor] = useState<string>(REDRAW_REGION_COLORS[0]);
  const [executionOpen, setExecutionOpen] = useState(false);
  const [executionData, setExecutionData] = useState<Record<string, unknown>>({});
  const [executionError, setExecutionError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const launchedNodeIdRef = useRef<string | null>(null);
  const redrawDocument: RedrawMaskDocument = {
    version: 2,
    sourceAssetId: asset.id,
    sourceWidth: asset.width,
    sourceHeight: asset.height,
    marks,
  };
  const intentRegions = redrawPaintMarks(redrawDocument);
  const editingMark = editingId ? marks.find((mark) => mark.id === editingId) ?? null : null;

  const remember = (snapshot = marks) => {
    setPast((history) => [...history, structuredClone(snapshot)].slice(-40));
    setFuture([]);
  };
  const undo = () => {
    const previous = past.at(-1);
    if (!previous) return;
    setFuture((history) => [structuredClone(marks), ...history].slice(0, 40));
    setMarks(previous);
    setEditingId(null);
    setSelectedId(null);
    setPast((history) => history.slice(0, -1));
  };
  const redo = () => {
    const next = future[0];
    if (!next) return;
    setPast((history) => [...history, structuredClone(marks)].slice(-40));
    setMarks(next);
    setEditingId(null);
    setSelectedId(null);
    setFuture((history) => history.slice(1));
  };
  const openRegion = (mark: RedrawMaskMark, isNew = false) => {
    setSelectedId(mark.id);
    setEditingId(mark.id);
    setEditingNew(isNew);
    setDraftIntent(mark.intent ?? '');
    setDraftColor(mark.color ?? REDRAW_REGION_COLORS[0]);
  };
  const closeRegion = () => {
    if (editingNew && editingMark && !editingMark.intent?.trim()) {
      setMarks((current) => current.filter((mark) => mark.id !== editingMark.id));
    }
    setEditingId(null);
    setEditingNew(false);
  };
  const confirmRegion = () => {
    if (!editingMark || !draftIntent.trim()) return;
    if (!editingNew) remember();
    setMarks((current) => current.map((mark) => mark.id === editingMark.id ? { ...mark, intent: draftIntent.trim(), color: draftColor } : mark));
    setSelectedId(editingMark.id);
    setEditingId(null);
    setEditingNew(false);
  };
  const deleteRegion = () => {
    if (!editingMark) return;
    remember();
    setMarks((current) => current.filter((mark) => mark.id !== editingMark.id));
    setEditingId(null);
    setSelectedId(null);
    setEditingNew(false);
  };
  const updateExecutionData = useCallback((_: string, patch: Record<string, unknown>) => {
    setExecutionData((current) => ({ ...current, ...patch }));
  }, []);

  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        if (executionOpen) setExecutionOpen(false);
        else if (editingId) closeRegion();
        else onClose();
        return;
      }
      if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 'z') return;
      if (editingId) return;
      event.preventDefault();
      event.stopPropagation();
      if (event.shiftKey) redo(); else undo();
    };
    window.addEventListener('keydown', handleKey, true);
    return () => window.removeEventListener('keydown', handleKey, true);
  }, [editingId, editingNew, editingMark, executionOpen, marks, past, future, onClose]);

  const commitGesture = (next: RedrawMaskMark[]) => {
    setMarks(next);
    const created = next.at(-1);
    if (created?.operation === 'paint') openRegion(created, true);
  };
  const execute = async () => {
    if (submitting) return;
    setExecutionError(null);
    let prompt = '';
    try {
      ({ prompt } = validateRedrawDocument(redrawDocument));
    } catch (error) {
      const message = error instanceof Error ? error.message : '重绘参数无效';
      onFeedback(message);
      setExecutionError(message);
      return;
    }
    setSubmitting(true);
    try {
      const capabilities = await jobAdapter.getCapabilities();
      const capability = capabilities.find((item) => item.id === 'image.redraw');
      if (!capability?.available) {
        throw new Error(capability?.unavailableReason ?? '未配置图像执行服务');
      }
      let targetNodeId = launchedNodeIdRef.current;
      const promptWithSource = `${buildReferenceToken(1)}\n${prompt}`;
      if (!targetNodeId) {
        const canvas = useCanvasStore.getState();
        const sourceWidth = node.width ?? node.measured?.width ?? 250;
        targetNodeId = canvas.createConnectedNode(node.id, 'imageGeneration', {
          x: node.position.x + sourceWidth + 160,
          y: node.position.y,
        }, undefined, {
          label: `${node.data.label} · 重绘`,
          prompt: promptWithSource,
          redraw: structuredClone(redrawDocument),
          mode: 'image2image',
          status: 'idle',
        });
        if (!targetNodeId) throw new Error('无法创建图生图节点，请检查图片节点输出端口');
        launchedNodeIdRef.current = targetNodeId;
      } else {
        useCanvasStore.getState().updateNode(targetNodeId, {
          prompt: promptWithSource,
          redraw: structuredClone(redrawDocument),
          status: 'idle',
        });
      }
      useCanvasStore.getState().selectNode(node.id);
      await submitImageRedraw(
        promptWithSource,
        targetNodeId,
        redrawDocument,
        { width: asset.width, height: asset.height },
        (message) => setExecutionError(message),
        {
          sourceAssetIds: [asset.id],
          sourceNodeIds: [node.id],
          params: executionData,
        },
      );
      requestAnimationFrame(() => void fitView({ padding: .2, duration: 360 }));
      setExecutionOpen(false);
      onClose();
    } catch (error) {
      const message = error instanceof Error ? error.message : '重绘提交失败';
      if (launchedNodeIdRef.current) {
        useCanvasStore.getState().updateNode(launchedNodeIdRef.current, { status: 'error' });
      }
      setExecutionError(message);
      onFeedback(message);
    } finally {
      setSubmitting(false);
    }
  };

  return <>
    {createPortal(<div className="redraw-fullscreen-editor" onPointerDown={(event) => event.stopPropagation()}>
      <header className="redraw-fullscreen-header">
        <div><strong>{node.data.label}</strong><span>区域化重绘</span></div>
        <div className="redraw-fullscreen-actions">
          <span className="redraw-region-count">{intentRegions.length ? `${intentRegions.length} 个区域` : '先标记区域'}</span>
          <button type="button" aria-label="退出重绘" title="退出重绘" onClick={onClose}><X size={18} /></button>
        </div>
      </header>

      <div className="redraw-fullscreen-toolbar nodrag nowheel">
        <div className="redraw-tool-group">
          <button title="选择区域" className={tool === 'select' ? 'is-active' : ''} onClick={() => setTool('select')}><MousePointer2 size={17} /></button>
          <button title="涂鸦" className={tool === 'brush' ? 'is-active' : ''} onClick={() => setTool('brush')}><Brush size={17} /></button>
          <button title="框选" className={tool === 'rectangle' ? 'is-active' : ''} onClick={() => setTool('rectangle')}><RectangleHorizontal size={17} /></button>
        </div><i />
        {tool === 'brush' && <label className="redraw-width" title={`线宽 ${brushWidth}`}><span style={{ height: Math.max(2, brushWidth / 9) }} /><input aria-label="涂鸦笔大小" type="range" min="1" max="100" value={brushWidth} onChange={(event) => setBrushWidth(Number(event.target.value))} /></label>}
        {tool === 'brush' && <i />}
        <button title="撤销" disabled={!past.length} onClick={undo}><Undo2 size={17} /></button>
        <button title="重做" disabled={!future.length} onClick={redo}><Redo2 size={17} /></button><i />
        <button className="redraw-execute" disabled={!intentRegions.length || submitting} onClick={() => {
          setExecutionError(null);
          setExecutionOpen(true);
        }} title="执行重绘"><Sparkles size={15} />执行重绘</button>
      </div>

      <ContainedRedrawStage imageAspect={asset.width / asset.height}>
        {() => <>
          {assetUrl
            ? <img className="redraw-fullscreen-image" src={assetUrl} alt={asset.name} draggable={false} />
            : <div className="fullscreen-media-loading">正在解码原图…</div>}
          <RedrawMaskOverlay
            marks={marks}
            tool={tool}
            width={brushWidth}
            imageWidth={asset.width}
            imageHeight={asset.height}
            selectedId={selectedId}
            onHover={setHoveredId}
            onSelect={(id) => {
              if (!id) {
                setSelectedId(null);
                setEditingId(null);
                return;
              }
              const mark = marks.find((candidate) => candidate.id === id);
              if (mark) openRegion(mark);
            }}
            onGestureStart={() => remember()}
            onCommit={commitGesture}
          />
          {editingMark && <RegionEditor
            mark={editingMark}
            index={Math.max(0, marks.filter((mark) => mark.operation === 'paint').findIndex((mark) => mark.id === editingMark.id))}
            intent={draftIntent}
            color={draftColor}
            onIntent={setDraftIntent}
            onColor={setDraftColor}
            onConfirm={confirmRegion}
            onCancel={closeRegion}
            onDelete={deleteRegion}
          />}
        </>}
      </ContainedRedrawStage>
    </div>, document.body)}

    {executionOpen && createPortal(<div className="redraw-execution-dialog" role="dialog" aria-modal="true" aria-label="执行重绘" onClick={(event) => { if (event.target === event.currentTarget) setExecutionOpen(false); }}>
      <section>
        <header>
          <div><strong>执行重绘</strong><span>{intentRegions.length} 个区域，单次图生图请求</span></div>
          <button type="button" aria-label="关闭执行重绘" onClick={() => setExecutionOpen(false)}><X size={18} /></button>
        </header>
        <div className="redraw-execution-prompt">{buildRedrawPrompt(redrawDocument)}</div>
        <GenFooter
          nodeId="redraw-execution-draft"
          kind="imageGen"
          data={executionData}
          updateNode={updateExecutionData}
          useCallbacks={{ generating: submitting, onGenerate: () => void execute() }}
          genText="执行重绘"
          referenceCount={1}
          modelFilter={supportsImageToImageModel}
          hideAspectRatio
        />
        {executionError && <div className="redraw-execution-error" role="status">{executionError}</div>}
      </section>
    </div>, document.body)}
  </>;
}

function supportsImageToImageModel(model: { params?: string }) {
  try {
    const params = model.params ? JSON.parse(model.params) as { spec?: { modes?: unknown[] } } : {};
    const modes = params.spec?.modes;
    if (!Array.isArray(modes) || !modes.length) return true;
    return modes.map(String).includes('image-to-image');
  } catch {
    return true;
  }
}
