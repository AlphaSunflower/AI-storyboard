import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Background, BackgroundVariant, ConnectionLineType, Panel, ReactFlow, ReactFlowProvider,
  useReactFlow, useStore, useStoreApi, type Viewport,
} from '@xyflow/react';
import { Check, ChevronDown, CircleHelp, Download, FileArchive, FolderOpen, Grid2X2, Layers, LayoutList, Map, MessageCircle, MousePointer2, Plus, Scissors, Search, Trash2 } from 'lucide-react';
import { getAsset, saveAsset, saveAssets, saveAssetToLibrary } from '../services/projectStorage';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import type { CanvasNode, CanvasSummary, MediaAsset, PersistedAsset, ProjectSummary, SaveStatus } from '../types';
import { createImageAsset, createVideoAsset, disposeRuntimeAsset, getMediaNodeSize, getVideoNodeSize, processWithConcurrency } from '../utils/mediaPipeline';
import { createId } from '../utils/id';
import { mediaQualityForZoom } from '../utils/canvasPerformance';
import { refreshMediaBudget, setMediaBudgetQuality } from '../utils/mediaBudget';
import { CanvasEdge } from './CanvasEdge';
import { CanvasContextMenu, type CanvasContextTarget } from './CanvasContextMenu';
import { AssetDrawer, ASSET_DRAG_MIME } from './AssetDrawer';
import { MediaNode } from './MediaNode';
import { VideoNode } from './VideoNode';
import { NodeLibrary } from './NodeLibrary';
import { TemplatePanel } from './TemplatePanel';

const nodeTypes = { media: MediaNode, video: VideoNode };
const edgeTypes = { canvas: CanvasEdge };

type StableMiniMapProps = { nodes: CanvasNode[]; initialViewport: Viewport; isMoving: boolean };

type MiniMapRect = { x: number; y: number; width: number; height: number };
type MiniMapBounds = MiniMapRect;

const fitMiniMapBounds = (rects: MiniMapRect[], aspect: number): MiniMapBounds => {
  const source = rects.length ? rects : [{ x: 0, y: 0, width: 1, height: 1 }];
  const minX = Math.min(...source.map((rect) => rect.x));
  const minY = Math.min(...source.map((rect) => rect.y));
  const maxX = Math.max(...source.map((rect) => rect.x + Math.max(1, rect.width)));
  const maxY = Math.max(...source.map((rect) => rect.y + Math.max(1, rect.height)));
  const span = Math.max(maxX - minX, maxY - minY, 1);
  const padding = Math.max(120, span * .14);
  const paddedWidth = Math.max(1, maxX - minX + padding * 2);
  const paddedHeight = Math.max(1, maxY - minY + padding * 2);
  const width = Math.max(paddedWidth, paddedHeight * aspect);
  const height = Math.max(paddedHeight, width / aspect);
  return { x: (minX + maxX) / 2 - width / 2, y: (minY + maxY) / 2 - height / 2, width, height };
};

const expandMiniMapBounds = (current: MiniMapBounds, incoming: MiniMapRect, aspect: number): MiniMapBounds => {
  const marginX = Math.max(40, incoming.width * .06);
  const marginY = Math.max(40, incoming.height * .06);
  const minX = Math.min(current.x, incoming.x - marginX);
  const minY = Math.min(current.y, incoming.y - marginY);
  const maxX = Math.max(current.x + current.width, incoming.x + incoming.width + marginX);
  const maxY = Math.max(current.y + current.height, incoming.y + incoming.height + marginY);
  const rawWidth = Math.max(1, maxX - minX);
  const rawHeight = Math.max(1, maxY - minY);
  const width = Math.max(rawWidth, rawHeight * aspect);
  const height = Math.max(rawHeight, width / aspect);
  return { x: (minX + maxX) / 2 - width / 2, y: (minY + maxY) / 2 - height / 2, width, height };
};

const rectContains = (outer: MiniMapRect, inner: MiniMapRect) => {
  const marginX = outer.width * .015;
  const marginY = outer.height * .015;
  return inner.x >= outer.x + marginX
    && inner.y >= outer.y + marginY
    && inner.x + inner.width <= outer.x + outer.width - marginX
    && inner.y + inner.height <= outer.y + outer.height - marginY;
};

const miniMapNodeRect = (node: CanvasNode): MiniMapRect => ({
  x: node.position.x,
  y: node.position.y,
  width: node.width ?? node.measured?.width ?? (node.data.kind === 'video' ? 420 : node.data.kind === 'text' ? 380 : 250),
  height: node.height ?? node.measured?.height ?? (node.data.kind === 'video' ? 236 : node.data.kind === 'text' ? 280 : 250),
});

const MiniMapNodes = memo(function MiniMapNodes({ nodes }: { nodes: CanvasNode[] }) {
  return <>{nodes.filter((node) => !node.hidden).map((node) => {
    const rect = miniMapNodeRect(node);
    return <rect key={node.id} className="react-flow__minimap-node" x={rect.x} y={rect.y} width={rect.width} height={rect.height} rx={4} />;
  })}</>;
});

function MiniMapViewport() {
  const flowStore = useStoreApi();
  const rectRef = useRef<SVGRectElement>(null);
  const initial = flowStore.getState();
  const initialViewport = {
    x: -initial.transform[0] / initial.transform[2],
    y: -initial.transform[1] / initial.transform[2],
    width: initial.width / initial.transform[2],
    height: initial.height / initial.transform[2],
  };
  useEffect(() => {
    const update = (state: ReturnType<typeof flowStore.getState>) => {
      const rect = rectRef.current;
      if (!rect) return;
      const zoom = state.transform[2];
      rect.setAttribute('x', String(-state.transform[0] / zoom));
      rect.setAttribute('y', String(-state.transform[1] / zoom));
      rect.setAttribute('width', String(state.width / zoom));
      rect.setAttribute('height', String(state.height / zoom));
    };
    const unsubscribe = flowStore.subscribe(update);
    update(flowStore.getState());
    return () => {
      unsubscribe();
    };
  }, [flowStore]);
  return <rect ref={rectRef} className="canvas-minimap-viewport" x={initialViewport.x} y={initialViewport.y} width={initialViewport.width} height={initialViewport.height} />;
}

function StableMiniMap({ nodes, initialViewport, isMoving }: StableMiniMapProps) {
  const flowWidth = useStore((state) => state.width);
  const flowHeight = useStore((state) => state.height);
  const flowStore = useStoreApi();
  const { setCenter } = useReactFlow();
  const draggingRef = useRef(false);
  const frameRef = useRef<number | null>(null);
  const pendingPointRef = useRef<{ x: number; y: number } | null>(null);
  const mapWidth = 184;
  const mapHeight = 126;
  const mapAspect = mapWidth / mapHeight;
  // `initialViewport` is a project snapshot. App updates it after every saved
  // pan/zoom, so reading it directly here would make the map's coordinate
  // system follow the live camera and cause the frame to jump or resize.
  const openingViewportRef = useRef<Viewport>(initialViewport);
  const boundsRef = useRef<MiniMapBounds | null>(null);
  const baseKeyRef = useRef('');
  const graphKeyRef = useRef('');
  const nodeRects = useMemo(() => nodes.filter((node) => !node.hidden).map(miniMapNodeRect), [nodes]);
  const openingViewport = useMemo(() => {
    const zoom = Math.max(.05, openingViewportRef.current.zoom);
    return {
      x: -openingViewportRef.current.x / zoom,
      y: -openingViewportRef.current.y / zoom,
      width: Math.max(1, flowWidth) / zoom,
      height: Math.max(1, flowHeight) / zoom,
    };
  }, [flowHeight, flowWidth]);
  const baseKey = useMemo(() => `${nodes.map((node) => `${node.id}:${node.position.x}:${node.position.y}:${node.width ?? node.measured?.width ?? ''}:${node.height ?? node.measured?.height ?? ''}:${node.hidden ? 1 : 0}`).join('|')}|${Math.round(flowWidth)}|${Math.round(flowHeight)}`, [flowHeight, flowWidth, nodes]);
  // Only a structural node-set or canvas-size change may reset the range.
  // Position and delayed measurement changes are merged monotonically below,
  // otherwise virtualized nodes can make the map shrink after a pan.
  const graphKey = useMemo(() => `${nodes.map((node) => `${node.id}:${node.hidden ? 1 : 0}`).join('|')}|${Math.round(flowWidth)}|${Math.round(flowHeight)}`, [flowHeight, flowWidth, nodes]);
  const baseBounds = useMemo(() => fitMiniMapBounds([...nodeRects, openingViewport], mapAspect), [mapAspect, nodeRects, openingViewport]);
  // Read the latest camera only when the stable parent needs to decide whether
  // to expand its world bounds. The high-frequency frame itself lives in
  // MiniMapViewport and subscribes directly to React Flow's transform store.
  const stableTransform = flowStore.getState().transform;
  const stableViewport = { x: -stableTransform[0] / stableTransform[2], y: -stableTransform[1] / stableTransform[2], width: flowWidth / stableTransform[2], height: flowHeight / stableTransform[2] };
  const worldBounds = useMemo(() => {
    let next = boundsRef.current;
    if (!next || graphKeyRef.current !== graphKey) {
      next = baseBounds;
    } else if (baseKeyRef.current !== baseKey) {
      // React Flow may fill measured dimensions a few frames after mount.
      // Merge that new base range without shrinking the established map.
      next = expandMiniMapBounds(next, baseBounds, mapAspect);
    }
    baseKeyRef.current = baseKey;
    graphKeyRef.current = graphKey;
    // Expand only when the live camera reaches the edge. This keeps the map
    // stable during normal panning while still handling zoom-out and travel
    // beyond the original node cluster.
    if (flowWidth > 0 && flowHeight > 0 && !isMoving && !rectContains(next, stableViewport)) {
      next = expandMiniMapBounds(next, {
        x: stableViewport.x,
        y: stableViewport.y,
        width: Math.max(1, stableViewport.width),
        height: Math.max(1, stableViewport.height),
      }, mapAspect);
    }
    boundsRef.current = next;
    return next;
  }, [baseBounds, baseKey, flowHeight, flowWidth, graphKey, isMoving, mapAspect, stableViewport.x, stableViewport.y, stableViewport.width, stableViewport.height]);
  useEffect(() => () => {
    if (frameRef.current !== null) cancelAnimationFrame(frameRef.current);
  }, []);
  const navigateTo = (element: SVGSVGElement, clientX: number, clientY: number) => {
    const rect = element.getBoundingClientRect();
    pendingPointRef.current = {
      x: worldBounds.x + ((clientX - rect.left) / rect.width) * worldBounds.width,
      y: worldBounds.y + ((clientY - rect.top) / rect.height) * worldBounds.height,
    };
    if (frameRef.current !== null) return;
    frameRef.current = requestAnimationFrame(() => {
      frameRef.current = null;
      const point = pendingPointRef.current;
      if (point) setCenter(point.x, point.y, { zoom: flowStore.getState().transform[2], duration: 0 });
    });
  };
  return <Panel position="bottom-left" className="canvas-minimap" aria-label="画布导航小地图">
    <svg className="canvas-minimap-svg" width={mapWidth} height={mapHeight} viewBox={`${worldBounds.x} ${worldBounds.y} ${worldBounds.width} ${worldBounds.height}`} role="img" aria-label="画布导航小地图"
      onPointerDown={(event) => { event.preventDefault(); draggingRef.current = true; event.currentTarget.setPointerCapture(event.pointerId); navigateTo(event.currentTarget, event.clientX, event.clientY); }}
      onPointerMove={(event) => { if (draggingRef.current) navigateTo(event.currentTarget, event.clientX, event.clientY); }}
      onPointerUp={(event) => { draggingRef.current = false; if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId); }}
      onPointerCancel={() => { draggingRef.current = false; }}>
      <MiniMapNodes nodes={nodes} />
      <MiniMapViewport />
    </svg>
  </Panel>;
}

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

const imageBlobForClipboard = async (blob: Blob) => {
  if (blob.type === 'image/png') return blob;
  const bitmap = await createImageBitmap(blob);
  try {
    const canvas = document.createElement('canvas');
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('浏览器无法创建图片剪贴板');
    context.drawImage(bitmap, 0, 0);
    return await new Promise<Blob>((resolve, reject) => canvas.toBlob((result) => result ? resolve(result) : reject(new Error('图片转码失败')), 'image/png'));
  } finally {
    bitmap.close();
  }
};

interface CanvasWorkspaceProps {
  projectId: string;
  projectTitle: string;
  canvasId: string;
  canvasTitle: string;
  canvases: CanvasSummary[];
  projects: ProjectSummary[];
  saveStatus: SaveStatus;
  initialViewport: Viewport;
  onProjectTitleChange: (title: string) => void;
  onViewportChange: (viewport: Viewport) => void;
  onOpenHome: () => void;
  onCreateProject: () => void;
  onSwitchProject: (projectId: string) => void;
  onSwitchCanvas: (canvasId: string) => void;
  onNewCanvas: () => void;
  onDeleteProject: () => void;
  onOpenAgent: () => void;
  onOpenInspector: () => void;
  onExportProject: () => void;
  isExporting: boolean;
  onNotify?: (message: string) => void;
}

const saveLabel: Record<SaveStatus, string> = { idle: '本地项目', saving: '保存中…', saved: '已保存到本地', error: '保存失败' };

function CanvasInner(props: CanvasWorkspaceProps) {
  const { projectId, projectTitle, canvasId, canvasTitle, canvases, projects, saveStatus, initialViewport, onProjectTitleChange, onViewportChange, onOpenHome, onCreateProject, onSwitchProject, onSwitchCanvas, onNewCanvas, onDeleteProject, onOpenAgent, onOpenInspector, onExportProject, isExporting, onNotify } = props;
  const [panel, setPanel] = useState<'nodes' | 'templates' | null>(null);
  const [showGrid, setShowGrid] = useState(true);
  const [showMinimap, setShowMinimap] = useState(false);
  const [workspaceMenuOpen, setWorkspaceMenuOpen] = useState(false);
  const [projectSwitcherOpen, setProjectSwitcherOpen] = useState(false);
  const [canvasSwitcherOpen, setCanvasSwitcherOpen] = useState(false);
  const [assetDrawerOpen, setAssetDrawerOpen] = useState(false);
  const [projectSearch, setProjectSearch] = useState('');
  const [renameDialogOpen, setRenameDialogOpen] = useState(false);
  const [renameDraft, setRenameDraft] = useState(projectTitle);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [isImporting, setIsImporting] = useState(false);
  const [isFileDragActive, setIsFileDragActive] = useState(false);
  const [isViewportMoving, setIsViewportMoving] = useState(false);
  const [contextTarget, setContextTarget] = useState<CanvasContextTarget | null>(null);
  const dragDepthRef = useRef(0);
  const viewportFrameRef = useRef<number | null>(null);
  const pendingViewportRef = useRef<Viewport | null>(null);
  const mediaQualityRef = useRef(useMediaStore.getState().quality);
  const mobileFitDoneRef = useRef(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const zoom = useStore((state) => Math.round(state.transform[2] * 100));
  const nodes = useCanvasStore((state) => state.nodes);
  const edges = useCanvasStore((state) => state.edges);
  const onNodesChange = useCanvasStore((state) => state.onNodesChange);
  const onEdgesChange = useCanvasStore((state) => state.onEdgesChange);
  const beginNodeDrag = useCanvasStore((state) => state.beginNodeDrag);
  const endNodeDrag = useCanvasStore((state) => state.endNodeDrag);
  const connect = useCanvasStore((state) => state.connect);
  const selectNode = useCanvasStore((state) => state.selectNode);
  const addNode = useCanvasStore((state) => state.addNode);
  const addImportedImages = useCanvasStore((state) => state.addImportedImages);
  const addImportedVideos = useCanvasStore((state) => state.addImportedVideos);
  const undo = useCanvasStore((state) => state.undo);
  const redo = useCanvasStore((state) => state.redo);
  const copySelected = useCanvasStore((state) => state.copySelected);
  const pasteClipboard = useCanvasStore((state) => state.pasteClipboard);
  const deleteSelected = useCanvasStore((state) => state.deleteSelected);
  const deleteEdge = useCanvasStore((state) => state.deleteEdge);
  const duplicateSelected = useCanvasStore((state) => state.duplicateSelected);
  const canPaste = useCanvasStore((state) => Boolean(state.clipboard?.nodes.length));
  const canUndo = useCanvasStore((state) => state.past.length > 0);
  const canRedo = useCanvasStore((state) => state.future.length > 0);
  const { fitView, screenToFlowPosition, zoomTo } = useReactFlow();
  const flowStore = useStoreApi();
  const upsertAssets = useMediaStore((state) => state.upsertAssets);
  const setMediaQuality = useMediaStore((state) => state.setQuality);

  const notify = useCallback((message: string) => {
    setNotice(message); onNotify?.(message);
    window.setTimeout(() => setNotice(null), 1800);
  }, [onNotify]);
  const visibleProjects = useMemo(() => projects.map((project) => project.id === projectId ? { ...project, title: projectTitle } : project), [projectId, projectTitle, projects]);
  const filteredProjects = useMemo(() => {
    const query = projectSearch.trim().toLocaleLowerCase();
    return query ? visibleProjects.filter((project) => project.title.toLocaleLowerCase().includes(query)) : visibleProjects;
  }, [projectSearch, visibleProjects]);
  const closeProjectMenus = useCallback(() => {
    setWorkspaceMenuOpen(false);
    setProjectSwitcherOpen(false);
    setCanvasSwitcherOpen(false);
  }, []);
  useEffect(() => {
    const listener = (event: Event) => notify((event as CustomEvent<string>).detail);
    window.addEventListener('morrow:notice', listener);
    return () => window.removeEventListener('morrow:notice', listener);
  }, [notify]);

  const closeContextMenu = useCallback(() => setContextTarget(null), []);
  const contextNode = contextTarget?.type === 'node' ? contextTarget.node : null;
  const contextAssetId = contextNode?.data.assetId;
  const withContextAsset = useCallback(async (action: (asset: PersistedAsset) => Promise<void> | void) => {
    if (!contextAssetId) { notify('请先上传真实图片'); return; }
    const asset = await getAsset(contextAssetId);
    if (!asset) { notify('真实素材不存在或已损坏'); return; }
    await action(asset);
  }, [contextAssetId, notify]);

  const saveContextAsset = useCallback(() => void withContextAsset(async (asset) => {
    await saveAssetToLibrary(asset.id);
    notify('真实素材已保存到跨项目素材库');
  }).catch((error) => notify(error instanceof Error ? error.message : '保存素材失败')), [notify, withContextAsset]);

  const downloadContextAsset = useCallback(() => void withContextAsset((asset) => downloadBlob(asset.originalBlob, asset.name)), [withContextAsset]);

  const copyContextImage = useCallback(() => void withContextAsset(async (asset) => {
    if (!window.isSecureContext || !navigator.clipboard?.write || typeof ClipboardItem === 'undefined') throw new Error('当前浏览器未开放图片剪贴板权限');
    const blob = await imageBlobForClipboard(asset.originalBlob);
    await navigator.clipboard.write([new ClipboardItem({ [blob.type]: blob })]);
    notify('图片已复制到系统剪贴板');
  }).catch((error) => notify(error instanceof Error ? error.message : '复制图片失败')), [notify, withContextAsset]);

  const flattenContextAsset = useCallback(() => void withContextAsset(async (asset) => {
    if (!contextNode || !asset.derivedFrom) throw new Error('当前节点没有需要合并的派生历史');
    const flattened: PersistedAsset = {
      ...asset,
      id: createId(),
      name: `${asset.name.replace(/\.[^.]+$/, '')}-flattened.${asset.mimeType === 'image/png' ? 'png' : asset.mimeType === 'image/webp' ? 'webp' : 'jpg'}`,
      derivedFrom: undefined,
      operation: { type: 'flatten', params: { sourceAssetId: asset.id } },
      libraryState: 'project',
      createdAt: Date.now(),
    };
    await saveAsset(flattened);
    upsertAssets([flattened]);
    useCanvasStore.getState().createDerivedNodes(contextNode.id, [{
      data: { assetId: flattened.id, label: `${contextNode.data.label} 已应用历史`, operation: flattened.operation },
      size: { width: contextNode.width ?? 250, height: contextNode.height ?? 250 },
    }]);
    notify('已创建应用全部历史的独立节点');
  }).catch((error) => notify(error instanceof Error ? error.message : '应用历史失败')), [contextNode, notify, upsertAssets, withContextAsset]);

  const syncMediaViewportNow = useCallback((viewport?: Viewport) => {
    const state = flowStore.getState(); const transform = state.transform;
    const current = viewport ?? { x: transform[0], y: transform[1], zoom: transform[2] };
    // Hysteresis avoids repeatedly swapping small/medium media near the threshold.
    const previous = mediaQualityRef.current;
    const next = mediaQualityForZoom(current.zoom, previous);
    if (next !== previous) { mediaQualityRef.current = next; setMediaQuality(next); setMediaBudgetQuality(next); }
  }, [flowStore, setMediaQuality]);

  const scheduleMediaViewport = useCallback((viewport?: Viewport) => {
    if (document.visibilityState === 'hidden') return;
    if (viewport) pendingViewportRef.current = viewport;
    if (viewportFrameRef.current !== null) return;
    viewportFrameRef.current = requestAnimationFrame(() => {
      viewportFrameRef.current = null;
      const pending = pendingViewportRef.current;
      pendingViewportRef.current = null;
      syncMediaViewportNow(pending ?? undefined);
      refreshMediaBudget();
    });
  }, [syncMediaViewportNow]);

  useEffect(() => {
    scheduleMediaViewport(initialViewport);
    return () => { if (viewportFrameRef.current !== null) cancelAnimationFrame(viewportFrameRef.current); };
  }, [initialViewport, scheduleMediaViewport]);
  useEffect(() => {
    const resume = () => { if (document.visibilityState === 'visible') scheduleMediaViewport(); };
    document.addEventListener('visibilitychange', resume);
    return () => document.removeEventListener('visibilitychange', resume);
  }, [scheduleMediaViewport]);
  useEffect(() => {
    if (mobileFitDoneRef.current || !nodes.length || window.innerWidth > 740) return;
    mobileFitDoneRef.current = true;
    const frame = requestAnimationFrame(() => void fitView({ padding: .24, duration: 0 }));
    return () => cancelAnimationFrame(frame);
  }, [fitView, nodes.length]);

  const importMedia = useCallback(async (fileList: FileList | File[], anchor?: { x: number; y: number }) => {
    const files = Array.from(fileList);
    if (!files.length) return;
    const state = flowStore.getState(); const center = anchor ?? screenToFlowPosition({ x: state.width / 2, y: state.height / 2 });
    const columns = Math.min(3, Math.max(1, Math.ceil(Math.sqrt(files.length))));
    const completed: Array<{ asset: MediaAsset; index: number }> = [];
    const errors: string[] = [];
    setIsImporting(true); notify(`正在处理 ${files.length} 张图片`);
    await processWithConcurrency(files, 2, async (file, index) => {
      let asset: MediaAsset | null = null;
      try { asset = file.type.startsWith('video/') ? await createVideoAsset(file) : await createImageAsset(file); completed.push({ asset, index }); }
      catch (error) { if (asset) disposeRuntimeAsset(asset); errors.push(`${file.name}: ${error instanceof Error ? error.message : '处理失败'}`); }
    });
    completed.sort((a, b) => a.index - b.index);
    completed.forEach(({ asset }) => {
      if (asset.projectId === undefined) asset.projectId = projectId;
      if (asset.canvasId === undefined) asset.canvasId = canvasId;
    });
    try {
      await saveAssets(completed.map(({ asset }) => asset));
      upsertAssets(completed.map(({ asset }) => asset));
    } catch (error) {
      completed.forEach(({ asset }) => disposeRuntimeAsset(asset));
      setIsImporting(false);
      notify(error instanceof Error ? error.message : '素材保存失败');
      return;
    }
    const positioned = completed.map(({ asset }, order) => ({
      assetId: asset.id, label: asset.name.replace(/\.[^.]+$/, ''), size: asset.kind === 'video' ? getVideoNodeSize(asset.width, asset.height) : getMediaNodeSize(asset.width, asset.height),
      position: { x: center.x + (order % columns) * 430 - ((columns - 1) * 430) / 2, y: center.y + Math.floor(order / columns) * 390 },
      kind: asset.kind,
    }));
    const images = positioned.filter((item) => item.kind === 'image');
    const videos = positioned.filter((item) => item.kind === 'video');
    if (images.length) addImportedImages(images);
    if (videos.length) addImportedVideos(videos);
    setIsImporting(false); scheduleMediaViewport();
    notify(errors.length ? `已导入 ${completed.length} 个素材，${errors.length} 个失败：${errors[0]}` : `已导入 ${completed.length} 个真实素材`);
  }, [addImportedImages, addImportedVideos, canvasId, flowStore, notify, projectId, scheduleMediaViewport, screenToFlowPosition, upsertAssets]);

  const addAssetAt = useCallback(async (assetId: string, position: { x: number; y: number }) => {
    const asset = await getAsset(assetId);
    if (!asset) return;
    const item = {
      assetId: asset.id,
      label: asset.name.replace(/\.[^.]+$/, ''),
      size: asset.kind === 'video' ? getVideoNodeSize(asset.width, asset.height) : getMediaNodeSize(asset.width, asset.height),
      position,
      kind: asset.kind,
    };
    if (asset.kind === 'image') addImportedImages([item]);
    else addImportedVideos([item]);
  }, [addImportedImages, addImportedVideos]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.target as HTMLElement).matches('input, textarea, select, [contenteditable="true"]')) return;
      if (event.key === 'Escape' && contextTarget) {
        event.preventDefault();
        event.stopImmediatePropagation();
        closeContextMenu();
        return;
      }
      if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); deleteSelected(); }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'd') { event.preventDefault(); duplicateSelected(); }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'c') { event.preventDefault(); copySelected(); }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'v') { event.preventDefault(); pasteClipboard(); }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') { event.preventDefault(); event.shiftKey ? redo() : undo(); }
    };
    addEventListener('keydown', onKeyDown, true); return () => removeEventListener('keydown', onKeyDown, true);
  }, [closeContextMenu, contextTarget, copySelected, deleteSelected, duplicateSelected, pasteClipboard, redo, undo]);

  useEffect(() => {
    if (!workspaceMenuOpen && !projectSwitcherOpen) return;
    const close = (event: PointerEvent) => {
      if (!(event.target as Element).closest('.canvas-project-bar')) closeProjectMenus();
    };
    addEventListener('pointerdown', close, true);
    return () => removeEventListener('pointerdown', close, true);
  }, [closeProjectMenus, projectSwitcherOpen, workspaceMenuOpen]);

  useEffect(() => {
    if (!renameDialogOpen) setRenameDraft(projectTitle);
  }, [projectTitle, renameDialogOpen]);

  useEffect(() => {
    if (contextTarget?.type !== 'edge') return;
    const closeEdgeAction = (event: PointerEvent) => {
      if (!(event.target as Element).closest('.canvas-edge-context-action')) closeContextMenu();
    };
    addEventListener('pointerdown', closeEdgeAction, true);
    addEventListener('wheel', closeContextMenu, { passive: true });
    return () => {
      removeEventListener('pointerdown', closeEdgeAction, true);
      removeEventListener('wheel', closeContextMenu);
    };
  }, [closeContextMenu, contextTarget]);

  const defaultEdgeOptions = useMemo(() => ({ type: 'canvas' }), []);

  return (
    <main className={`canvas-workspace ${isImporting ? 'is-importing-media' : ''} ${isFileDragActive ? 'is-file-drag-active' : ''} ${isViewportMoving ? 'is-viewport-moving' : ''}`}
      onDragEnter={(event) => { if (event.dataTransfer.types.includes('Files')) { dragDepthRef.current += 1; setIsFileDragActive(true); } }}
      onDragOver={(event) => { if (event.dataTransfer.types.includes('Files')) event.preventDefault(); }}
      onDragLeave={() => { dragDepthRef.current = Math.max(0, dragDepthRef.current - 1); if (!dragDepthRef.current) setIsFileDragActive(false); }}
      onDrop={(event) => { event.preventDefault(); dragDepthRef.current = 0; setIsFileDragActive(false); const assetId = event.dataTransfer.getData(ASSET_DRAG_MIME); if (assetId) { void addAssetAt(assetId, screenToFlowPosition({ x: event.clientX, y: event.clientY })); return; } if (event.dataTransfer.files.length) void importMedia(event.dataTransfer.files, screenToFlowPosition({ x: event.clientX, y: event.clientY })); }}>
      <input ref={fileInputRef} className="canvas-file-input" type="file" accept="image/png,image/jpeg,image/webp,video/mp4,video/webm,video/quicktime" multiple onChange={(event) => { if (event.target.files) void importMedia(event.target.files); event.target.value = ''; }} />
      <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes} onNodesChange={onNodesChange} onEdgesChange={onEdgesChange} onConnect={connect}
        onNodeDragStart={beginNodeDrag} onNodeDragStop={endNodeDrag}
        onNodeClick={(event, node) => { closeContextMenu(); selectNode(node.id, event.shiftKey || event.ctrlKey || event.metaKey); }} onPaneClick={(event) => { closeContextMenu(); selectNode(null); if (event.detail === 2) { const point = screenToFlowPosition({ x: event.clientX, y: event.clientY }); addNode('text', { x: point.x - 190, y: point.y - 140 }); } }}
        onPaneContextMenu={(event) => { event.preventDefault(); setContextTarget({ type: 'pane', clientX: event.clientX, clientY: event.clientY, flowPosition: screenToFlowPosition({ x: event.clientX, y: event.clientY }) }); }}
        onNodeContextMenu={(event, node) => { event.preventDefault(); if (!node.selected) selectNode(node.id); setContextTarget({ type: 'node', node, clientX: event.clientX, clientY: event.clientY, flowPosition: screenToFlowPosition({ x: event.clientX, y: event.clientY }) }); }}
        onEdgeContextMenu={(event, edge) => { event.preventDefault(); setContextTarget({ type: 'edge', edgeId: edge.id, clientX: event.clientX, clientY: event.clientY, flowPosition: screenToFlowPosition({ x: event.clientX, y: event.clientY }) }); }}
        defaultEdgeOptions={defaultEdgeOptions} connectionLineType={ConnectionLineType.Bezier} minZoom={.18} maxZoom={2.4} defaultViewport={initialViewport}
        deleteKeyCode={null} colorMode="dark" proOptions={{ hideAttribution: true }}
        onMoveStart={() => { closeContextMenu(); setIsViewportMoving(true); }}
        onMove={(_, viewport) => scheduleMediaViewport(viewport)}
        onMoveEnd={(_, viewport) => { setIsViewportMoving(false); scheduleMediaViewport(viewport); onViewportChange(viewport); }}
        onlyRenderVisibleElements={nodes.length > 320}>
        {showGrid && <Background variant={BackgroundVariant.Dots} gap={28} size={1} color="rgba(255,255,255,.10)" />}
        {showMinimap && <StableMiniMap key={projectId} nodes={nodes} initialViewport={initialViewport} isMoving={isViewportMoving} />}
        <Panel className={`canvas-project-bar ${workspaceMenuOpen || projectSwitcherOpen ? 'is-menu-open' : ''}`} position="top-left">
          <button className="workspace-menu-trigger" aria-label="本地工作区菜单" aria-expanded={workspaceMenuOpen} onClick={() => { setWorkspaceMenuOpen((open) => !open); setProjectSwitcherOpen(false); setCanvasSwitcherOpen(false); }}><ChevronDown size={16} /></button>
          <i className="project-bar-divider" />
          <button className="project-switcher-trigger canvas-switcher-trigger" aria-label="切换画布" aria-expanded={canvasSwitcherOpen} onClick={() => { setCanvasSwitcherOpen((open) => !open); setProjectSwitcherOpen(false); setWorkspaceMenuOpen(false); }}><Layers size={13} /><span>{canvasTitle || '画布'}</span><ChevronDown size={13} /></button>
          {canvasSwitcherOpen && <div className="project-switcher canvas-switcher" role="listbox" aria-label="切换画布">
            <div className="project-switcher-list" role="listbox">
              {canvases.map((item) => <button role="option" aria-selected={item.id === canvasId} className={item.id === canvasId ? 'is-current' : ''} key={item.id} onClick={() => { closeProjectMenus(); if (item.id !== canvasId) onSwitchCanvas(item.id); }}><span className="project-switcher-copy"><strong>{item.title}</strong><small>{item.nodeCount} 个节点 · {item.assetCount} 项素材</small></span>{item.id === canvasId && <Check size={15} />}</button>)}
            </div>
            <button className="project-switcher-create" onClick={() => { closeProjectMenus(); onNewCanvas(); }}><Plus size={15} /> 新建画布</button>
          </div>}
          <i className="project-bar-divider" />
          <button className="project-switcher-trigger" aria-label="搜索和切换项目" aria-expanded={projectSwitcherOpen} onClick={() => { setProjectSwitcherOpen((open) => !open); setWorkspaceMenuOpen(false); setCanvasSwitcherOpen(false); setProjectSearch(''); }}><span>{projectTitle || 'Untitled'}</span><ChevronDown size={14} /></button>
          <span className={`project-save-indicator is-${saveStatus}`} role="status" aria-label={saveLabel[saveStatus]} title={saveLabel[saveStatus]}><Check size={14} /></span>
          <button className="project-inline-export" disabled={isExporting} onClick={onExportProject} aria-label="导出项目" title={isExporting ? '导出中…' : '导出项目'}><FileArchive size={15} /></button>
          <div className="project-copy project-compat-layer"><input aria-label="画布名称" value={projectTitle} onChange={(event) => onProjectTitleChange(event.target.value)} /><small className={saveStatus === 'error' ? 'is-error' : ''}>{saveLabel[saveStatus]}</small></div>
          {workspaceMenuOpen && <div className="workspace-menu" role="menu" aria-label="本地工作区菜单">
            <button role="menuitem" onClick={() => { closeProjectMenus(); onOpenHome(); }}><FolderOpen size={15} /><span>返回项目中心</span></button>
            <div className="project-menu-divider" />
            <div className="project-menu-section-label">项目</div>
            <button role="menuitem" onClick={() => { closeProjectMenus(); setRenameDraft(projectTitle); setRenameDialogOpen(true); }}><span>重命名</span></button>
            <button role="menuitem" onClick={() => { closeProjectMenus(); onCreateProject(); }}><Plus size={15} /><span>新建项目</span></button>
            <button role="menuitem" disabled={isExporting} onClick={() => { closeProjectMenus(); onExportProject(); }}><FileArchive size={15} /><span>{isExporting ? '导出中…' : '导出项目包'}</span><kbd>.morrow</kbd></button>
            <div className="project-menu-divider" />
            <button className="project-menu-danger" role="menuitem" onClick={() => { closeProjectMenus(); setDeleteDialogOpen(true); }}><Trash2 size={15} /><span>删除项目</span></button>
          </div>}
          {projectSwitcherOpen && <div className="project-switcher" role="dialog" aria-label="搜索和切换项目">
            <label className="project-switcher-search"><Search size={15} /><input autoFocus aria-label="搜索项目" placeholder="搜索项目" value={projectSearch} onChange={(event) => setProjectSearch(event.target.value)} onKeyDown={(event) => { if (event.key === 'Escape') closeProjectMenus(); }} /></label>
            <div className="project-switcher-list" role="tree" aria-label="本地项目">
              {filteredProjects.length ? filteredProjects.map((project) => <button role="treeitem" aria-selected={project.id === projectId} className={project.id === projectId ? 'is-current' : ''} key={project.id} onClick={() => { closeProjectMenus(); if (project.id !== projectId) onSwitchProject(project.id); }}><span className="project-switcher-thumb">{project.assetCount}</span><span className="project-switcher-copy"><strong>{project.title || 'Untitled'}</strong><small>{project.nodeCount} 个节点 · {new Date(project.updatedAt).toLocaleDateString('zh-CN')}</small></span>{project.id === projectId && <Check size={15} />}</button>) : <p>没有匹配的本地项目</p>}
            </div>
            <button className="project-switcher-create" onClick={() => { closeProjectMenus(); onCreateProject(); }}><Plus size={15} /> 新建项目</button>
          </div>}
        </Panel>
        <Panel className="canvas-tool-island" position="top-left"><button className={panel === 'nodes' ? 'primary-tool is-active' : 'primary-tool'} onClick={() => setPanel(panel === 'nodes' ? null : 'nodes')} aria-label="添加节点"><Plus size={26} /></button><div className="canvas-tool-group"><button className={assetDrawerOpen ? 'is-active' : ''} onClick={() => setAssetDrawerOpen((open) => !open)} aria-label="资产库"><Layers size={20} /></button><button onClick={() => setPanel(panel === 'nodes' ? null : 'nodes')} aria-label="节点库"><Search size={20} /></button><button onClick={() => setPanel(panel === 'templates' ? null : 'templates')} aria-label="结构模板"><Download size={20} /></button><button onClick={onOpenInspector} aria-label="节点属性"><LayoutList size={20} /></button><button onClick={onOpenAgent} aria-label="执行服务"><MessageCircle size={20} /></button></div></Panel>
        <Panel className="canvas-view-bar" position="bottom-left"><button className={showMinimap ? 'is-active' : ''} onClick={() => setShowMinimap(!showMinimap)} aria-label="小地图"><Map size={18} /></button><button className={showGrid ? 'is-active' : ''} onClick={() => setShowGrid(!showGrid)} aria-label="网格"><Grid2X2 size={18} /></button><button onClick={() => fitView({ padding: .22, duration: 300 })} aria-label="适应视图"><MousePointer2 size={18} /></button><input aria-label="画布缩放" type="range" min="18" max="240" value={zoom} onChange={(event) => void zoomTo(Number(event.target.value) / 100)} /><span>{zoom}%</span><i /><button onClick={() => notify('双击空白处创建文字节点；拖入图片可直接导入')} aria-label="帮助"><CircleHelp size={19} /></button></Panel>
        {notice && <Panel className="canvas-status-toast" position="bottom-center">{notice}</Panel>}
        {!nodes.length && <Panel className="canvas-empty-hint" position="top-left"><div><strong>空画布</strong><span>从真实素材或文字开始</span></div><section><button onClick={() => fileInputRef.current?.click()}>导入图片 / 视频</button><button onClick={() => addNode('text')}>创建文字</button><button onClick={() => addNode('image')}>创建图片占位</button><button onClick={() => addNode('video')}>创建视频占位</button></section></Panel>}
      </ReactFlow>
      {assetDrawerOpen && <AssetDrawer projectId={projectId} onClose={() => setAssetDrawerOpen(false)} />}
      {panel === 'nodes' && <NodeLibrary onClose={() => setPanel(null)} onImport={() => fileInputRef.current?.click()} />}
      {panel === 'templates' && <TemplatePanel onClose={() => setPanel(null)} />}
      {renameDialogOpen && <div className="project-dialog-backdrop" role="presentation" onPointerDown={(event) => { if (event.target === event.currentTarget) setRenameDialogOpen(false); }}><form className="project-dialog" role="dialog" aria-modal="true" aria-labelledby="rename-project-title" onSubmit={(event) => { event.preventDefault(); const title = renameDraft.trim(); if (!title) return; onProjectTitleChange(title); setRenameDialogOpen(false); }}><h2 id="rename-project-title">重命名项目</h2><p>请输入项目的新名称</p><input autoFocus aria-label="项目的新名称" value={renameDraft} onChange={(event) => setRenameDraft(event.target.value)} onKeyDown={(event) => { if (event.key === 'Escape') setRenameDialogOpen(false); }} /><footer><button type="button" onClick={() => setRenameDialogOpen(false)}>取消</button><button type="submit" disabled={!renameDraft.trim()}>确认</button></footer></form></div>}
      {deleteDialogOpen && <div className="project-dialog-backdrop" role="presentation" onPointerDown={(event) => { if (event.target === event.currentTarget) setDeleteDialogOpen(false); }}><div className="project-dialog project-delete-dialog" role="alertdialog" aria-modal="true" aria-labelledby="delete-project-title"><h2 id="delete-project-title">删除当前项目？</h2><p>项目、画布结构及仅由该项目引用的素材将从本地删除。此操作无法撤销。</p><footer><button type="button" onClick={() => setDeleteDialogOpen(false)}>取消</button><button className="is-danger" type="button" onClick={() => { setDeleteDialogOpen(false); onDeleteProject(); }}>确认删除</button></footer></div></div>}
      {contextTarget?.type === 'edge' && <button
        className="canvas-edge-context-action"
        style={{ left: contextTarget.clientX, top: contextTarget.clientY }}
        title="剪断连接"
        aria-label="剪断连接"
        onClick={() => { deleteEdge(contextTarget.edgeId); closeContextMenu(); }}
      ><Scissors size={18} /></button>}
      {contextTarget && contextTarget.type !== 'edge' && <CanvasContextMenu
        target={contextTarget}
        canPaste={canPaste}
        canUndo={canUndo}
        canRedo={canRedo}
        hasImageAsset={Boolean(contextNode?.data.assetId)}
        hasMediaAsset={Boolean(contextNode?.data.assetId)}
        canFlattenHistory={Boolean(contextNode?.data.assetId && contextNode?.data.derivedFrom)}
        onClose={closeContextMenu}
        onUpload={() => fileInputRef.current?.click()}
        onOpenAssets={() => setPanel('nodes')}
        onAddText={() => addNode('text', contextTarget.flowPosition)}
        onAddImage={() => addNode('image', contextTarget.flowPosition)}
        onUndo={undo}
        onRedo={redo}
        onCopy={() => { const count = copySelected(); if (count) notify(`已复制 ${count} 个节点`); }}
        onPaste={() => { const created = pasteClipboard(contextTarget.flowPosition); if (created.length) notify(`已粘贴 ${created.length} 个节点`); }}
        onDuplicate={() => { const created = duplicateSelected(); if (created.length) notify(`已创建 ${created.length} 个副本`); }}
        onDelete={deleteSelected}
        onSaveToLibrary={saveContextAsset}
        onFlattenHistory={flattenContextAsset}
        onDownload={downloadContextAsset}
        onCopyImage={copyContextImage}
      />}
    </main>
  );
}

export function CanvasWorkspace(props: CanvasWorkspaceProps) { return <ReactFlowProvider><CanvasInner {...props} /></ReactFlowProvider>; }
