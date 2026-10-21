import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { LayoutList, Maximize2, MessageCircle } from 'lucide-react';
import type { Viewport } from '@xyflow/react';
import { startCanvasBridge } from './canvas/bridgeClient';
import {
  buildCanvasModeReadyMessage,
  buildCanvasModeStatusMessage,
  EMBEDDED_APP_READY_MESSAGE,
  isCanvasAuthMessage,
  isCanvasModeControlMessage,
  isCanvasGraphUpdatedMessage,
  parseCanvasEmbeddedMode,
  planCanvasGraphUpdates,
  postCanvasModeMessage,
  type CanvasEmbeddedMode,
  type CanvasGraphPatch,
  type CanvasGraphUpdatedMessage,
} from './canvas/canvasEmbeddedMode';
import { MoonChatWindow } from './components/MoonChatWindow';
import { CanvasWorkspace } from './components/CanvasWorkspace';
import { HomePage } from './components/HomePage';
import { InspectorPanel } from './components/InspectorPanel';
import { ProjectHome } from './components/ProjectHome';
import { exportProjectPackage, importProjectPackage } from './services/projectPackage';
import { cleanupGenerationQueue, createCanvas, createProject, deleteCanvas, deleteProject, getAssetMetadatas, getCanvasRevision, isRevisionConflict, listCanvases, listProjects, loadCanvas, loadProject, projectAssetIds, renameProject as patchProjectTitle, saveCanvas, saveProject, scheduleAssetGarbageCollection } from './services/projectStorage';
import { useCanvasStore } from './store/canvasStore';
import { useMediaStore } from './store/mediaStore';
import type { CanvasDocumentV1, CanvasSummary, ProjectDocumentV2, ProjectSummary, SaveStatus } from './types';
import { LoginScreen } from './components/LoginScreen';
import { bootstrapJobAuth, clearToken, getJobToken, setJobToken, tokenExpired } from './services/jobAuth';
import { recoverGenerationJobs } from './canvas/generationRuntime';
import { decideLiveRefresh } from './canvas/liveRefresh';
import { recoverGraphExecutions, syncServerExecutions } from './canvas/graphExecutionService';
import { usePresence } from './hooks/usePresence';
import { hydrateReferencedCanvasAssets } from './services/canvasMedia';

type SideView = 'agent' | 'inspector';
type View = 'home' | 'projectHome' | 'canvas';
/** 一个防抖窗口里最多合并多少条图更新；超过就丢掉补丁、退回一次全量重载。 */
const MAX_PENDING_GRAPH_UPDATES = 20;
/** 保存失败提示的最小间隔：智能体持续写入时不刷屏。 */
const SAVE_ERROR_NOTICE_INTERVAL_MS = 8000;
// 视图层级：层级变大＝往里走（推入），变小＝往外退（退回）。动画方向按这个算。
const VIEW_RANK: Record<View, number> = { home: 0, projectHome: 1, canvas: 2 };

const downloadBlob = (blob: Blob, name: string) => {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url; anchor.download = name;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
};

export default function App() {
  const [view, setView] = useState<View>('home');
  // 视图切换方向：往画布里走是推入（从右滑进来），退回项目中心是退回（从左滑进来）。
  const previousViewRank = useRef(VIEW_RANK[view]);
  const viewMotion = useRef<'forward' | 'back'>('forward');
  if (VIEW_RANK[view] !== previousViewRank.current) {
    viewMotion.current = VIEW_RANK[view] > previousViewRank.current ? 'forward' : 'back';
    previousViewRank.current = VIEW_RANK[view];
  }
  // 写在 <html> 上给 .view-shell 读：三个视图壳分属不同组件，用属性比逐层传 prop 干净
  useLayoutEffect(() => {
    document.documentElement.dataset.viewMotion = viewMotion.current;
  }, [view]);
  // 登录门控：无画布 token（未从 /chat 跳转带 token，也未本地登录）→ 先显示登录页
  const [authed, setAuthed] = useState(() => !!getJobToken() && !tokenExpired());
  const embeddedModeRef = useRef<CanvasEmbeddedMode | null>(parseCanvasEmbeddedMode(window.location.search));
  const embeddedMode = embeddedModeRef.current;
  const embeddedWindow = new URLSearchParams(window.location.search).get('embedded') === '1';
  const [authMessage, setAuthMessage] = useState<string | null>(null);
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [projectsLoading, setProjectsLoading] = useState(true);
  const [project, setProject] = useState<ProjectDocumentV2 | null>(null);
  const [canvas, setCanvas] = useState<CanvasDocumentV1 | null>(null);
  const [canvases, setCanvases] = useState<CanvasSummary[]>([]);
  const [saveStatus, setSaveStatus] = useState<SaveStatus>('idle');
  const [sideView, setSideView] = useState<SideView>(embeddedWindow ? 'inspector' : 'agent');
  const [sideOpen, setSideOpen] = useState(!embeddedWindow && window.innerWidth > 1180);
  const [panelWidth, setPanelWidth] = useState(400); // Moon 智能体面板宽度（可拖拽）
  const [panelResizing, setPanelResizing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [canvasInteractionLocked, setCanvasInteractionLocked] = useState(false);
  // 显示/隐藏动效：拖拽把手与右下角「打开面板」浮标都要进出场，而不是啪一下出现/消失。
  const resizeHandle = usePresence(sideOpen, { duration: 140 });
  const reopenFab = usePresence(!sideOpen, { duration: 140 });
  // 聊天页「去画布编辑」带过来的媒体参数：只在本窗口内透传给内嵌 Moon 对话，用完即从 hash 抹掉
  const [chatMediaParam, setChatMediaParam] = useState('');
  const [isExporting, setIsExporting] = useState(false);
  /** 本地存在未落库的改动（保存失败后为 true）：4s 轮询在此期间让路，不用服务端图覆盖本地编辑。 */
  const [hasLocalChanges, setHasLocalChanges] = useState(false);
  const saveTimer = useRef<number | null>(null);
  const canvasRef = useRef<CanvasDocumentV1 | null>(null);
  const viewportRef = useRef<Viewport | null>(null);
  const panelRef = useRef<HTMLElement | null>(null);
  const pendingSave = useRef<CanvasDocumentV1 | null>(null);
  const saveInFlight = useRef<Promise<CanvasDocumentV1 | null> | null>(null);
  const graphUpdateTimer = useRef<number | null>(null);
  /** 一个防抖窗口内累积的图更新（可能带增量补丁），按到达顺序应用。 */
  const pendingGraphUpdates = useRef<CanvasGraphUpdatedMessage[]>([]);
  const lastSaveErrorNotice = useRef<{ message: string; at: number } | null>(null);
  /**
   * 保存失败后连续重试的次数：轮询每 4s 重试一次；超过 `MAX_SAVE_RETRIES` 就放弃保护本地改动、
   * 重载远端并提示——否则智能体持续写入时会永远在"重试保存"里打转，画布照样看不到新数据。
   */
  const saveRetryCount = useRef(0);
  const maintenanceRunRef = useRef<string | null>(null);
  const revision = useCanvasStore((state) => state.revision);
  const loadProjectGraph = useCanvasStore((state) => state.loadProjectGraph);
  const hydrateMetadata = useMediaStore((state) => state.hydrateMetadata);
  const clearAssets = useMediaStore((state) => state.clearAssets);

  const notify = useCallback((message: string) => {
    setNotice(message);
    window.setTimeout(() => setNotice(null), 2200);
  }, []);

  /**
   * 保存失败的可见提示（原来是 `.catch(() => undefined)` 静默吞掉）。
   *
   * <p>节流：智能体持续写入时同一原因最多每 8s 提示一次，避免提示条刷屏。
   *
   * <p>位置说明：必须定义在 4s 轮询 effect **之前**——轮询的依赖数组在渲染期求值，
   * 定义在之后会触发 `Cannot access 'notifySaveFailure' before initialization`。
   */
  const notifySaveFailure = useCallback((error: unknown) => {
    const message = isRevisionConflict(error)
      ? '画布已被智能体/他人修改，已保留你的本地改动，请确认后重试'
      : error instanceof Error ? error.message : '画布保存失败，已保留本地改动';
    const last = lastSaveErrorNotice.current;
    const now = Date.now();
    if (last && last.message === message && now - last.at < SAVE_ERROR_NOTICE_INTERVAL_MS) return;
    lastSaveErrorNotice.current = { message, at: now };
    notify(message);
  }, [notify]);

  // 画布 ↔ Moon 智能体面板 拖拽拉伸。健壮性要点：
  // - onMove 里 ev.buttons===0 直接停止 → 鼠标松开（含在 iframe 上松开导致 mouseup 未送达 document）后，悬浮不会再触发 resize；
  // - 拖拽期间直改 DOM 宽度（不 setState 每帧），避免 App 全树 re-render 与 react-flow 重排卡顿；mouseup/mouseleave 才 commit state；
  // - 拖拽期间给 app-shell 挂 is-panel-resizing：面板里嵌的 Moon 子站（iframe）吃掉 pointer-events 会让
  //   光标一旦移进子站就收不到 mousemove、拉伸卡住；由 CSS 把 iframe 置为 pointer-events:none 接管；
  // - ponytail: react-flow 对容器尺寸的 ResizeObserver 重排在所难免，若仍卡顿，升级路径是面板改绝对定位覆盖画布（不挤占 canvas 宽度）。
  const startPanelResize = useCallback((e: ReactMouseEvent<HTMLElement>) => {
    e.preventDefault();
    const el = panelRef.current;
    const startX = e.clientX;
    const startWidth = el?.offsetWidth ?? 440;
    const clamp = (w: number) => Math.min(680, Math.max(360, w));
    let lastWidth = startWidth;
    if (el) el.style.transition = 'none';
    setPanelResizing(true);
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    const stop = () => {
      const cur = panelRef.current;
      if (cur) cur.style.transition = '';
      setPanelResizing(false);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', stop);
      document.removeEventListener('mouseleave', stop);
      setPanelWidth(lastWidth);
    };
    const onMove = (ev: MouseEvent) => {
      if (ev.buttons === 0) { stop(); return; }
      lastWidth = clamp(startWidth + (startX - ev.clientX));
      const cur = panelRef.current;
      if (cur) { cur.style.width = `${lastWidth}px`; cur.style.flexBasis = `${lastWidth}px`; }
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', stop);
    document.addEventListener('mouseleave', stop);
  }, []);

  const refreshProjects = useCallback(async () => {
    setProjectsLoading(true);
    try { setProjects(await listProjects()); }
    finally { setProjectsLoading(false); }
  }, []);

  useEffect(() => {
    if (embeddedMode) return;
    if (!getJobToken()) {
      void bootstrapJobAuth().then((token) => {
        if (token) setAuthed(true);
      });
    }
  }, [embeddedMode]);

  useEffect(() => {
    if (!authed) return;
    void refreshProjects();
  }, [authed, refreshProjects]);
  // token 过期 → 清 token 回登录页（authed 门控重新为 false）
  useEffect(() => {
    let cancelled = false;
    const check = async () => {
      if (embeddedMode && !getJobToken()) return;
      const token = await bootstrapJobAuth();
      if (cancelled) return;
      if (token) setAuthed(true);
      else if (!getJobToken()) {
        // 登录请求可能刚好在这次 refresh 期间完成；刷新失败结果不能清掉新 token。
        clearToken();
        setAuthed(false);
      }
    };
    void check();
    const timer = window.setInterval(() => void check(), 30_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [embeddedMode]);
  useEffect(() => {
    const rejectAuth = (event: Event) => {
      const rejectedToken = (event as CustomEvent<{ token?: string | null }>).detail?.token;
      const currentToken = getJobToken();
      // 无权请求在父页面注入 token 前发出时，迟到的 401 不能清掉刚收到的新身份。
      if (rejectedToken ? rejectedToken !== currentToken : currentToken) return;
      setAuthMessage('登录已失效，请重新登录。');
      clearToken();
      setAuthed(false);
    };
    addEventListener('morrow:auth-rejected', rejectAuth);
    return () => removeEventListener('morrow:auth-rejected', rejectAuth);
  }, []);
  useEffect(() => {
    if (!embeddedMode) return;
    const acceptParentAuth = (event: MessageEvent) => {
      if (event.origin !== embeddedMode.parentOrigin
          || event.source !== window.parent
          || !isCanvasAuthMessage(event.data)) return;
      setJobToken(event.data.accessToken, event.data.userId);
      setAuthMessage(null);
      setAuthed(true);
    };
    addEventListener('message', acceptParentAuth);
    if (window.parent !== window) {
      window.parent.postMessage(
        { type: EMBEDDED_APP_READY_MESSAGE, key: 'canvas' },
        embeddedMode.parentOrigin,
      );
    }
    return () => removeEventListener('message', acceptParentAuth);
  }, [embeddedMode]);
  useEffect(() => {
    const rejectedCanvas = (event: Event) => {
      const path = (event as CustomEvent<{ path?: string }>).detail?.path ?? '';
      const activeCanvasId = canvasRef.current?.id;
      if (!activeCanvasId || !path.includes(`/canvases/${encodeURIComponent(activeCanvasId)}`)) return;
      if (saveTimer.current !== null) {
        window.clearTimeout(saveTimer.current);
        saveTimer.current = null;
      }
      pendingSave.current = null;
      setSaveStatus('error');
      setCanvas(null);
      clearAssets();
      if (project?.id) {
        setView('projectHome');
        window.history.replaceState(null, '', `#project=${encodeURIComponent(project.id)}`);
      } else {
        setView('home');
        window.history.replaceState(null, '', window.location.pathname);
      }
      notify('当前账号无权访问该画布，已停止自动保存');
    };
    addEventListener('morrow:permission-denied', rejectedCanvas);
    return () => removeEventListener('morrow:permission-denied', rejectedCanvas);
  }, [clearAssets, notify, project?.id]);
  useEffect(() => {
    if (!authed || view !== 'canvas' || !canvas?.id) return;
    const reconcile = () => {
      void hydrateReferencedCanvasAssets(canvas.id).catch(() => undefined);
      void recoverGenerationJobs()
        .then(() => {
          const key = `${project?.id ?? ''}:${canvas.id}`;
          if (maintenanceRunRef.current === key) return;
          maintenanceRunRef.current = key;
          return cleanupGenerationQueue().catch(() => undefined);
        })
        .catch(() => undefined);
      recoverGraphExecutions();
      // 服务端执行的批次：刷新/重开后把还在跑的那一批接回来（轮询 + 结果落地）。
      void syncServerExecutions(canvas.id).catch(() => undefined);
    };
    reconcile();
    const timer = window.setInterval(reconcile, 15_000);
    return () => window.clearInterval(timer);
  }, [authed, canvas?.id, project?.id, view]);
  useEffect(() => { canvasRef.current = canvas; }, [canvas]);
  useEffect(() => {
    if (view === 'canvas' && project && canvas?.id) {
      return startCanvasBridge(project.id, canvas.id, (status) => {
        if (status.state === 'ready') {
          window.dispatchEvent(new CustomEvent('morrow:canvas-bridge-ready', {
            detail: { projectId: project.id, canvasId: canvas.id },
          }));
        }
        if (!embeddedMode) return;
        if (status.state === 'ready') {
          postCanvasModeMessage(
            embeddedMode,
            buildCanvasModeReadyMessage(embeddedMode, canvas.id, canvas.title),
          );
        } else {
          postCanvasModeMessage(
            embeddedMode,
            buildCanvasModeStatusMessage(
              embeddedMode,
              status.state === 'connecting' ? 'bridge-connecting' : 'bridge-error',
              status.message,
            ),
          );
        }
      }, { projectTitle: project.title, canvasTitle: canvas.title });
    }
    return undefined;
  }, [embeddedMode, view, project?.id, canvas?.id, canvas?.title]);
  useEffect(() => {
    if (!embeddedMode || view !== 'projectHome' || project?.id !== embeddedMode.projectId) return;
    postCanvasModeMessage(
      embeddedMode,
      buildCanvasModeStatusMessage(embeddedMode, 'selecting', '请选择要编辑的画布'),
    );
  }, [embeddedMode, project?.id, view]);
  useEffect(() => {
    if (!embeddedMode) return;
    const onMessage = (event: MessageEvent) => {
      if (event.origin !== embeddedMode.parentOrigin || !isCanvasModeControlMessage(event.data)) return;
      if (event.data.conversationId !== embeddedMode.conversationId
        || event.data.projectId !== embeddedMode.projectId) return;
      setCanvasInteractionLocked(event.data.locked);
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [embeddedMode]);
  useEffect(() => {
    const mobile = window.matchMedia('(max-width: 1180px)');
    const syncSidePanel = (event: MediaQueryListEvent | MediaQueryList) => { if (event.matches) setSideOpen(false); };
    syncSidePanel(mobile);
    mobile.addEventListener('change', syncSidePanel);
    return () => mobile.removeEventListener('change', syncSidePanel);
  }, []);

  const persistCurrentCanvas = useCallback(async () => {
    const current = canvasRef.current;
    if (!current) return null;
    const graph = useCanvasStore.getState();
    const next: CanvasDocumentV1 = {
      ...current,
      nodes: graph.nodes.map((node) => node.selected ? { ...node, selected: false } : node),
      edges: graph.edges,
      generationRecords: graph.generationRecords,
      executions: graph.executions,
      viewport: viewportRef.current ?? current.viewport,
      updatedAt: Date.now(),
    };
    pendingSave.current = next;
    setSaveStatus('saving');
    setHasLocalChanges(true);
    try {
      do {
        if (!saveInFlight.current) {
          saveInFlight.current = (async () => {
            let saved: CanvasDocumentV1 | null = null;
            while (pendingSave.current) {
              const candidate = pendingSave.current;
              pendingSave.current = null;
              const persisted = await saveCanvas(candidate);   // 返回 applyGraph 后的新 revision
              saved = persisted;
              if (canvasRef.current?.id === candidate.id) {
                canvasRef.current = persisted;
                setCanvas(persisted);
                setSaveStatus('saved');
              }
            }
            return saved;
          })().finally(() => { saveInFlight.current = null; });
        }
        await saveInFlight.current;
      } while (pendingSave.current);
      // 落库成功（含 40901 冲突合并后的成功）→ 本地与服务端对齐，轮询恢复工作。
      setHasLocalChanges(false);
      // 冲突合并可能把智能体刚在服务端建的节点带回来了：立刻采用，不用等下一次轮询或刷新。
      const latest = canvasRef.current;
      if (latest?.id === next.id) {
        const known = new Set(useCanvasStore.getState().nodes.map((node) => node.id));
        const incoming = (latest.nodes ?? []).filter((node) => !known.has(node.id));
        if (incoming.length) {
          const assets = await getAssetMetadatas(projectAssetIds(latest.nodes));
          hydrateMetadata(assets);
          loadProjectGraph(latest);
          console.info(`[canvas] 已同步服务端新增的 ${incoming.length} 个节点（保存冲突合并）`);
        }
      }
      void refreshProjects();
      return canvasRef.current?.id === next.id ? canvasRef.current : next;
    } catch (error) {
      // 不再静默：保留本地改动标记（让轮询让路），并把原因交给调用方/提示条。
      setSaveStatus('error');
      setHasLocalChanges(true);
      console.warn('[canvas] 画布保存失败，本地改动已保留', error);
      throw error;
    }
  }, [refreshProjects, getAssetMetadatas, projectAssetIds, hydrateMetadata, loadProjectGraph]);

  const openProjectHome = useCallback(async (projectId: string) => {
    const nextProject = await loadProject(projectId);
    if (!nextProject) throw new Error('项目不存在');
    setProject(nextProject);
    setCanvas(null);
    const summaries = await listCanvases(projectId);
    setCanvases(summaries);
    setView('projectHome');
    window.history.replaceState(null, '', `#project=${encodeURIComponent(projectId)}`);
    return summaries;
  }, []);

  const openCanvas = useCallback(async (projectId: string, canvasId: string) => {
    const nextProject = await loadProject(projectId);
    if (!nextProject) throw new Error('项目不存在');
    const nextCanvas = await loadCanvas(canvasId, projectId);
    if (!nextCanvas) throw new Error('画布不存在');
    const assets = await getAssetMetadatas(projectAssetIds(nextCanvas.nodes));
    clearAssets();
    hydrateMetadata(assets);
    loadProjectGraph(nextCanvas);
    viewportRef.current = nextCanvas.viewport;
    setProject(nextProject);
    setCanvas(nextCanvas);
    setCanvases(await listCanvases(projectId));
    setSaveStatus('saved');
    // 整图重载 = 本地图被服务端真相替换，未落库标记必须清掉，否则轮询会被永久挡住。
    setHasLocalChanges(false);
    setSideView(embeddedWindow ? 'inspector' : 'agent');
    setSideOpen(embeddedWindow ? false : window.innerWidth > 1180);
    setView('canvas');
    window.history.replaceState(null, '', `#project=${encodeURIComponent(projectId)}&canvas=${encodeURIComponent(canvasId)}`);
  }, [clearAssets, embeddedMode, embeddedWindow, hydrateMetadata, loadProjectGraph]);

  /**
   * 应用一条远端增量补丁（不整图重载）。
   *
   * <p>身份键只有节点/边的 `id`：**绝不**用 `assetUrl` 相等判断"节点是否已存在/内容是否没变"
   * （补丁里的 `assetUrl` 会被后端重新签名，字符串与本地存量不一致）。
   * <p>返回 false 表示当前画布文档不可用（已切换/未打开），调用方必须退回全量重载。
   */
  const applyRemoteGraphPatch = useCallback((patch: CanvasGraphPatch): boolean => {
    const current = canvasRef.current;
    if (!current) return false;
    const summary = useCanvasStore.getState().applyRemotePatch(patch);
    const next: CanvasDocumentV1 = {
      ...current,
      nodes: useCanvasStore.getState().nodes,
      edges: useCanvasStore.getState().edges,
      updatedAt: Date.now(),
    };
    canvasRef.current = next;
    setCanvas(next);
    // 素材登记以 assetId 为准（assetUrl 只用于直接渲染，可能带 token，不参与判定）；
    // 补丁里的节点没有 assetId 就按"没有素材"处理，不发多余请求。
    const knownAssetIds = useMediaStore.getState().metadata;
    const hasUnknownAsset = [...summary.addedNodeIds, ...summary.updatedNodeIds].some((nodeId) => {
      const node = useCanvasStore.getState().nodes.find((item) => item.id === nodeId);
      const assetId = node?.data.assetId;
      if (!assetId) return false;
      return !knownAssetIds[assetId];
    });
    if (hasUnknownAsset) {
      void hydrateReferencedCanvasAssets(current.id).catch(() => undefined);
    }
    return true;
  }, []);

  useEffect(() => {
    if (!embeddedMode) return;
    const onGraphUpdated = (event: MessageEvent) => {
      if (event.origin !== embeddedMode.parentOrigin
          || !isCanvasGraphUpdatedMessage(event.data)) return;
      if (event.data.conversationId !== embeddedMode.conversationId
          || event.data.projectId !== embeddedMode.projectId) return;
      pendingGraphUpdates.current.push(event.data);
      console.info(`[canvas-live] 收到图更新消息 revision=${event.data.revision} `
        + `patch=${event.data.patch ? 'yes' : 'no'} 队列=${pendingGraphUpdates.current.length}`);
      // 有界：一次防抖窗口里堆太多就丢弃补丁、退回一次全量重载（revision 高水位仍然正确）。
      if (pendingGraphUpdates.current.length > MAX_PENDING_GRAPH_UPDATES) {
        pendingGraphUpdates.current = [{ ...event.data, patch: undefined }];
      }
      if (graphUpdateTimer.current !== null) window.clearTimeout(graphUpdateTimer.current);
      graphUpdateTimer.current = window.setTimeout(() => {
        graphUpdateTimer.current = null;
        const updates = pendingGraphUpdates.current;
        pendingGraphUpdates.current = [];
        const latest = updates.at(-1);
        if (!latest) return;
        const target = canvasRef.current;
        const plan = planCanvasGraphUpdates(
          updates,
          target ? { canvasId: target.id, revision: target.revision ?? 0 } : null,
        );
        console.info(`[canvas-live] 决策=${plan?.mode ?? 'none'}`
          + `${plan && plan.mode === 'reload' ? ` reason=${plan.reason}` : ''}`
          + `${plan ? ` revision=${plan.revision}` : ''}`
          + `${target ? ` local=${target.revision ?? 0}` : ' local=无画布'}`);
        if (plan?.mode === 'patch' && target) {
          try {
            for (const patch of plan.patches) {
              // false = 应用期间文档被换掉（切画布/关画布），revision 复核会兜住。
              if (!applyRemoteGraphPatch(patch)) throw new Error('画布已切换，补丁未完整应用');
            }
            // 本地文档 revision 提升到本批最高 revision，下一轮轮询才不会又全量重载。
            const next: CanvasDocumentV1 = {
              ...target,
              nodes: useCanvasStore.getState().nodes,
              edges: useCanvasStore.getState().edges,
              revision: plan.revision,
            };
            canvasRef.current = next;
            setCanvas(next);
            // 复核：补丁是**有界累积**的，可能只覆盖了部分变更。应用后本地 revision 必须正好
            // 等于消息 revision，对不上就退回一次全量重载，绝不留半截图。
            if ((canvasRef.current?.revision ?? -1) !== plan.revision) {
              throw new Error('画布补丁不完整，已重新加载画布');
            }
            return;
          } catch (error) {
            // 半应用不可接受：退回一次整图重载把本地图对齐服务端真相。
            notify(error instanceof Error ? error.message : '画布补丁应用失败，已重新加载画布');
          }
        }
        void openCanvas(latest.projectId, latest.canvasId)
          .catch((error) => notify(error instanceof Error ? error.message : '画布更新加载失败'));
      }, 120);
    };
    window.addEventListener('message', onGraphUpdated);
    return () => {
      window.removeEventListener('message', onGraphUpdated);
      if (graphUpdateTimer.current !== null) window.clearTimeout(graphUpdateTimer.current);
    };
  }, [applyRemoteGraphPatch, embeddedMode, notify, openCanvas]);

  // 独立窗口打开的画布收不到 parent 的 graph-updated 消息：轮询轻量 revision 接口，
  // 智能体在服务端建节点/连线后把变化拉到本地。本地有未落库改动时让路，避免覆盖未提交的编辑。
  useEffect(() => {
    if (view !== 'canvas' || !project || !canvas) return;
    const projectId = project.id;
    const canvasId = canvas.id;
    let stopped = false;
    let lastSkipReason = '';
    const tick = () => {
      if (stopped) return;
      const decision = decideLiveRefresh({
        hidden: document.hidden,
        // 看"是否真的有保存在飞"，而不是可能卡死的 saveStatus 字符串：
        // persistCurrentCanvas 只在画布 id 未变时才把状态置回 'saved'，期间换过画布就永远是 'saving'。
        savingInFlight: saveInFlight.current !== null,
        hasPendingDebounce: saveTimer.current !== null,
        hasLocalChanges,
        saveRetryCount: saveRetryCount.current,
        // 拖动中/批次执行中都不能整图重载：前者会把节点位置打回服务端旧值（松手瞬间"弹回原位"），
        // 后者会把本地 executions 换成服务端旧快照，正在跑的批次直接消失。
        dragging: useCanvasStore.getState().dragStartSnapshot !== null,
        executing: useCanvasStore.getState().executions.some((execution) => execution.status === 'running'),
      });
      if (decision.action === 'skip') {
        // 只在原因变化时打一行，避免每 4s 刷屏
        if (lastSkipReason !== decision.reason) {
          lastSkipReason = decision.reason;
          console.info(`[canvas-live] 轮询让路 reason=${decision.reason}`);
        }
        return;
      }
      lastSkipReason = '';
      if (decision.action === 'retry-save') {
        // 上次保存失败留下的未落库改动，靠"重试保存"化解，而不是永久让路——
        // 否则轮询再也不工作，表现为"必须刷新才出画布数据"。
        saveRetryCount.current += 1;
        console.info(`[canvas-live] 轮询转为重试保存（第 ${saveRetryCount.current} 次）`);
        void persistCurrentCanvas()
          .then(() => { saveRetryCount.current = 0; })
          .catch((error) => notifySaveFailure(error));
        return;
      }
      if (decision.action === 'reload-after-conflict') {
        // 重试预算用尽：智能体一直在写，本地改动落不了库。优先让用户看到智能体的数据，
        // 并明确告知本地改动已被放弃（旧行为是永久停摆，只能刷新）。
        saveRetryCount.current = 0;
        setHasLocalChanges(false);
        console.info('[canvas-live] 重试保存已达上限，放弃本地改动并重载远端');
        notify('画布已被智能体更新，已加载最新版本（本地未保存的改动已放弃）');
        void openCanvas(projectId, canvasId)
          .catch((error) => notify(error instanceof Error ? error.message : '画布更新加载失败'));
        return;
      }
      void getCanvasRevision(canvasId)
        .then((revision) => {
          if (stopped) return;
          const local = canvasRef.current?.revision ?? 0;
          if (revision <= local) return;
          if (decision.action === 'check' && decision.deferReload) {
            // 本地还有保存在飞/防抖：这一轮只感知，不重载；下一次 tick（4s 后）再落地。
            console.info(`[canvas-live] 服务端有更新 server=${revision} local=${local}，等本地保存落地后再整图重载`);
            return;
          }
          console.info(`[canvas-live] 服务端有更新 server=${revision} local=${local} → 整图重载`);
          return openCanvas(projectId, canvasId);
        })
        .catch(() => { /* 轮询失败静默，下一轮再试 */ });
    };
    const timer = window.setInterval(tick, 4000);
    // 后台标签页刻意不发请求（document.hidden 让路）；切回前台时**立刻**补一次检查，
    // 否则用户从聊天窗口切回画布要白等最多 4 秒才看到智能体的更新。
    const onVisible = () => { if (!document.hidden) tick(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      stopped = true;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [view, project, canvas, hasLocalChanges, openCanvas, persistCurrentCanvas, notifySaveFailure, notify]);

  /**
   * 画布信箱里的写命令跑在浏览器里，用的是本地这份图。
   *
   * <p>智能体刚在服务端建的节点，本地还没拉下来时，命令会报 `NODE_NOT_FOUND` /
   * `CANVAS_REVISION_CONFLICT`（2026-10-19 实测：apply_plan 建完 10 个节点后立刻
   * `canvas_group_nodes`，前端报「节点不存在：…」）。这里接一个「先对齐服务端再重试」的握手：
   * 命令层派发 `morrow:canvas-resync`，我们整图重载后回 `morrow:canvas-resynced`，
   * 命令层收到后重试一次，用户就不会再看到那次假报错。
   */
  useEffect(() => {
    const onResync = (event: Event) => {
      const detail = (event as CustomEvent<{ projectId?: string; canvasId?: string; reason?: string }>).detail ?? {};
      const targetProject = detail.projectId ?? project?.id;
      const targetCanvas = detail.canvasId ?? canvasRef.current?.id;
      const done = () => window.dispatchEvent(new CustomEvent('morrow:canvas-resynced', {
        detail: { canvasId: targetCanvas ?? '' },
      }));
      if (!targetProject || !targetCanvas) {
        done();
        return;
      }
      console.info(`[canvas-live] 画布命令需要最新图（${detail.reason ?? 'resync'}）→ 整图重载后重试`);
      void openCanvas(targetProject, targetCanvas)
        .catch((error) => {
          console.warn('[canvas-live] 对齐服务端画布失败', error);
        })
        .finally(done);
    };
    window.addEventListener('morrow:canvas-resync', onResync);
    return () => window.removeEventListener('morrow:canvas-resync', onResync);
  }, [openCanvas, project?.id]);

  const newProject = useCallback(async () => {
    if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    if (canvasRef.current) await persistCurrentCanvas();
    const created = await createProject('Untitled');
    await refreshProjects();
    await openProjectHome(created.id);
  }, [openProjectHome, persistCurrentCanvas, refreshProjects]);

  const createAndOpenCanvas = useCallback(async (targetProjectId: string) => {
    if (!targetProjectId) return;
    const existing = await listCanvases(targetProjectId);
    const created = await createCanvas(targetProjectId, `画布 ${existing.length + 1}`);
    await refreshProjects();
    await openCanvas(targetProjectId, created.id);
  }, [openCanvas, refreshProjects]);

  useEffect(() => {
    const match = window.location.hash.match(/^#project=([^&]+)(?:&canvas=([^&]+))?(?:&media=(.*))?$/);
    if (!match) return;
    const projectId = decodeURIComponent(match[1]);
    const canvasId = match[2] ? decodeURIComponent(match[2]) : undefined;
    if (match[3]) setChatMediaParam(decodeURIComponent(match[3]));
    if (canvasId) {
      void openCanvas(projectId, canvasId)
        .catch(() => window.history.replaceState(null, '', window.location.pathname));
      return;
    }
    void openProjectHome(projectId)
      .then((summaries) => {
        if (embeddedMode && summaries.length === 0) return createAndOpenCanvas(projectId);
        return undefined;
      })
      .catch(() => window.history.replaceState(null, '', window.location.pathname));
  }, [createAndOpenCanvas, embeddedMode, openCanvas, openProjectHome]);

  const switchCanvas = useCallback(async (canvasId: string) => {
    if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    await persistCurrentCanvas();
    if (!project) return;
    await openCanvas(project.id, canvasId);
  }, [openCanvas, persistCurrentCanvas, project]);

  const renameCanvas = useCallback(async (canvasId: string, title: string) => {
    if (canvasRef.current?.id === canvasId) {
      canvasRef.current = { ...canvasRef.current, title };
      setCanvas((current) => current?.id === canvasId ? { ...current, title } : current);
      await persistCurrentCanvas();
      if (project) setCanvases(await listCanvases(project.id));
      notify(`画布已重命名为「${title}」`);
      return;
    }
    const target = await loadCanvas(canvasId);
    if (!target) return;
    await saveCanvas({ ...target, title });
    if (project) setCanvases(await listCanvases(project.id));
    notify(`画布已重命名为「${title}」`);
  }, [notify, persistCurrentCanvas, project]);

  useEffect(() => {
    const onCanvasCreated = (event: Event) => {
      const detail = (event as CustomEvent<{
        projectId?: string;
        canvasId?: string;
        title?: string;
        activate?: boolean;
      }>).detail;
      if (!detail?.projectId || !detail.canvasId || detail.projectId !== project?.id) return;
      void listCanvases(detail.projectId).then(setCanvases).catch(() => undefined);
      if (detail.activate) {
        void openCanvas(detail.projectId, detail.canvasId)
          .then(() => notify(`已新建并切换画布「${detail.title || detail.canvasId}」`))
          .catch((error) => notify(error instanceof Error ? error.message : '切换新画布失败'));
      } else {
        notify(`已新建画布「${detail.title || detail.canvasId}」`);
      }
    };
    const onCanvasRenamed = (event: Event) => {
      const detail = (event as CustomEvent<{
        projectId?: string;
        canvasId?: string;
        title?: string;
      }>).detail;
      if (!detail?.projectId || !detail.canvasId || detail.projectId !== project?.id) return;
      if (canvasRef.current?.id === detail.canvasId && detail.title) {
        const next = { ...canvasRef.current, title: detail.title };
        canvasRef.current = next;
        setCanvas(next);
      }
      void listCanvases(detail.projectId).then(setCanvases).catch(() => undefined);
      notify(`画布已重命名为「${detail.title || detail.canvasId}」`);
    };
    addEventListener('morrow:canvas-created', onCanvasCreated);
    addEventListener('morrow:canvas-renamed', onCanvasRenamed);
    return () => {
      removeEventListener('morrow:canvas-created', onCanvasCreated);
      removeEventListener('morrow:canvas-renamed', onCanvasRenamed);
    };
  }, [notify, openCanvas, project?.id]);

  const deleteCanvasById = useCallback(async (canvasId: string) => {
    const deletingCurrent = canvasRef.current?.id === canvasId;
    if (deletingCurrent) {
      if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
      pendingSave.current = null;
      await saveInFlight.current?.catch(() => null);
    }
    await deleteCanvas(canvasId);
    scheduleAssetGarbageCollection();
    const remaining = project ? await listCanvases(project.id) : [];
    setCanvases(remaining);
    if (deletingCurrent && project) {
      if (remaining.length) {
        await openCanvas(project.id, remaining[0].id);
      } else {
        await openProjectHome(project.id);
      }
    }
    await refreshProjects();
    notify('画布已删除');
  }, [notify, openCanvas, openProjectHome, project, refreshProjects]);

  const renameProject = useCallback(async (title: string) => {
    setProject((current) => {
      const next = current ? { ...current, title } : current;
      if (next) void saveProject(next);
      return next;
    });
  }, []);

  const switchProject = useCallback(async (projectId: string) => {
    if (useCanvasStore.getState().projectId === projectId) return;
    if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    await persistCurrentCanvas();
    await refreshProjects();
    await openProjectHome(projectId);
  }, [openProjectHome, persistCurrentCanvas, refreshProjects]);

  const importPackage = useCallback(async (file: File) => {
    const imported = await importProjectPackage(file);
    await refreshProjects();
    await openProjectHome(imported.project.id);
    notify('项目包已完整导入');
  }, [notify, openProjectHome, refreshProjects]);

  const exportPackage = useCallback(async () => {
    if (!project) return;
    if (isExporting) return;
    setIsExporting(true);
    try {
      if (canvasRef.current) await persistCurrentCanvas();
      const blob = await exportProjectPackage(project.id);
      downloadBlob(blob, `${project.title || 'Untitled'}.morrow`);
      notify('项目包已导出');
    } catch (error) { notify(error instanceof Error ? error.message : '项目包导出失败'); }
    finally { setIsExporting(false); }
  }, [isExporting, notify, persistCurrentCanvas, project]);

  useEffect(() => {
    if (view !== 'canvas' || !canvas) return;
    // store revision 归零 = loadProjectGraph（整图重载 / 切换画布），**不是用户编辑**：
    // 不能触发自动保存，否则会把刚从服务端拉下来的图再回写一遍（revision 乒乓 + 冲突风险，
    // 而那次回写一旦失败就会让 hasLocalChanges 卡住、把 4s 轮询永久关掉）。
    if (revision === 0) return;
    if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    setSaveStatus('saving');
    setHasLocalChanges(true);
    saveTimer.current = window.setTimeout(() => {
      void persistCurrentCanvas().catch((error) => notifySaveFailure(error));
    }, 550);
    return () => { if (saveTimer.current !== null) window.clearTimeout(saveTimer.current); };
  }, [persistCurrentCanvas, canvas?.id, canvas?.title, revision, view, notifySaveFailure]);

  useEffect(() => {
    const saveWhenHidden = () => {
      if (document.visibilityState !== 'hidden' || !canvasRef.current) return;
      void persistCurrentCanvas().catch((error) => notifySaveFailure(error));
    };
    document.addEventListener('visibilitychange', saveWhenHidden);
    return () => document.removeEventListener('visibilitychange', saveWhenHidden);
  }, [persistCurrentCanvas, notifySaveFailure]);

  const returnToProject = async () => {
    if (!project) return;
    if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    const state = useCanvasStore.getState();
    const protectedIds = [...state.past, ...state.future].flatMap((snapshot) => [...projectAssetIds(snapshot.nodes)]);
    // 导航不依赖 IndexedDB 保存：保存或清理变慢时也必须立即回到项目中心。
    setCanvas(null);
    setView('projectHome');
    window.history.replaceState(null, '', `#project=${encodeURIComponent(project.id)}`);
    void persistCurrentCanvas()
      .then(() => scheduleAssetGarbageCollection(protectedIds))
      .catch(() => notify('返回前保存失败，已保留项目中心视图'));
    void listCanvases(project.id)
      .then(setCanvases)
      .catch(() => undefined);
  };

  const returnHome = async () => {
    setView('home');
    setProject(null);
    setCanvas(null);
    clearAssets();
    window.history.replaceState(null, '', window.location.pathname);
    await refreshProjects();
  };

  const removeProject = useCallback(async (projectId: string) => {
    await deleteProject(projectId);
    scheduleAssetGarbageCollection();
    await refreshProjects();
  }, [refreshProjects]);

  const removeCurrentProject = useCallback(async () => {
    const currentProject = project;
    if (!currentProject) return;
    if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    if (saveInFlight.current) await saveInFlight.current;
    await deleteProject(currentProject.id);
    scheduleAssetGarbageCollection();
    setView('home');
    setProject(null);
    setCanvas(null);
    clearAssets();
    window.history.replaceState(null, '', window.location.pathname);
    await refreshProjects();
    notify('项目已从本地删除');
  }, [clearAssets, notify, project, refreshProjects]);

  /** 项目切换列表里的重命名：按 id 直接改名，当前项目同步刷新标题 */
  const renameProjectById = useCallback(async (projectId: string, title: string) => {
    const next = title.trim();
    if (!next) return;
    await patchProjectTitle(projectId, next);
    setProject((current) => (current && current.id === projectId ? { ...current, title: next } : current));
    await refreshProjects();
  }, [refreshProjects]);

  /** 项目切换列表里的删除：删的是当前项目时同时退回项目中心 */
  const removeProjectById = useCallback(async (projectId: string) => {
    if (project?.id === projectId) {
      await removeCurrentProject();
      return;
    }
    await removeProject(projectId);
  }, [project?.id, removeCurrentProject, removeProject]);

  if (!authed) return <LoginScreen message={authMessage} onLogin={() => { setAuthMessage(null); setAuthed(true); }} />;

  if (view === 'home') {
    return <><HomePage projects={projects} loading={projectsLoading} onCreate={() => void newProject().catch((error) => notify(error.message))} onOpen={(id) => void openProjectHome(id).catch((error) => notify(error.message))} onDelete={(id) => void removeProject(id).catch((error) => notify(error.message))} onImport={(file) => void importPackage(file).catch((error) => notify(error instanceof Error ? error.message : '项目包导入失败'))} />{notice && <div className="canvas-status-toast app-status-toast" role="status">{notice}</div>}</>;
  }

  if (view === 'projectHome' && project) {
    return <><ProjectHome project={project} canvases={canvases} canvasModeLocked={!!embeddedMode} onBack={() => void returnHome()} onCreateCanvas={() => void createAndOpenCanvas(project.id).catch((error) => notify(error.message))} onOpenCanvas={(canvasId) => void openCanvas(project.id, canvasId).catch((error) => notify(error.message))} onRenameCanvas={(canvasId, title) => void renameCanvas(canvasId, title)} onDeleteCanvas={(canvasId) => void deleteCanvasById(canvasId).catch((error) => notify(error.message))} onRenameProject={(title) => void renameProject(title)} onDeleteProject={() => void removeCurrentProject().catch((error) => notify(error.message))} onExportProject={() => void exportPackage()} onNotify={notify} />{notice && <div className="canvas-status-toast app-status-toast" role="status">{notice}</div>}</>;
  }

  if (!project || !canvas) return null;

  return (
    <div className={`app-shell view-shell ${sideOpen ? '' : 'side-is-closed'}${panelResizing ? ' is-panel-resizing' : ''}`}>
      <CanvasWorkspace
        projectId={project.id}
        projectTitle={project.title}
        canvasId={canvas.id}
        canvasTitle={canvas.title}
        canvases={canvases}
        projects={projects}
        saveStatus={saveStatus}
        canvasModeLocked={!!embeddedMode}
        canvasInteractionLocked={canvasInteractionLocked}
        initialViewport={canvas.viewport}
        onProjectTitleChange={(title) => { setProject((current) => { const next = current ? { ...current, title } : current; if (next) void saveProject(next); return next; }); }}
        onViewportChange={(viewport) => { viewportRef.current = viewport; }}
        onOpenHome={() => void returnToProject()}
        onCreateProject={() => void newProject().catch((error) => notify(error instanceof Error ? error.message : '新建项目失败'))}
        onSwitchProject={(projectId) => void switchProject(projectId).catch((error) => notify(error instanceof Error ? error.message : '切换项目失败'))}
        onSwitchCanvas={(canvasId) => void switchCanvas(canvasId).catch((error) => notify(error instanceof Error ? error.message : '切换画布失败'))}
        onNewCanvas={() => void createAndOpenCanvas(project.id).catch((error) => notify(error.message))}
        onRenameCanvas={(canvasId, title) => void renameCanvas(canvasId, title).catch((error) => notify(error instanceof Error ? error.message : '重命名画布失败'))}
        onDeleteCanvas={(canvasId) => void deleteCanvasById(canvasId).catch((error) => notify(error instanceof Error ? error.message : '删除画布失败'))}
        onDeleteProject={() => void removeCurrentProject().catch((error) => notify(error instanceof Error ? error.message : '删除项目失败'))}
        onRenameProjectById={(projectId, title) => void renameProjectById(projectId, title).catch((error) => notify(error instanceof Error ? error.message : '重命名项目失败'))}
        onDeleteProjectById={(projectId) => void removeProjectById(projectId).catch((error) => notify(error instanceof Error ? error.message : '删除项目失败'))}
        onOpenInspector={() => { setSideView('inspector'); setSideOpen(true); }}
        onToggleSidePanel={() => setSideOpen((open) => !open)}
        onExportProject={() => void exportPackage()}
        isExporting={isExporting}
        onNotify={notify}
      />
      {resizeHandle.mounted && (
        <div
          className="panel-resize-handle motion-presence"
          data-present={resizeHandle.present}
          onMouseDown={startPanelResize}
          role="separator"
          aria-orientation="vertical"
          aria-label="调整面板宽度"
        ><span className="panel-resize-grip" /></div>
      )}
      <aside
        ref={panelRef}
        className="chat-workspace"
        style={sideOpen ? { width: panelWidth, flexBasis: panelWidth } : undefined}
      >
        <header className="chat-topbar">
          {/* 两个视图各占一个标签，选中态落在当前视图上：用户一眼能看出这是切换而不是单个开关 */}
          <div
            className={`workspace-side-tabs${embeddedWindow ? ' is-single' : ''}`}
            role="tablist"
            aria-label="面板视图"
          >
            {!embeddedWindow && (
              <button
                type="button"
                role="tab"
                aria-selected={sideView === 'agent'}
                className={`workspace-side-tab${sideView === 'agent' ? ' is-active' : ''}`}
                onClick={() => { setSideView('agent'); setSideOpen(true); }}
              >
                <MessageCircle size={16} />Moon 智能体
              </button>
            )}
            <button
              type="button"
              role="tab"
              aria-selected={sideView === 'inspector'}
              className={`workspace-side-tab${sideView === 'inspector' ? ' is-active' : ''}`}
              onClick={() => { setSideView('inspector'); setSideOpen(true); }}
            >
              <LayoutList size={16} />节点属性
            </button>
          </div>
          <div className="chat-topbar-spacer" />
          <button onClick={() => setSideOpen(false)} aria-label="收起面板" title="收起面板（B）"><Maximize2 size={16} /></button>
        </header>
        <div className="chat-body">
          {!embeddedWindow && (
            <div className="chat-view" data-active={sideView === 'agent'}>
              <MoonChatWindow media={chatMediaParam} />
            </div>
          )}
          <div className="chat-view" data-active={sideView === 'inspector'}><InspectorPanel /></div>
        </div>
      </aside>
      {reopenFab.mounted && (
        <button
          className="reopen-chat motion-presence"
          data-present={reopenFab.present}
          onClick={() => {
            if (embeddedWindow) setSideView('inspector');
            setSideOpen(true);
          }}
          aria-label={sideView === 'inspector' ? '打开节点属性' : '打开 Moon 智能体'}
        >
          {sideView === 'inspector' ? <LayoutList size={20} /> : <MessageCircle size={20} />}
        </button>
      )}
      {notice && <div className="canvas-status-toast app-status-toast" role="status">{notice}</div>}
    </div>
  );
}
