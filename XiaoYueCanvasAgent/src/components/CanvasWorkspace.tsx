import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import {
  Background, BackgroundVariant, ConnectionLineType, ControlButton, Controls, MiniMap, Panel, ReactFlow, ReactFlowProvider, SelectionMode,
  useReactFlow, useStore, useStoreApi, type Connection, type NodeProps, type Viewport,
} from '@xyflow/react';
import { Check, ChevronDown, Copy, Eye, EyeOff, FileArchive, FolderOpen, History, Layers, LoaderCircle, Map, Maximize2, Minimize2, MoreHorizontal, Network, Pencil, Plus, Scissors, Search, Trash2 } from 'lucide-react';
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
import { AssetDrawer, ASSET_DRAG_MIME, LIBRARY_ASSET_DRAG_MIME } from './AssetDrawer';
import { MediaNode } from './MediaNode';
import { VideoNode } from './VideoNode';
import { CanvasToolRail } from './CanvasToolRail';
import { GenerationRecordsPanel } from './GenerationRecordsPanel';
import { TaskQueuePanel } from './TaskQueuePanel';
import { CanvasUiProvider } from '../canvas/CanvasUiContext';
import { CanvasNodeDisplayProvider, shouldCompactNodeKind, useCanvasNodeDisplay, type NodeDisplayMode } from '../canvas/CanvasNodeDisplayContext';
import { TEXT_EDIT_NODE_KINDS, type CanvasNodeKind } from '../canvas/graphContracts';
import { filterEdgesForDisplay } from '../canvas/edgeVisibility';
import { cacheCanvasAssetBlob, createPersistedCanvasAsset, persistExistingCanvasAsset } from '../services/canvasMedia';
import { importCanvasLibraryAsset, importCanvasLibraryGroup } from '../services/canvasProjectLibrary';
import { canvasCommandService } from '../canvas/canvasCommandService';
import { usePresence } from '../hooks/usePresence';

const COMPACT_NODE_WIDTH = 156;
const COMPACT_NODE_HEIGHT = 44;
/** 复用同一个空集合，避免「没选中任何节点」时每次渲染都新建一个 Set。 */
const EMPTY_ID_SET: ReadonlySet<string> = new Set();

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
  imageGeneration: CanvasMediaNode,
  videoGeneration: CanvasMediaNode,
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
  onRenameProjectById: (projectId: string, title: string) => void;
  onDeleteProjectById: (projectId: string) => void;
  onOpenInspector: () => void;
  onExportProject: () => void;
  isExporting: boolean;
  onNotify?: (message: string) => void;
  canvasModeLocked?: boolean;
  canvasInteractionLocked?: boolean;
}

const saveLabel: Record<SaveStatus, string> = { idle: '本地项目', saving: '保存中…', saved: '已保存到本地', error: '保存失败' };

function CanvasInner(props: CanvasWorkspaceProps) {
  const { projectId, projectTitle, canvasId, canvasTitle, canvases, projects, saveStatus, initialViewport, onProjectTitleChange, onViewportChange, onOpenHome, onCreateProject, onSwitchProject, onSwitchCanvas, onNewCanvas, onRenameCanvas, onDeleteCanvas, onDeleteProject, onRenameProjectById, onDeleteProjectById, onOpenInspector, onExportProject, isExporting, onNotify, canvasModeLocked = false, canvasInteractionLocked = false } = props;
  const [showRecords, setShowRecords] = useState(false);
  const [showMinimap, setShowMinimap] = useState(false);
  // 「显示 / 隐藏连线」。和 showMinimap 一样是纯视图状态：不写进画布图，刷新回到默认的显示连线。
  const [showEdges, setShowEdges] = useState(true);
  const [nodeDisplayMode, setNodeDisplayMode] = useState<NodeDisplayMode>('compact');
  const [expandedNodeIds, setExpandedNodeIds] = useState<Set<string>>(() => new Set());
  const [workspaceMenuOpen, setWorkspaceMenuOpen] = useState(false);
  const [projectSwitcherOpen, setProjectSwitcherOpen] = useState(false);
  const [canvasSwitcherOpen, setCanvasSwitcherOpen] = useState(false);
  const [assetDrawerOpen, setAssetDrawerOpen] = useState(false);
  // 右键「上传资产」进来的多选模式：选中的项目库素材按类型落成图片 / 视频节点
  const [assetDrawerSelecting, setAssetDrawerSelecting] = useState(false);
  const [assetUploading, setAssetUploading] = useState(false);

  // 左侧浮层位置只剩生成记录和项目库抽屉两块，必须互斥，否则会重叠
  const closeAssetDrawer = useCallback(() => {
    setAssetDrawerOpen(false);
    setAssetDrawerSelecting(false);
  }, []);
  const toggleRecords = useCallback(() => {
    closeAssetDrawer();
    setShowRecords((current) => !current);
  }, [closeAssetDrawer]);
  const toggleAssetDrawer = useCallback(() => {
    setShowRecords(false);
    setAssetDrawerSelecting(false);
    setAssetDrawerOpen((open) => !open);
  }, []);
  const [projectSearch, setProjectSearch] = useState('');
  const [renameDialogOpen, setRenameDialogOpen] = useState(false);
  const [renameDraft, setRenameDraft] = useState(projectTitle);
  const [canvasRenameTarget, setCanvasRenameTarget] = useState<CanvasSummary | null>(null);
  const [canvasRenameDraft, setCanvasRenameDraft] = useState('');
  const [canvasDeleteTarget, setCanvasDeleteTarget] = useState<CanvasSummary | null>(null);
  // 项目切换列表：每行 ⋯ 菜单（重命名 / 删除），右键同样打开该行菜单
  const [projectActionMenuId, setProjectActionMenuId] = useState<string | null>(null);
  const [projectRenameTarget, setProjectRenameTarget] = useState<{ id: string; title: string } | null>(null);
  const [projectRenameDraft, setProjectRenameDraft] = useState('');
  const [projectDeleteTarget, setProjectDeleteTarget] = useState<{ id: string; title: string } | null>(null);
  const [canvasActionMenuId, setCanvasActionMenuId] = useState<string | null>(null);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [isImporting, setIsImporting] = useState(false);
  const [isFileDragActive, setIsFileDragActive] = useState(false);
  // 显示/隐藏动效：画布上的每个覆盖层都先退场再卸载，不再啪一下消失。
  const recordsPanel = usePresence(showRecords);
  const assetDrawer = usePresence(assetDrawerOpen);
  const statusToast = usePresence(Boolean(notice), { duration: 140 });
  const projectSwitcher = usePresence(projectSwitcherOpen, { duration: 140 });
  const canvasSwitcher = usePresence(canvasSwitcherOpen, { duration: 140 });
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
  const emptyHint = usePresence(nodes.length === 0);
  const flowNodes = useMemo(
    () => nodes.map((node) => {
      // 不再强制 dragHandle：节点正文默认就是拖动面（图片 / 视频 / 生成节点的预览框都能直接按下拖），
      // 只有需要保持自身交互的控件才用 .nodrag 退出拖动。
      // 例外：正文本身就是编辑器的节点（storyboard 的 .storyboard-editor、各生成节点的
      // 提示词/参数面板）整块挂在 .nodrag 下，这些节点仍然只能拖标题条。
      if (!shouldCompactNodeKind(node.data.kind) || nodeDisplayMode === 'expanded' || expandedNodeIds.has(node.id)) return node;
      return { ...node, width: COMPACT_NODE_WIDTH, height: COMPACT_NODE_HEIGHT };
    }),
    [expandedNodeIds, nodeDisplayMode, nodes],
  );
  const selectedNodes = useMemo(() => nodes.filter((node) => node.selected), [nodes]);
  const edges = useCanvasStore((state) => state.edges);
  // 选中集合的稳定指纹：拖动节点会不停换 nodes 引用，但没改选中集合时不该重算边数组。
  const selectedIdKey = useMemo(
    () => selectedNodes.map((node) => node.id).sort().join(','),
    [selectedNodes],
  );
  const flowEdges = useMemo(
    () => filterEdgesForDisplay(edges, {
      edgesHidden: !showEdges,
      revealedNodeIds: selectedIdKey ? new Set(selectedIdKey.split(',')) : EMPTY_ID_SET,
    }),
    [edges, selectedIdKey, showEdges],
  );
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
  const workspaceRef = useRef<HTMLElement>(null);
  const upsertAssets = useMediaStore((state) => state.upsertAssets);
  const setMediaQuality = useMediaStore((state) => state.setQuality);

  const notify = useCallback((message: string) => {
    if (onNotify) {
      onNotify(message);
      return;
    }
    setNotice(message);
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
    setProjectActionMenuId(null);
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

  // 节点外置标题要抵消画布缩放：把 1/zoom 作为 CSS 变量下发，节点就不必各自订阅 zoom。
  useEffect(() => {
    const apply = (transform: readonly [number, number, number]) => {
      workspaceRef.current?.style.setProperty('--inverse-zoom', String(1 / (transform[2] || 1)));
    };
    apply(flowStore.getState().transform);
    return flowStore.subscribe((state, previous) => {
      if (state.transform !== previous.transform) apply(state.transform);
    });
  }, [flowStore]);
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

  /** 左栏点节点：落到当前视口中心，跟随用户视线，不硬编码坐标。 */
  const addNodeAtViewportCenter = useCallback((kind: CanvasNodeKind) => {
    const state = flowStore.getState();
    addNode(kind as MediaKind, screenToFlowPosition({ x: state.width / 2, y: state.height / 2 }));
  }, [addNode, flowStore, screenToFlowPosition]);

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

  const addPersistedAssetAt = useCallback((persisted: PersistedAsset, position: { x: number; y: number }) => {
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
  }, [addImportedImages, addImportedVideos, upsertAssets]);

  const addAssetAt = useCallback(async (assetId: string, position: { x: number; y: number }) => {
    try {
      const asset = await getAsset(assetId);
      if (!asset) return false;
      const persisted = await persistExistingCanvasAsset(asset, '');
      addPersistedAssetAt(persisted, position);
      return true;
    } catch (error) {
      notify(error instanceof Error ? error.message : '素材写入 MinIO 失败');
      return false;
    }
  }, [addPersistedAssetAt, notify]);

  /** 资产库抽屉「本地素材库」里的插入：复用 addAssetAt，落到视口中心。抽屉不关，方便连插几张。 */
  const insertLocalLibraryAsset = useCallback(async (assetId: string) => {
    const state = flowStore.getState();
    const inserted = await addAssetAt(assetId, screenToFlowPosition({ x: state.width / 2, y: state.height / 2 }));
    if (inserted) {
      scheduleMediaViewport();
      notify('本地素材已插入画布');
    }
  }, [addAssetAt, flowStore, notify, scheduleMediaViewport, screenToFlowPosition]);

  const addLibraryAssetAt = useCallback(async (
    libraryAssetId: string,
    position: { x: number; y: number },
    options?: { silent?: boolean },
  ) => {
    try {
      const descriptor = await importCanvasLibraryAsset(canvasId, libraryAssetId);
      const persisted = await cacheCanvasAssetBlob(descriptor);
      addPersistedAssetAt(persisted, position);
      if (!options?.silent) notify('项目库素材已导入画布');
      return true;
    } catch (error) {
      notify(error instanceof Error ? error.message : '项目库素材导入失败');
      return false;
    }
  }, [addPersistedAssetAt, canvasId, notify]);

  /** 多选上传：按选中顺序铺成网格落节点，只报一条汇总。 */
  const uploadLibraryAssets = useCallback(async (libraryAssetIds: string[]) => {
    if (!libraryAssetIds.length) return;
    setAssetUploading(true);
    const state = flowStore.getState();
    const center = screenToFlowPosition({ x: state.width / 2, y: state.height / 2 });
    const columns = Math.min(3, Math.max(1, Math.ceil(Math.sqrt(libraryAssetIds.length))));
    let imported = 0;
    for (const [order, libraryAssetId] of libraryAssetIds.entries()) {
      const ok = await addLibraryAssetAt(libraryAssetId, {
        x: center.x + (order % columns) * 430 - ((columns - 1) * 430) / 2,
        y: center.y + Math.floor(order / columns) * 390,
      }, { silent: true });
      if (ok) imported += 1;
    }
    setAssetUploading(false);
    closeAssetDrawer();
    scheduleMediaViewport();
    notify(imported === libraryAssetIds.length
      ? `已上传 ${imported} 个资产到画布`
      : `已上传 ${imported} / ${libraryAssetIds.length} 个资产，其余失败`);
  }, [addLibraryAssetAt, closeAssetDrawer, flowStore, notify, scheduleMediaViewport, screenToFlowPosition]);

  const addLibraryGroupAt = useCallback(async (
    groupAssetId: string,
    position: { x: number; y: number },
  ) => {
    try {
      const descriptors = await importCanvasLibraryGroup(canvasId, groupAssetId);
      const loaded: Array<{ persisted: PersistedAsset; index: number }> = [];
      const errors: string[] = [];
      await processWithConcurrency(descriptors, 2, async (descriptor, index) => {
        try {
          loaded.push({ persisted: await cacheCanvasAssetBlob(descriptor), index });
        } catch (error) {
          errors.push(error instanceof Error ? error.message : '资产集文件读取失败');
        }
      });
      loaded.sort((left, right) => left.index - right.index);
      const columns = Math.min(3, Math.max(1, Math.ceil(Math.sqrt(loaded.length))));
      loaded.forEach(({ persisted }, order) => {
        addPersistedAssetAt(persisted, {
          x: position.x + (order % columns) * 430 - ((columns - 1) * 430) / 2,
          y: position.y + Math.floor(order / columns) * 390,
        });
      });
      notify(errors.length
        ? `资产集已导入 ${loaded.length} 个文件，${errors.length} 个失败：${errors[0]}`
        : `资产集已导入 ${loaded.length} 个文件`);
    } catch (error) {
      notify(error instanceof Error ? error.message : '资产集导入失败');
    }
  }, [addPersistedAssetAt, canvasId, notify]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      // [role="textbox"]：文字节点的静态预览面。它退出编辑态后仍会持有焦点，
      // 不认它的话 Delete/Backspace 会把整个节点删掉、Ctrl+A 会变成全选节点。
      if ((event.target as HTMLElement).matches('input, textarea, select, [contenteditable="true"], [role="textbox"]')) return;
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
    if (!workspaceMenuOpen && !projectSwitcherOpen && !canvasSwitcherOpen) return;
    const close = (event: PointerEvent) => {
      const target = event.target as Element;
      if (!target.closest('.canvas-project-bar, .workspace-menu, .project-switcher, .canvas-item-menu')) closeProjectMenus();
    };
    addEventListener('pointerdown', close, true);
    return () => removeEventListener('pointerdown', close, true);
  }, [canvasSwitcherOpen, closeProjectMenus, projectSwitcherOpen, workspaceMenuOpen]);

  useEffect(() => {
    if (!renameDialogOpen) setRenameDraft(projectTitle);
  }, [projectTitle, renameDialogOpen]);

  const defaultEdgeOptions = useMemo(() => ({ type: 'canvas' }), []);

  return (
    <main ref={workspaceRef} className={`canvas-workspace ${isImporting ? 'is-importing-media' : ''} ${isFileDragActive ? 'is-file-drag-active' : ''} ${isViewportMoving ? 'is-viewport-moving' : ''} ${selectedNodes.length > 1 ? 'has-multi-selection' : ''}`}
      onDragEnter={(event) => { if (event.dataTransfer.types.includes('Files')) { dragDepthRef.current += 1; setIsFileDragActive(true); } }}
      onDragOver={(event) => {
        const types = event.dataTransfer.types;
        if (types.includes('Files') || types.includes(LIBRARY_ASSET_DRAG_MIME) || types.includes(ASSET_DRAG_MIME)) {
          event.preventDefault();
        }
      }}
      onDragLeave={() => { dragDepthRef.current = Math.max(0, dragDepthRef.current - 1); if (!dragDepthRef.current) setIsFileDragActive(false); }}
      onDrop={(event) => {
        event.preventDefault();
        dragDepthRef.current = 0;
        setIsFileDragActive(false);
        const position = screenToFlowPosition({ x: event.clientX, y: event.clientY });
        const libraryPayload = event.dataTransfer.getData(LIBRARY_ASSET_DRAG_MIME);
        if (libraryPayload) {
          try {
            const parsed = JSON.parse(libraryPayload) as { libraryAssetId?: string; type?: string };
            if (parsed.libraryAssetId) {
              if (parsed.type === 'group') {
                void addLibraryGroupAt(parsed.libraryAssetId, position);
              } else {
                void addLibraryAssetAt(parsed.libraryAssetId, position);
              }
              return;
            }
          } catch {
            // Ignore malformed cross-window drag payload and fall through.
          }
        }
        const assetId = event.dataTransfer.getData(ASSET_DRAG_MIME);
        if (assetId) {
          void addAssetAt(assetId, position);
          return;
        }
        if (event.dataTransfer.files.length) void importMedia(event.dataTransfer.files, position);
      }}>
      <input ref={fileInputRef} className="canvas-file-input" type="file" accept="image/png,image/jpeg,image/webp,video/mp4,video/webm,video/quicktime" multiple onChange={(event) => { if (event.target.files) void importMedia(event.target.files); event.target.value = ''; }} />
      <CanvasNodeDisplayProvider mode={nodeDisplayMode} expandedNodeIds={expandedNodeIds} minimizeNode={minimizeNode}>
      <CanvasUiProvider openInspector={onOpenInspector}>
      <ReactFlow nodes={flowNodes} edges={flowEdges} nodeTypes={nodeTypes} edgeTypes={edgeTypes} onNodesChange={onNodesChange} onEdgesChange={onEdgesChange} onConnect={handleConnect} onConnectStart={handleConnectStart} onConnectEnd={handleConnectEnd}
        connectOnClick={false}
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
        onNodeDoubleClick={(_event, node) => {
          // 文字节点的双击属于「进入正文编辑」，由 TextNotePromptNode 自己处理；
          // 这里再兜一道，避免它同时把「节点详情」面板也拉起来。
          if (TEXT_EDIT_NODE_KINDS.has(String(node.data.kind))) return;
          selectNode(node.id); onOpenInspector();
        }}
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
        {/* 点阵随缩放一起放大/拉疏（React Flow 的 pattern 会乘上 zoom）*/}
        {/* 点阵常显：网格开关已下架，画布始终保留点阵底纹 */}
        <Background variant={BackgroundVariant.Dots} gap={28} size={2} color="rgba(244,244,245,.16)" />
        <CanvasSelectionToolbar nodes={selectedNodes} onCopy={copySelection} onCut={cutSelection} onDelete={deleteSelected} />
        {showMinimap && <MiniMap
          className="canvas-minimap"
          position="bottom-left"
          pannable
          zoomable
          nodeBorderRadius={5}
          nodeStrokeWidth={1.25}
          nodeColor={(node) => node.selected ? '#f4f4f5' : 'rgba(244,244,245,.34)'}
          nodeStrokeColor={(node) => node.selected ? '#ffffff' : 'rgba(244,244,245,.42)'}
          bgColor="rgba(18,18,18,.96)"
          maskColor="rgba(5,5,5,.7)"
          maskStrokeColor="rgba(244,244,245,.75)"
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
          {!canvasInteractionLocked && canvasSwitcher.mounted && <div className="project-switcher canvas-switcher motion-presence" data-present={canvasSwitcher.present} role="dialog" aria-label="切换画布">
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
                    modal={false}
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
          {!canvasModeLocked && projectSwitcher.mounted && <div className="project-switcher motion-presence" data-present={projectSwitcher.present} role="dialog" aria-label="搜索和切换项目">
            <label className="project-switcher-search"><Search size={15} /><input autoFocus aria-label="搜索项目" placeholder="搜索项目" value={projectSearch} onChange={(event) => setProjectSearch(event.target.value)} onKeyDown={(event) => { if (event.key === 'Escape') closeProjectMenus(); }} /></label>
            <div className="project-switcher-list" role="tree" aria-label="本地项目">
              {filteredProjects.length ? filteredProjects.map((project) => {
                const isCurrent = project.id === projectId;
                const title = project.title || 'Untitled';
                return <div
                  className={`project-switcher-row ${isCurrent ? 'is-current' : ''}`}
                  key={project.id}
                  onContextMenu={(event) => {
                    event.preventDefault();
                    setProjectActionMenuId(project.id);
                  }}
                >
                  <button
                    role="treeitem"
                    aria-selected={isCurrent}
                    className="project-switcher-select"
                    title={title}
                    onClick={() => { closeProjectMenus(); if (!isCurrent) onSwitchProject(project.id); }}
                  >
                    <span className="project-switcher-thumb">{project.assetCount}</span>
                    <span className="project-switcher-copy"><strong>{title}</strong><small>{project.nodeCount} 个节点 · {new Date(project.updatedAt).toLocaleDateString('zh-CN')}</small></span>
                    {isCurrent && <Check size={15} />}
                  </button>
                  <DropdownMenu.Root
                    modal={false}
                    open={projectActionMenuId === project.id}
                    onOpenChange={(open) => setProjectActionMenuId(open ? project.id : null)}
                  >
                    <DropdownMenu.Trigger asChild>
                      <button
                        className="canvas-switcher-action-trigger"
                        aria-label={`管理项目 ${title}`}
                        title="重命名或删除项目"
                        onClick={(event) => event.stopPropagation()}
                      ><MoreHorizontal size={16} /></button>
                    </DropdownMenu.Trigger>
                    <DropdownMenu.Portal>
                      <DropdownMenu.Content className="canvas-item-menu" align="end" sideOffset={6} aria-label={`项目管理 ${title}`}>
                        <DropdownMenu.Item asChild>
                          <button onClick={() => { setProjectActionMenuId(null); closeProjectMenus(); setProjectRenameTarget({ id: project.id, title }); setProjectRenameDraft(title); }}><Pencil size={14} /><span>重命名</span></button>
                        </DropdownMenu.Item>
                        <DropdownMenu.Separator className="project-menu-divider" />
                        <DropdownMenu.Item asChild>
                          <button className="canvas-menu-danger" onClick={() => { setProjectActionMenuId(null); closeProjectMenus(); setProjectDeleteTarget({ id: project.id, title }); }}><Trash2 size={14} /><span>删除项目</span></button>
                        </DropdownMenu.Item>
                      </DropdownMenu.Content>
                    </DropdownMenu.Portal>
                  </DropdownMenu.Root>
                </div>;
              }) : <p>没有匹配的本地项目</p>}
            </div>
            <button className="project-switcher-create" onClick={() => { closeProjectMenus(); onCreateProject(); }}><Plus size={15} /> 新建项目</button>
          </div>}
        </Panel>
        <CanvasToolRail
          onAddNode={addNodeAtViewportCenter}
          onUpload={() => fileInputRef.current?.click()}
          onToggleAssets={toggleAssetDrawer}
          assetsActive={assetDrawerOpen}
        />
        {/* 右上角并排：生成记录入口 + 任务队列。生成记录不再藏在工具岛或队列面板里 */}
        <Panel className="canvas-quick-actions" position="top-right">
          <button
            className={`canvas-quick-action generation-records-trigger${showRecords ? ' is-active' : ''}`}
            onClick={toggleRecords}
            aria-label="生成记录"
            aria-pressed={showRecords}
            title="生成记录"
          ><History size={18} /></button>
          <TaskQueuePanel />
        </Panel>
        <Controls
          className="canvas-controls canvas-view-bar"
          position="bottom-left"
          orientation="horizontal"
          showZoom
          showFitView
          // 画布交互锁按钮已下架：它只是把画布切成只读，实际没人用，留着还容易误触。
          showInteractive={false}
          fitViewOptions={{ padding: .22, duration: 300 }}
          aria-label="画布控制"
        >
          <ControlButton className={showMinimap ? 'is-active' : ''} onClick={() => setShowMinimap((value) => !value)} aria-label="小地图" title="小地图"><Map size={16} /></ControlButton>
          {/* 显示 / 隐藏连线。和同排的展开-收起一样，图标表示「点下去会发生什么」，
              is-active 标出「隐藏连线」这个非默认模式已经打开。 */}
          <ControlButton
            className={showEdges ? '' : 'is-active'}
            onClick={() => setShowEdges((value) => !value)}
            aria-label={showEdges ? '隐藏连线' : '显示连线'}
            aria-pressed={!showEdges}
            title={showEdges ? '隐藏连线' : '显示连线（点节点可单独看它的连线）'}
          >
            {showEdges ? <EyeOff size={16} /> : <Eye size={16} />}
          </ControlButton>
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
        </Controls>
        {statusToast.mounted && <Panel className="canvas-status-toast motion-fade" data-present={statusToast.present} position="bottom-center">{notice}</Panel>}
        {emptyHint.mounted && <Panel className="canvas-empty-hint motion-fade" data-present={emptyHint.present} position="top-left"><div><strong>空画布</strong><span>从真实素材或文字开始</span></div><section><button onClick={() => fileInputRef.current?.click()}>导入图片 / 视频</button><button onClick={() => addNode('text')}>创建文字</button><button onClick={() => addNode('image')}>创建图片占位</button><button onClick={() => addNode('video')}>创建视频占位</button></section></Panel>}
      </ReactFlow>
      </CanvasUiProvider>
      </CanvasNodeDisplayProvider>
      {assetDrawer.mounted && <AssetDrawer
        projectId={projectId}
        canvasId={canvasId}
        selecting={assetDrawerSelecting}
        uploading={assetUploading}
        present={assetDrawer.present}
        onConfirmUpload={(libraryAssetIds) => { void uploadLibraryAssets(libraryAssetIds); }}
        onInsertLocalAsset={(assetId) => { void insertLocalLibraryAsset(assetId); }}
        onClose={closeAssetDrawer}
      />}
      {recordsPanel.mounted && <GenerationRecordsPanel present={recordsPanel.present} onClose={() => setShowRecords(false)} />}
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
      <Dialog.Root open={Boolean(projectRenameTarget)} onOpenChange={(open) => { if (!open) setProjectRenameTarget(null); }}>
        <Dialog.Portal>
          <Dialog.Overlay className="project-dialog-backdrop" />
          <Dialog.Content asChild>
            <form className="project-dialog" onSubmit={(event) => {
              event.preventDefault();
              const title = projectRenameDraft.trim();
              if (!title || !projectRenameTarget) return;
              onRenameProjectById(projectRenameTarget.id, title);
              setProjectRenameTarget(null);
            }}>
              <Dialog.Title>重命名项目</Dialog.Title>
              <Dialog.Description className="project-dialog-description">请输入项目的新名称</Dialog.Description>
              <input autoFocus aria-label="项目名称" value={projectRenameDraft} onChange={(event) => setProjectRenameDraft(event.target.value)} />
              <footer><Dialog.Close asChild><button type="button">取消</button></Dialog.Close><button type="submit" disabled={!projectRenameDraft.trim()}>确认</button></footer>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
      <Dialog.Root open={Boolean(projectDeleteTarget)} onOpenChange={(open) => { if (!open) setProjectDeleteTarget(null); }}>
        <Dialog.Portal>
          <Dialog.Overlay className="project-dialog-backdrop" />
          <Dialog.Content className="project-dialog project-delete-dialog">
            <Dialog.Title>删除项目「{projectDeleteTarget?.title}」？</Dialog.Title>
            <Dialog.Description className="project-dialog-description">项目及其画布、仅由该项目引用的素材都会删除。此操作无法撤销。</Dialog.Description>
            <footer>
              <Dialog.Close asChild><button type="button">取消</button></Dialog.Close>
              <button className="is-danger" type="button" onClick={() => {
                const target = projectDeleteTarget;
                setProjectDeleteTarget(null);
                if (target) onDeleteProjectById(target.id);
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
        onOpenAssets={() => { setShowRecords(false); setAssetDrawerSelecting(true); setAssetDrawerOpen(true); }}
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
