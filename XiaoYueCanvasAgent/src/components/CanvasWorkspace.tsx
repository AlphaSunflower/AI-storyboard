import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import {
  Background, BackgroundVariant, ConnectionLineType, ControlButton, Controls, MiniMap, Panel, ReactFlow, ReactFlowProvider, SelectionMode,
  useReactFlow, useStore, useStoreApi, type Connection, type NodeProps, type Viewport,
} from '@xyflow/react';
import { Check, ChevronDown, CircleHelp, Copy, Download, FileArchive, FolderOpen, Grid2X2, History, Layers, LayoutList, LoaderCircle, Map, Maximize2, MessageCircle, Minimize2, MoreHorizontal, Network, Pencil, Plus, Scissors, Search, Trash2 } from 'lucide-react';
import { getAsset, saveAsset, saveAssets, saveAssetToLibrary } from '../services/projectStorage';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import type { CanvasNode, CanvasSummary, MediaAsset, MediaKind, PersistedAsset, ProjectSummary, SaveStatus } from '../types';
import { disposeRuntimeAsset, getMediaNodeSize, getVideoNodeSize, processWithConcurrency } from '../utils/mediaPipeline';
import { createId } from '../utils/id';
import { mediaQualityForZoom } from '../utils/canvasPerformance';
import { refreshMediaBudget, setMediaBudgetQuality } from '../utils/mediaBudget';
import { CanvasEdge } from './CanvasEdge';
import { CanvasContextMenu, type CanvasContextTarget } from './CanvasContextMenu';
import { ConnectionNodeMenu } from './ConnectionNodeMenu';
import { AssetDrawer, ASSET_DRAG_MIME } from './AssetDrawer';
import { MediaNode } from './MediaNode';
import { VideoNode } from './VideoNode';
import { NodeLibrary } from './NodeLibrary';
import { GenerationRecordsPanel } from './GenerationRecordsPanel';
import { TemplatePanel } from './TemplatePanel';
import { TaskQueuePanel } from './TaskQueuePanel';
import { CanvasUiProvider } from '../canvas/CanvasUiContext';
import { CanvasNodeDisplayProvider, shouldCompactNodeKind, useCanvasNodeDisplay, type NodeDisplayMode } from '../canvas/CanvasNodeDisplayContext';
import { createPersistedCanvasAsset, persistExistingCanvasAsset } from '../services/canvasMedia';
import { canvasCommandService } from '../canvas/canvasCommandService';

const COMPACT_NODE_WIDTH = 156;
const COMPACT_NODE_HEIGHT = 44;

function CanvasMediaNode(props: NodeProps<CanvasNode>) {
  const { mode, expandedNodeIds, minimizeNode } = useCanvasNodeDisplay();
  const compactable = shouldCompactNodeKind(props.data.kind);
  const expanded = expandedNodeIds.has(props.id);
  return <MediaNode
    {...props}
    displayCollapsed={mode === 'compact' && compactable && !expanded}
    showMinimize={mode === 'compact' && compactable && expanded}
    onMinimize={minimizeNode}
  />;
}

const nodeTypes = {
  media: CanvasMediaNode,
  video: VideoNode,
  image: CanvasMediaNode,
  text: CanvasMediaNode,
  script: CanvasMediaNode,
  note: CanvasMediaNode,
  storyboardPlanner: CanvasMediaNode,
  shot: CanvasMediaNode,
  textToImage: CanvasMediaNode,
  imageToImage: CanvasMediaNode,
  batchImageRefine: CanvasMediaNode,
  textToVideo: CanvasMediaNode,
  imageToVideo: CanvasMediaNode,
  firstLastFrameToVideo: CanvasMediaNode,
  referenceToVideo: CanvasMediaNode,
  videoMerge: CanvasMediaNode,
};
const edgeTypes = { canvas: CanvasEdge };

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

function CanvasSelectionToolbar({ nodes, onCopy, onCut, onDelete }: {
  nodes: CanvasNode[];
  onCopy: () => void;
  onCut: () => void;
  onDelete: () => void;
}) {
  const transform = useStore((state) => state.transform);
  if (nodes.length < 2) return null;

  const bounds = nodes.reduce((result, node) => {
    const width = node.width ?? node.measured?.width ?? 0;
    const height = node.height ?? node.measured?.height ?? 0;
    return {
      minX: Math.min(result.minX, node.position.x),
      minY: Math.min(result.minY, node.position.y),
      maxX: Math.max(result.maxX, node.position.x + width),
      maxY: Math.max(result.maxY, node.position.y + height),
    };
  }, { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity });
  const [viewportX, viewportY, zoom] = transform;
  const style = {
    left: bounds.minX * zoom + viewportX,
    top: bounds.minY * zoom + viewportY,
    width: Math.max(1, (bounds.maxX - bounds.minX) * zoom),
    height: Math.max(1, (bounds.maxY - bounds.minY) * zoom),
  };

  return <div className="canvas-selection-toolbar" style={style}>
    <div className="canvas-selection-actions nodrag nopan" role="toolbar" aria-label="多选节点操作" onPointerDown={(event) => event.stopPropagation()}>
      <button onClick={onCopy} aria-label="复制" title="复制 Ctrl+C"><Copy size={14} /></button>
      <button onClick={onCut} aria-label="剪切" title="剪切 Ctrl+X"><Scissors size={14} /></button>
      <button className="is-danger" onClick={onDelete} aria-label="删除" title="删除 Ctrl+D"><Trash2 size={14} /></button>
    </div>
  </div>;
}

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
  onRenameCanvas: (canvasId: string, title: string) => void;
  onDeleteCanvas: (canvasId: string) => void;
  onDeleteProject: () => void;
  onOpenAgent: () => void;
  onOpenInspector: () => void;
  onExportProject: () => void;
  isExporting: boolean;
  onNotify?: (message: string) => void;
  canvasModeLocked?: boolean;
  canvasInteractionLocked?: boolean;
}

const saveLabel: Record<SaveStatus, string> = { idle: '本地项目', saving: '保存中…', saved: '已保存到本地', error: '保存失败' };

function CanvasInner(props: CanvasWorkspaceProps) {
  const { projectId, projectTitle, canvasId, canvasTitle, canvases, projects, saveStatus, initialViewport, onProjectTitleChange, onViewportChange, onOpenHome, onCreateProject, onSwitchProject, onSwitchCanvas, onNewCanvas, onRenameCanvas, onDeleteCanvas, onDeleteProject, onOpenAgent, onOpenInspector, onExportProject, isExporting, onNotify, canvasModeLocked = false, canvasInteractionLocked = false } = props;
  const [panel, setPanel] = useState<'nodes' | 'templates' | 'history' | null>(null);
  const [showGrid, setShowGrid] = useState(true);
  const [showMinimap, setShowMinimap] = useState(false);
  const [nodeDisplayMode, setNodeDisplayMode] = useState<NodeDisplayMode>('compact');
  const [expandedNodeIds, setExpandedNodeIds] = useState<Set<string>>(() => new Set());
  const [workspaceMenuOpen, setWorkspaceMenuOpen] = useState(false);
  const [projectSwitcherOpen, setProjectSwitcherOpen] = useState(false);
  const [canvasSwitcherOpen, setCanvasSwitcherOpen] = useState(false);
  const [assetDrawerOpen, setAssetDrawerOpen] = useState(false);
  const [projectSearch, setProjectSearch] = useState('');
  const [renameDialogOpen, setRenameDialogOpen] = useState(false);
  const [renameDraft, setRenameDraft] = useState(projectTitle);
  const [canvasRenameTarget, setCanvasRenameTarget] = useState<CanvasSummary | null>(null);
  const [canvasRenameDraft, setCanvasRenameDraft] = useState('');
  const [canvasDeleteTarget, setCanvasDeleteTarget] = useState<CanvasSummary | null>(null);
  const [canvasActionMenuId, setCanvasActionMenuId] = useState<string | null>(null);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [isImporting, setIsImporting] = useState(false);
  const [isFileDragActive, setIsFileDragActive] = useState(false);
  const [isViewportMoving, setIsViewportMoving] = useState(false);
  const [isLayingOut, setIsLayingOut] = useState(false);
  const [contextTarget, setContextTarget] = useState<CanvasContextTarget | null>(null);
  const dragDepthRef = useRef(0);
  const viewportFrameRef = useRef<number | null>(null);
  const pendingViewportRef = useRef<Viewport | null>(null);
  const mediaQualityRef = useRef(useMediaStore.getState().quality);
  const mobileFitDoneRef = useRef(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const nodes = useCanvasStore((state) => state.nodes);
  const flowNodes = useMemo(
    () => nodes.map((node) => {
      const displayNode = node.dragHandle === '.node-drag-handle' ? node : { ...node, dragHandle: '.node-drag-handle' };
      if (!shouldCompactNodeKind(node.data.kind) || nodeDisplayMode === 'expanded' || expandedNodeIds.has(node.id)) return displayNode;
      return { ...displayNode, width: COMPACT_NODE_WIDTH, height: COMPACT_NODE_HEIGHT };
    }),
    [expandedNodeIds, nodeDisplayMode, nodes],
  );
  const selectedNodes = useMemo(() => nodes.filter((node) => node.selected), [nodes]);
  const edges = useCanvasStore((state) => state.edges);
  const onNodesChange = useCanvasStore((state) => state.onNodesChange);
  const onEdgesChange = useCanvasStore((state) => state.onEdgesChange);
  const beginNodeDrag = useCanvasStore((state) => state.beginNodeDrag);
  const endNodeDrag = useCanvasStore((state) => state.endNodeDrag);
  const connect = useCanvasStore((state) => state.connect);
  const selectNode = useCanvasStore((state) => state.selectNode);
  const selectAll = useCanvasStore((state) => state.selectAll);
  const addNode = useCanvasStore((state) => state.addNode);
  const addImportedImages = useCanvasStore((state) => state.addImportedImages);
  const addImportedVideos = useCanvasStore((state) => state.addImportedVideos);
  const undo = useCanvasStore((state) => state.undo);
  const redo = useCanvasStore((state) => state.redo);
  const copySelected = useCanvasStore((state) => state.copySelected);
  const pasteClipboard = useCanvasStore((state) => state.pasteClipboard);
  const deleteSelected = useCanvasStore((state) => state.deleteSelected);
  const duplicateSelected = useCanvasStore((state) => state.duplicateSelected);
  const canPaste = useCanvasStore((state) => Boolean(state.clipboard?.nodes.length));
  const canUndo = useCanvasStore((state) => state.past.length > 0);
  const canRedo = useCanvasStore((state) => state.future.length > 0);
  const { fitView, screenToFlowPosition } = useReactFlow();
  // 「连接即创建」：跟踪连接生命周期，拖线松手未落到节点时（didConnect 为 false）
  // dispatch 'connection-end'，由 ConnectionNodeMenu 弹出「选择节点类型」菜单。
  const connectRef = useRef<{
    nodeId: string;
    nodeType: string | undefined;
    handleType: 'source' | 'target' | null;
    handleId: string;
    didConnect: boolean;
  } | null>(null);
  const handleConnectStart = useCallback((_event: any, params: any) => {
    if (!params?.nodeId) return;
    const node = useCanvasStore.getState().nodes.find((n) => n.id === params.nodeId);
    connectRef.current = {
      nodeId: params.nodeId,
      nodeType: node?.data.kind,
      handleType: params.handleType ?? null,
      handleId: params.handleId ?? '',
      didConnect: false,
    };
  }, []);
  const handleConnect = useCallback((connection: Connection) => {
    if (connectRef.current) connectRef.current.didConnect = true;
    connect(connection);
  }, [connect]);
  const handleConnectEnd = useCallback((event: MouseEvent | TouchEvent) => {
    const ref = connectRef.current;
    if (!ref || ref.didConnect) { connectRef.current = null; return; }
    const clientX = 'clientX' in event ? event.clientX : 0;
    const clientY = 'clientY' in event ? event.clientY : 0;
    const flow = screenToFlowPosition({ x: clientX, y: clientY });
    window.dispatchEvent(new CustomEvent('connection-end', {
      detail: {
        clientX,
        clientY,
        flowX: flow.x,
        flowY: flow.y,
        sourceNodeId: ref.nodeId,
        sourceNodeType: ref.nodeType,
        handleType: ref.handleType ?? 'source',
        handleId: ref.handleId,
      },
    }));
    connectRef.current = null;
  }, [screenToFlowPosition]);
  const flowStore = useStoreApi();
  const upsertAssets = useMediaStore((state) => state.upsertAssets);
  const setMediaQuality = useMediaStore((state) => state.setQuality);

  const notify = useCallback((message: string) => {
    setNotice(message); onNotify?.(message);
    window.setTimeout(() => setNotice(null), 1800);
  }, [onNotify]);
  const minimizeNode = useCallback((nodeId: string) => {
    setExpandedNodeIds((current) => {
      if (!current.has(nodeId)) return current;
      const next = new Set(current);
      next.delete(nodeId);
      return next;
    });
  }, []);
  const toggleNodeDisplayMode = useCallback(() => {
    setNodeDisplayMode((mode) => mode === 'compact' ? 'expanded' : 'compact');
    setExpandedNodeIds(new Set());
  }, []);
  const copySelection = useCallback(() => {
    const count = copySelected();
    if (count) notify(`已复制 ${count} 个节点`);
    return count;
  }, [copySelected, notify]);
  const cutSelection = useCallback(() => {
    const count = copySelected();
    if (!count) return 0;
    deleteSelected();
    notify(`已剪切 ${count} 个节点`);
    return count;
  }, [copySelected, deleteSelected, notify]);
  const runLayout = useCallback(async (nodeIds?: string[], connectedComponent = false) => {
    if (isLayingOut) return;
    setIsLayingOut(true);
    try {
      const context = { projectId, source: 'user' as const };
      const result = connectedComponent && nodeIds?.length
        ? await canvasCommandService.layoutConnectedComponent(context, { nodeIds })
        : await canvasCommandService.layoutDag(context, nodeIds?.length ? { nodeIds, direction: 'right' } : { direction: 'right' });
      if (!result.ok) {
        notify(result.error?.message ?? '整理编排失败');
        return;
      }
      const count = result.changes?.movedNodeIds?.length ?? 0;
      notify(count ? `已整理 ${count} 个节点` : '当前编排已是最优状态');
    } catch (error) {
      notify(error instanceof Error ? `整理编排失败：${error.message}` : '整理编排失败');
    } finally {
      setIsLayingOut(false);
    }
  }, [isLayingOut, notify, projectId]);
  const runCurrentLayout = useCallback(() => {
    if (selectedNodes.length > 1) return runLayout(selectedNodes.map((node) => node.id));
    if (selectedNodes.length === 1) return runLayout([selectedNodes[0].id], true);
    return runLayout();
  }, [runLayout, selectedNodes]);
  const visibleProjects = useMemo(() => projects.map((project) => project.id === projectId ? { ...project, title: projectTitle } : project), [projectId, projectTitle, projects]);
  const filteredProjects = useMemo(() => {
    const query = projectSearch.trim().toLocaleLowerCase();
    return query ? visibleProjects.filter((project) => project.title.toLocaleLowerCase().includes(query)) : visibleProjects;
  }, [projectSearch, visibleProjects]);
  const closeProjectMenus = useCallback(() => {
    setWorkspaceMenuOpen(false);
    setProjectSwitcherOpen(false);
    setCanvasSwitcherOpen(false);
    setCanvasActionMenuId(null);
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
  useEffect(() => {
    setNodeDisplayMode('compact');
    setExpandedNodeIds(new Set());
  }, [canvasId]);

  const importMedia = useCallback(async (fileList: FileList | File[], anchor?: { x: number; y: number }) => {
    const files = Array.from(fileList);
    if (!files.length) return;
    const state = flowStore.getState(); const center = anchor ?? screenToFlowPosition({ x: state.width / 2, y: state.height / 2 });
    const columns = Math.min(3, Math.max(1, Math.ceil(Math.sqrt(files.length))));
    const completed: Array<{ asset: PersistedAsset; index: number }> = [];
    const errors: string[] = [];
    setIsImporting(true); notify(`正在处理 ${files.length} 张图片`);
    await processWithConcurrency(files, 2, async (file, index) => {
      let asset: PersistedAsset | null = null;
      try { asset = await createPersistedCanvasAsset(file, ''); completed.push({ asset, index }); }
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
    try {
      const asset = await getAsset(assetId);
      if (!asset) return;
      const persisted = await persistExistingCanvasAsset(asset, '');
      const item = {
        assetId: persisted.id,
        label: persisted.name.replace(/\.[^.]+$/, ''),
        size: persisted.kind === 'video' ? getVideoNodeSize(persisted.width, persisted.height) : getMediaNodeSize(persisted.width, persisted.height),
        position,
        kind: persisted.kind,
      };
      upsertAssets([persisted]);
      if (persisted.kind === 'image') addImportedImages([item]);
      else addImportedVideos([item]);
    } catch (error) {
      notify(error instanceof Error ? error.message : '素材写入 MinIO 失败');
    }
  }, [addImportedImages, addImportedVideos, notify, upsertAssets]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.target as HTMLElement).matches('input, textarea, select, [contenteditable="true"]')) return;
      if (event.key === 'Escape' && contextTarget) {
        event.preventDefault();
        event.stopImmediatePropagation();
        closeContextMenu();
        return;
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'a') {
        event.preventDefault();
        const count = selectAll();
        if (count) notify(`已全选 ${count} 个节点`);
        return;
      }
      if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); deleteSelected(); }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'd') { event.preventDefault(); deleteSelected(); }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'x') {
        event.preventDefault();
        cutSelection();
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'c') {
        event.preventDefault();
        copySelection();
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'v') {
        event.preventDefault();
        const created = pasteClipboard();
        if (created.length) notify(`已粘贴 ${created.length} 个节点`);
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') { event.preventDefault(); event.shiftKey ? redo() : undo(); }
    };
    addEventListener('keydown', onKeyDown, true); return () => removeEventListener('keydown', onKeyDown, true);
  }, [closeContextMenu, contextTarget, copySelection, cutSelection, deleteSelected, notify, pasteClipboard, redo, selectAll, undo]);

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

  const defaultEdgeOptions = useMemo(() => ({ type: 'canvas' }), []);

  return (
    <main className={`canvas-workspace ${isImporting ? 'is-importing-media' : ''} ${isFileDragActive ? 'is-file-drag-active' : ''} ${isViewportMoving ? 'is-viewport-moving' : ''} ${selectedNodes.length > 1 ? 'has-multi-selection' : ''}`}
      onDragEnter={(event) => { if (event.dataTransfer.types.includes('Files')) { dragDepthRef.current += 1; setIsFileDragActive(true); } }}
      onDragOver={(event) => { if (event.dataTransfer.types.includes('Files')) event.preventDefault(); }}
      onDragLeave={() => { dragDepthRef.current = Math.max(0, dragDepthRef.current - 1); if (!dragDepthRef.current) setIsFileDragActive(false); }}
      onDrop={(event) => { event.preventDefault(); dragDepthRef.current = 0; setIsFileDragActive(false); const assetId = event.dataTransfer.getData(ASSET_DRAG_MIME); if (assetId) { void addAssetAt(assetId, screenToFlowPosition({ x: event.clientX, y: event.clientY })); return; } if (event.dataTransfer.files.length) void importMedia(event.dataTransfer.files, screenToFlowPosition({ x: event.clientX, y: event.clientY })); }}>
      <input ref={fileInputRef} className="canvas-file-input" type="file" accept="image/png,image/jpeg,image/webp,video/mp4,video/webm,video/quicktime" multiple onChange={(event) => { if (event.target.files) void importMedia(event.target.files); event.target.value = ''; }} />
      <CanvasNodeDisplayProvider mode={nodeDisplayMode} expandedNodeIds={expandedNodeIds} minimizeNode={minimizeNode}>
      <CanvasUiProvider openInspector={onOpenInspector}>
      <ReactFlow nodes={flowNodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes} onNodesChange={onNodesChange} onEdgesChange={onEdgesChange} onConnect={handleConnect} onConnectStart={handleConnectStart} onConnectEnd={handleConnectEnd}
        onNodeDragStart={beginNodeDrag} onNodeDragStop={endNodeDrag}
        onNodeClick={(event, node) => {
          closeContextMenu();
          selectNode(node.id, event.shiftKey || event.ctrlKey || event.metaKey);
          if (nodeDisplayMode === 'compact' && shouldCompactNodeKind(node.data.kind) && !expandedNodeIds.has(node.id)) {
            setExpandedNodeIds((current) => {
              if (current.has(node.id)) return current;
              const next = new Set(current);
              next.add(node.id);
              return next;
            });
          }
        }} onPaneClick={(event) => { closeContextMenu(); selectNode(null); if (event.detail === 2) { const point = screenToFlowPosition({ x: event.clientX, y: event.clientY }); addNode('text', { x: point.x - 190, y: point.y - 140 }); } }}
        onNodeDoubleClick={(_event, node) => { selectNode(node.id); onOpenInspector(); }}
        onPaneContextMenu={(event) => { event.preventDefault(); setContextTarget({ type: 'pane', clientX: event.clientX, clientY: event.clientY, flowPosition: screenToFlowPosition({ x: event.clientX, y: event.clientY }) }); }}
        onNodeContextMenu={(event, node) => {
          event.preventDefault();
          const flowPosition = screenToFlowPosition({ x: event.clientX, y: event.clientY });
          if (node.selected && selectedNodes.length > 1) {
            setContextTarget({ type: 'selection', count: selectedNodes.length, clientX: event.clientX, clientY: event.clientY, flowPosition });
            return;
          }
          if (!node.selected) selectNode(node.id);
          setContextTarget({ type: 'node', node, clientX: event.clientX, clientY: event.clientY, flowPosition });
        }}
        onSelectionContextMenu={(event, selection) => {
          event.preventDefault();
          setContextTarget({ type: 'selection', count: selection.length, clientX: event.clientX, clientY: event.clientY, flowPosition: screenToFlowPosition({ x: event.clientX, y: event.clientY }) });
        }}
        onEdgeContextMenu={(event, edge) => { event.preventDefault(); selectNode(null); onEdgesChange([{ id: edge.id, type: 'select', selected: true }]); }}
        defaultEdgeOptions={defaultEdgeOptions} connectionLineType={ConnectionLineType.Bezier} minZoom={.18} maxZoom={2.4} defaultViewport={initialViewport}
        deleteKeyCode={null} colorMode="dark" proOptions={{ hideAttribution: true }}
        panOnDrag={[1]} selectionOnDrag selectionMode={SelectionMode.Partial}
        onMoveStart={() => { closeContextMenu(); setIsViewportMoving(true); }}
        onMove={(_, viewport) => scheduleMediaViewport(viewport)}
        onMoveEnd={(_, viewport) => { setIsViewportMoving(false); scheduleMediaViewport(viewport); onViewportChange(viewport); }}
        onlyRenderVisibleElements={nodes.length > 320}>
        {showGrid && <Background variant={BackgroundVariant.Dots} gap={28} size={1} color="rgba(255,255,255,.10)" />}
        <CanvasSelectionToolbar nodes={selectedNodes} onCopy={copySelection} onCut={cutSelection} onDelete={deleteSelected} />
        {showMinimap && <MiniMap
          className="canvas-minimap"
          position="bottom-right"
          pannable
          zoomable
          nodeBorderRadius={5}
          nodeStrokeWidth={1.25}
          nodeColor={(node) => node.selected ? '#d7ff54' : 'rgba(215,255,84,.34)'}
          nodeStrokeColor={(node) => node.selected ? '#f3ffc5' : 'rgba(226,233,216,.42)'}
          bgColor="rgba(20,37,31,.96)"
          maskColor="rgba(4,12,9,.7)"
          maskStrokeColor="rgba(215,255,84,.9)"
          maskStrokeWidth={1.25}
          ariaLabel="画布导航小地图"
        />}
        <Panel className={`canvas-project-bar ${workspaceMenuOpen || projectSwitcherOpen ? 'is-menu-open' : ''}`} position="top-left">
          {!canvasModeLocked && <DropdownMenu.Root open={workspaceMenuOpen} onOpenChange={(open) => { setWorkspaceMenuOpen(open); if (open) { setProjectSwitcherOpen(false); setCanvasSwitcherOpen(false); } }}>
            <DropdownMenu.Trigger asChild><button className="workspace-menu-trigger" aria-label="本地工作区菜单"><ChevronDown size={16} /></button></DropdownMenu.Trigger>
            <DropdownMenu.Portal>
              <DropdownMenu.Content className="workspace-menu" aria-label="本地工作区菜单" align="start" sideOffset={8}>
                <DropdownMenu.Item asChild><button onClick={() => { closeProjectMenus(); onOpenHome(); }}><FolderOpen size={15} /><span>返回项目中心</span></button></DropdownMenu.Item>
                <DropdownMenu.Separator className="project-menu-divider" />
                <DropdownMenu.Label className="project-menu-section-label">项目</DropdownMenu.Label>
                <DropdownMenu.Item asChild><button onClick={() => { closeProjectMenus(); setRenameDraft(projectTitle); setRenameDialogOpen(true); }}><span>重命名</span></button></DropdownMenu.Item>
                <DropdownMenu.Item asChild><button onClick={() => { closeProjectMenus(); onCreateProject(); }}><Plus size={15} /><span>新建项目</span></button></DropdownMenu.Item>
                <DropdownMenu.Item disabled={isExporting} asChild><button disabled={isExporting} onClick={() => { closeProjectMenus(); onExportProject(); }}><FileArchive size={15} /><span>{isExporting ? '导出中…' : '导出项目包'}</span><kbd>.morrow</kbd></button></DropdownMenu.Item>
                <DropdownMenu.Separator className="project-menu-divider" />
                <DropdownMenu.Item asChild><button className="project-menu-danger" onClick={() => { closeProjectMenus(); setDeleteDialogOpen(true); }}><Trash2 size={15} /><span>删除项目</span></button></DropdownMenu.Item>
              </DropdownMenu.Content>
            </DropdownMenu.Portal>
          </DropdownMenu.Root>}
          {!canvasInteractionLocked && <i className="project-bar-divider" />}
          {!canvasInteractionLocked && <button className="project-switcher-trigger canvas-switcher-trigger" aria-label="切换画布" aria-expanded={canvasSwitcherOpen} onClick={() => { setCanvasSwitcherOpen((open) => !open); setProjectSwitcherOpen(false); setWorkspaceMenuOpen(false); }}><Layers size={13} /><span>{canvasTitle || '画布'}</span><ChevronDown size={13} /></button>}
          {!canvasInteractionLocked && canvasSwitcherOpen && <div className="project-switcher canvas-switcher" role="dialog" aria-label="切换画布">
            <div className="project-switcher-list" role="listbox">
              {canvases.map((item) => {
                const isCurrent = item.id === canvasId;
                return <div
                  className={`canvas-switcher-row ${isCurrent ? 'is-current' : ''}`}
                  key={item.id}
                  onContextMenu={(event) => {
                    event.preventDefault();
                    setCanvasActionMenuId(item.id);
                  }}
                >
                  <button
                    role="option"
                    aria-selected={isCurrent}
                    className="canvas-switcher-select"
                    title={item.title}
                    onClick={() => { closeProjectMenus(); if (!isCurrent) onSwitchCanvas(item.id); }}
                  >
                    <span className="project-switcher-copy"><strong>{item.title}</strong><small>{item.nodeCount} 个节点 · {item.assetCount} 项素材</small></span>
                    {isCurrent && <Check size={15} />}
                  </button>
                  <DropdownMenu.Root
                    open={canvasActionMenuId === item.id}
                    onOpenChange={(open) => setCanvasActionMenuId(open ? item.id : null)}
                  >
                    <DropdownMenu.Trigger asChild>
                      <button
                        className="canvas-switcher-action-trigger"
                        aria-label={`管理画布 ${item.title}`}
                        title="重命名或删除画布"
                        onClick={(event) => event.stopPropagation()}
                      ><MoreHorizontal size={16} /></button>
                    </DropdownMenu.Trigger>
                    <DropdownMenu.Portal>
                      <DropdownMenu.Content className="canvas-item-menu" align="end" sideOffset={6} aria-label={`画布管理 ${item.title}`}>
                        <DropdownMenu.Item asChild>
                          <button onClick={() => { setCanvasActionMenuId(null); closeProjectMenus(); setCanvasRenameTarget(item); setCanvasRenameDraft(item.title); }}><Pencil size={14} /><span>重命名</span></button>
                        </DropdownMenu.Item>
                        <DropdownMenu.Separator className="project-menu-divider" />
                        <DropdownMenu.Item asChild>
                          <button className="canvas-menu-danger" onClick={() => { setCanvasActionMenuId(null); closeProjectMenus(); setCanvasDeleteTarget(item); }}><Trash2 size={14} /><span>删除画布</span></button>
                        </DropdownMenu.Item>
                      </DropdownMenu.Content>
                    </DropdownMenu.Portal>
                  </DropdownMenu.Root>
                </div>;
              })}
            </div>
            <button className="project-switcher-create" onClick={() => { closeProjectMenus(); onNewCanvas(); }}><Plus size={15} /> 新建画布</button>
          </div>}
          {!canvasModeLocked && <i className="project-bar-divider" />}
          {!canvasModeLocked && <button className="project-switcher-trigger" aria-label="搜索和切换项目" aria-expanded={projectSwitcherOpen} onClick={() => { setProjectSwitcherOpen((open) => !open); setWorkspaceMenuOpen(false); setCanvasSwitcherOpen(false); setProjectSearch(''); }}><span>{projectTitle || 'Untitled'}</span><ChevronDown size={14} /></button>}
          <span className={`project-save-indicator is-${saveStatus}`} role="status" aria-label={saveLabel[saveStatus]} title={saveLabel[saveStatus]}><Check size={14} /></span>
          {!canvasModeLocked && <button className="project-inline-export" disabled={isExporting} onClick={onExportProject} aria-label="导出项目" title={isExporting ? '导出中…' : '导出项目'}><FileArchive size={15} /></button>}
          <div className="project-copy project-compat-layer"><input aria-label="画布名称" value={projectTitle} disabled={canvasModeLocked} onChange={(event) => onProjectTitleChange(event.target.value)} /><small className={saveStatus === 'error' ? 'is-error' : ''}>{saveLabel[saveStatus]}</small></div>
          {!canvasModeLocked && projectSwitcherOpen && <div className="project-switcher" role="dialog" aria-label="搜索和切换项目">
            <label className="project-switcher-search"><Search size={15} /><input autoFocus aria-label="搜索项目" placeholder="搜索项目" value={projectSearch} onChange={(event) => setProjectSearch(event.target.value)} onKeyDown={(event) => { if (event.key === 'Escape') closeProjectMenus(); }} /></label>
            <div className="project-switcher-list" role="tree" aria-label="本地项目">
              {filteredProjects.length ? filteredProjects.map((project) => <button role="treeitem" aria-selected={project.id === projectId} className={project.id === projectId ? 'is-current' : ''} key={project.id} onClick={() => { closeProjectMenus(); if (project.id !== projectId) onSwitchProject(project.id); }}><span className="project-switcher-thumb">{project.assetCount}</span><span className="project-switcher-copy"><strong>{project.title || 'Untitled'}</strong><small>{project.nodeCount} 个节点 · {new Date(project.updatedAt).toLocaleDateString('zh-CN')}</small></span>{project.id === projectId && <Check size={15} />}</button>) : <p>没有匹配的本地项目</p>}
            </div>
            <button className="project-switcher-create" onClick={() => { closeProjectMenus(); onCreateProject(); }}><Plus size={15} /> 新建项目</button>
          </div>}
        </Panel>
        <Panel className="canvas-tool-island" position="top-left"><button className={panel === 'nodes' ? 'primary-tool is-active' : 'primary-tool'} onClick={() => setPanel(panel === 'nodes' ? null : 'nodes')} aria-label="添加节点"><Plus size={26} /></button><div className="canvas-tool-group"><button className={assetDrawerOpen ? 'is-active' : ''} onClick={() => setAssetDrawerOpen((open) => !open)} aria-label="资产库"><Layers size={20} /></button><button onClick={() => setPanel(panel === 'templates' ? null : 'templates')} aria-label="结构模板"><Download size={20} /></button><button className={panel === 'history' ? 'is-active' : ''} onClick={() => setPanel(panel === 'history' ? null : 'history')} aria-label="生成记录"><History size={20} /></button><button onClick={onOpenInspector} aria-label="节点属性"><LayoutList size={20} /></button>{!canvasModeLocked && <button onClick={onOpenAgent} aria-label="执行服务"><MessageCircle size={20} /></button>}</div></Panel>
        <Panel className="canvas-task-queue" position="top-right"><TaskQueuePanel onOpenRecords={() => setPanel('history')} /></Panel>
        <Controls
          className="canvas-controls canvas-view-bar"
          position="bottom-left"
          orientation="horizontal"
          showZoom
          showFitView
          showInteractive
          fitViewOptions={{ padding: .22, duration: 300 }}
          aria-label="画布控制"
        >
          <ControlButton className={showMinimap ? 'is-active' : ''} onClick={() => setShowMinimap((value) => !value)} aria-label="小地图" title="小地图"><Map size={16} /></ControlButton>
          <ControlButton className={showGrid ? 'is-active' : ''} onClick={() => setShowGrid((value) => !value)} aria-label="网格" title="网格"><Grid2X2 size={16} /></ControlButton>
          <ControlButton
            className={nodeDisplayMode === 'compact' ? 'is-active' : ''}
            onClick={toggleNodeDisplayMode}
            aria-label={nodeDisplayMode === 'compact' ? '展开全部节点' : '收起全部节点'}
            title={nodeDisplayMode === 'compact' ? '展开全部节点' : '收起全部节点'}
          >
            {nodeDisplayMode === 'compact' ? <Maximize2 size={16} /> : <Minimize2 size={16} />}
          </ControlButton>
          <ControlButton
            className={isLayingOut ? 'is-active' : ''}
            disabled={isLayingOut}
            onClick={() => void runCurrentLayout()}
            aria-label="整理编排"
            aria-busy={isLayingOut}
            title={selectedNodes.length > 1 ? '整理所选节点' : selectedNodes.length === 1 ? '整理当前连通编排' : '整理整张画布'}
          >
            {isLayingOut ? <LoaderCircle className="is-spinning" size={16} /> : <Network size={16} />}
          </ControlButton>
          <ControlButton onClick={() => notify('双击空白处创建文字节点；拖入图片可直接导入')} aria-label="帮助" title="帮助"><CircleHelp size={17} /></ControlButton>
        </Controls>
        {notice && <Panel className="canvas-status-toast" position="bottom-center">{notice}</Panel>}
        {!nodes.length && <Panel className="canvas-empty-hint" position="top-left"><div><strong>空画布</strong><span>从真实素材或文字开始</span></div><section><button onClick={() => fileInputRef.current?.click()}>导入图片 / 视频</button><button onClick={() => addNode('text')}>创建文字</button><button onClick={() => addNode('image')}>创建图片占位</button><button onClick={() => addNode('video')}>创建视频占位</button></section></Panel>}
      </ReactFlow>
      </CanvasUiProvider>
      </CanvasNodeDisplayProvider>
      {assetDrawerOpen && <AssetDrawer projectId={projectId} onClose={() => setAssetDrawerOpen(false)} />}
      {panel === 'nodes' && <NodeLibrary onClose={() => setPanel(null)} onImport={() => fileInputRef.current?.click()} />}
      {panel === 'templates' && <TemplatePanel onClose={() => setPanel(null)} />}
      {panel === 'history' && <GenerationRecordsPanel onClose={() => setPanel(null)} />}
      <Dialog.Root open={renameDialogOpen} onOpenChange={setRenameDialogOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="project-dialog-backdrop" />
          <Dialog.Content asChild>
            <form className="project-dialog" onSubmit={(event) => { event.preventDefault(); const title = renameDraft.trim(); if (!title) return; onProjectTitleChange(title); setRenameDialogOpen(false); }}>
              <Dialog.Title>重命名项目</Dialog.Title>
              <Dialog.Description className="project-dialog-description">请输入项目的新名称</Dialog.Description>
              <input autoFocus aria-label="项目的新名称" value={renameDraft} onChange={(event) => setRenameDraft(event.target.value)} />
              <footer><Dialog.Close asChild><button type="button">取消</button></Dialog.Close><button type="submit" disabled={!renameDraft.trim()}>确认</button></footer>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
      <Dialog.Root open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="project-dialog-backdrop" />
          <Dialog.Content className="project-dialog project-delete-dialog">
            <Dialog.Title>删除当前项目？</Dialog.Title>
            <Dialog.Description className="project-dialog-description">项目、画布结构及仅由该项目引用的素材将从本地删除。此操作无法撤销。</Dialog.Description>
            <footer><Dialog.Close asChild><button type="button">取消</button></Dialog.Close><button className="is-danger" type="button" onClick={() => { setDeleteDialogOpen(false); onDeleteProject(); }}>确认删除</button></footer>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
      <Dialog.Root open={Boolean(canvasRenameTarget)} onOpenChange={(open) => { if (!open) setCanvasRenameTarget(null); }}>
        <Dialog.Portal>
          <Dialog.Overlay className="project-dialog-backdrop" />
          <Dialog.Content asChild>
            <form className="project-dialog" onSubmit={(event) => {
              event.preventDefault();
              const title = canvasRenameDraft.trim();
              if (!title || !canvasRenameTarget) return;
              onRenameCanvas(canvasRenameTarget.id, title);
              setCanvasRenameTarget(null);
            }}>
              <Dialog.Title>重命名画布</Dialog.Title>
              <Dialog.Description className="project-dialog-description">请输入画布的新名称</Dialog.Description>
              <input autoFocus aria-label="画布的新名称" value={canvasRenameDraft} onChange={(event) => setCanvasRenameDraft(event.target.value)} />
              <footer><Dialog.Close asChild><button type="button">取消</button></Dialog.Close><button type="submit" disabled={!canvasRenameDraft.trim()}>确认</button></footer>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
      <Dialog.Root open={Boolean(canvasDeleteTarget)} onOpenChange={(open) => { if (!open) setCanvasDeleteTarget(null); }}>
        <Dialog.Portal>
          <Dialog.Overlay className="project-dialog-backdrop" />
          <Dialog.Content className="project-dialog project-delete-dialog">
            <Dialog.Title>删除画布「{canvasDeleteTarget?.title}」？</Dialog.Title>
            <Dialog.Description className="project-dialog-description">画布中的节点和连线将被删除。此操作无法撤销。</Dialog.Description>
            <footer>
              <Dialog.Close asChild><button type="button">取消</button></Dialog.Close>
              <button className="is-danger" type="button" onClick={() => {
                const target = canvasDeleteTarget;
                setCanvasDeleteTarget(null);
                if (target) onDeleteCanvas(target.id);
              }}>确认删除</button>
            </footer>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
      {contextTarget && <CanvasContextMenu
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
        onAddNode={(kind) => addNode(kind as MediaKind, contextTarget.flowPosition)}
        onUndo={undo}
        onRedo={redo}
        onCopy={copySelection}
        onCut={cutSelection}
        onLayout={() => {
          if (contextTarget.type === 'selection') void runLayout(selectedNodes.map((node) => node.id));
          else if (contextTarget.type === 'node') void runLayout([contextTarget.node.id], true);
          else void runLayout();
        }}
        onPaste={() => { const created = pasteClipboard(contextTarget.flowPosition); if (created.length) notify(`已粘贴 ${created.length} 个节点`); }}
        onDuplicate={() => { const created = duplicateSelected(); if (created.length) notify(`已创建 ${created.length} 个副本`); }}
        onDelete={deleteSelected}
        onSaveToLibrary={saveContextAsset}
        onFlattenHistory={flattenContextAsset}
        onDownload={downloadContextAsset}
        onCopyImage={copyContextImage}
      />}
      <ConnectionNodeMenu />
    </main>
  );
}

export function CanvasWorkspace(props: CanvasWorkspaceProps) { return <ReactFlowProvider><CanvasInner {...props} /></ReactFlowProvider>; }
