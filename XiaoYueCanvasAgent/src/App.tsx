import { useCallback, useEffect, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { Maximize2, MessageCircle, SlidersHorizontal } from 'lucide-react';
import type { Viewport } from '@xyflow/react';
import { startCanvasBridge } from './canvas/bridgeClient';
import {
  buildCanvasModeReadyMessage,
  buildCanvasModeStatusMessage,
  isCanvasModeControlMessage,
  isCanvasGraphUpdatedMessage,
  parseCanvasEmbeddedMode,
  postCanvasModeMessage,
  type CanvasEmbeddedMode,
} from './canvas/canvasEmbeddedMode';
import { MoonChatWindow } from './components/MoonChatWindow';
import { CanvasWorkspace } from './components/CanvasWorkspace';
import { HomePage } from './components/HomePage';
import { InspectorPanel } from './components/InspectorPanel';
import { ProjectHome } from './components/ProjectHome';
import { exportProjectPackage, importProjectPackage } from './services/projectPackage';
import { collectAssetGarbage, createCanvas, createProject, deleteCanvas, deleteProject, getAssetMetadatas, listCanvases, listProjects, loadCanvas, loadProject, projectAssetIds, saveCanvas, saveProject } from './services/projectStorage';
import { useCanvasStore } from './store/canvasStore';
import { useMediaStore } from './store/mediaStore';
import type { CanvasDocumentV1, CanvasSummary, ProjectDocumentV2, ProjectSummary, SaveStatus } from './types';
import { LoginScreen } from './components/LoginScreen';
import { bootstrapJobAuth, clearToken, getJobToken, tokenExpired } from './services/jobAuth';
import { recoverGenerationJobs } from './canvas/generationRuntime';
import { recoverGraphExecutions } from './canvas/graphExecutionService';
import { hydrateReferencedCanvasAssets } from './services/canvasMedia';

type SideView = 'agent' | 'inspector';
type View = 'home' | 'projectHome' | 'canvas';

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
  // 登录门控：无画布 token（未从 /chat 跳转带 token，也未本地登录）→ 先显示登录页
  const [authed, setAuthed] = useState(() => !!getJobToken());
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
  const [panelWidth, setPanelWidth] = useState(400); // 执行服务面板宽度（可拖拽）
  const [notice, setNotice] = useState<string | null>(null);
  const [canvasInteractionLocked, setCanvasInteractionLocked] = useState(false);
  const [isExporting, setIsExporting] = useState(false);
  const saveTimer = useRef<number | null>(null);
  const canvasRef = useRef<CanvasDocumentV1 | null>(null);
  const viewportRef = useRef<Viewport | null>(null);
  const panelRef = useRef<HTMLElement | null>(null);
  const pendingSave = useRef<CanvasDocumentV1 | null>(null);
  const saveInFlight = useRef<Promise<CanvasDocumentV1 | null> | null>(null);
  const graphUpdateTimer = useRef<number | null>(null);
  const revision = useCanvasStore((state) => state.revision);
  const loadProjectGraph = useCanvasStore((state) => state.loadProjectGraph);
  const hydrateMetadata = useMediaStore((state) => state.hydrateMetadata);
  const clearAssets = useMediaStore((state) => state.clearAssets);

  const notify = useCallback((message: string) => {
    setNotice(message);
    window.setTimeout(() => setNotice(null), 2200);
  }, []);

  // 画布 ↔ 执行服务面板 拖拽拉伸。健壮性要点：
  // - onMove 里 ev.buttons===0 直接停止 → 鼠标松开（含在 iframe 上松开导致 mouseup 未送达 document）后，悬浮不会再触发 resize；
  // - 拖拽期间直改 DOM 宽度（不 setState 每帧），避免 App 全树 re-render 与 react-flow 重排卡顿；mouseup/mouseleave 才 commit state；
  // - ponytail: react-flow 对容器尺寸的 ResizeObserver 重排在所难免，若仍卡顿，升级路径是面板改绝对定位覆盖画布（不挤占 canvas 宽度）。
  const startPanelResize = useCallback((e: ReactMouseEvent<HTMLElement>) => {
    e.preventDefault();
    const el = panelRef.current;
    const startX = e.clientX;
    const startWidth = el?.offsetWidth ?? 440;
    const clamp = (w: number) => Math.min(680, Math.max(360, w));
    let lastWidth = startWidth;
    if (el) el.style.transition = 'none';
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    const stop = () => {
      const cur = panelRef.current;
      if (cur) cur.style.transition = '';
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
    if (!getJobToken()) {
      void bootstrapJobAuth().then((token) => {
        if (token) setAuthed(true);
      });
    }
  }, []);

  useEffect(() => {
    if (!authed) return;
    void refreshProjects();
  }, [authed, refreshProjects]);
  // token 过期 → 清 token 回登录页（authed 门控重新为 false）
  useEffect(() => {
    const check = () => { if (tokenExpired()) { clearToken(); setAuthed(false); } };
    check();
    const timer = window.setInterval(check, 30_000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    const rejectAuth = (event: Event) => {
      const rejectedToken = (event as CustomEvent<{ token?: string | null }>).detail?.token;
      if (rejectedToken && rejectedToken !== getJobToken()) return;
      setAuthMessage('登录已失效，请重新登录。');
      clearToken();
      setAuthed(false);
    };
    addEventListener('morrow:auth-rejected', rejectAuth);
    return () => removeEventListener('morrow:auth-rejected', rejectAuth);
  }, []);
  useEffect(() => {
    if (!authed || view !== 'canvas' || !canvas?.id) return;
    const reconcile = () => {
      void hydrateReferencedCanvasAssets(canvas.id).catch(() => undefined);
      void recoverGenerationJobs().catch(() => undefined);
      recoverGraphExecutions();
    };
    reconcile();
    const timer = window.setInterval(reconcile, 15_000);
    return () => window.clearInterval(timer);
  }, [authed, canvas?.id, view]);
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
      void refreshProjects();
      return canvasRef.current?.id === next.id ? canvasRef.current : next;
    } catch (error) {
      setSaveStatus('error');
      throw error;
    }
  }, [refreshProjects]);

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
    setSideView(embeddedWindow ? 'inspector' : 'agent');
    setSideOpen(embeddedWindow ? false : window.innerWidth > 1180);
    setView('canvas');
    window.history.replaceState(null, '', `#project=${encodeURIComponent(projectId)}&canvas=${encodeURIComponent(canvasId)}`);
  }, [clearAssets, embeddedMode, embeddedWindow, hydrateMetadata, loadProjectGraph]);

  useEffect(() => {
    if (!embeddedMode) return;
    const onGraphUpdated = (event: MessageEvent) => {
      if (event.origin !== embeddedMode.parentOrigin
          || !isCanvasGraphUpdatedMessage(event.data)) return;
      if (event.data.conversationId !== embeddedMode.conversationId
          || event.data.projectId !== embeddedMode.projectId) return;
      if (graphUpdateTimer.current !== null) window.clearTimeout(graphUpdateTimer.current);
      graphUpdateTimer.current = window.setTimeout(() => {
        graphUpdateTimer.current = null;
        void openCanvas(event.data.projectId, event.data.canvasId)
          .catch((error) => notify(error instanceof Error ? error.message : '画布更新加载失败'));
      }, 120);
    };
    window.addEventListener('message', onGraphUpdated);
    return () => {
      window.removeEventListener('message', onGraphUpdated);
      if (graphUpdateTimer.current !== null) window.clearTimeout(graphUpdateTimer.current);
    };
  }, [embeddedMode, notify, openCanvas]);

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
    const match = window.location.hash.match(/^#project=([^&]+)(?:&canvas=([^&]+))?$/);
    if (!match) return;
    const projectId = decodeURIComponent(match[1]);
    const canvasId = match[2] ? decodeURIComponent(match[2]) : undefined;
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
    await collectAssetGarbage();
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
    if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    setSaveStatus('saving');
    saveTimer.current = window.setTimeout(() => { void persistCurrentCanvas().catch(() => undefined); }, 550);
    return () => { if (saveTimer.current !== null) window.clearTimeout(saveTimer.current); };
  }, [persistCurrentCanvas, canvas?.id, canvas?.title, revision, view]);

  useEffect(() => {
    const saveWhenHidden = () => { if (document.visibilityState === 'hidden' && canvasRef.current) void persistCurrentCanvas().catch(() => undefined); };
    document.addEventListener('visibilitychange', saveWhenHidden);
    return () => document.removeEventListener('visibilitychange', saveWhenHidden);
  }, [persistCurrentCanvas]);

  const returnToProject = async () => {
    if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    try { await persistCurrentCanvas(); } catch { notify('返回前保存失败，画布仍保持打开'); return; }
    const state = useCanvasStore.getState();
    const protectedIds = [...state.past, ...state.future].flatMap((snapshot) => [...projectAssetIds(snapshot.nodes)]);
    await collectAssetGarbage(protectedIds);
    if (!project) return;
    setCanvas(null);
    setCanvases(await listCanvases(project.id));
    setView('projectHome');
    window.history.replaceState(null, '', `#project=${encodeURIComponent(project.id)}`);
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
    await collectAssetGarbage();
    await refreshProjects();
  }, [refreshProjects]);

  const removeCurrentProject = useCallback(async () => {
    const currentProject = project;
    if (!currentProject) return;
    if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    if (saveInFlight.current) await saveInFlight.current;
    await deleteProject(currentProject.id);
    await collectAssetGarbage();
    setView('home');
    setProject(null);
    setCanvas(null);
    clearAssets();
    window.history.replaceState(null, '', window.location.pathname);
    await refreshProjects();
    notify('项目已从本地删除');
  }, [clearAssets, notify, project, refreshProjects]);

  if (!authed) return <LoginScreen message={authMessage} onLogin={() => { setAuthMessage(null); setAuthed(true); }} />;

  if (view === 'home') {
    return <><HomePage projects={projects} loading={projectsLoading} onCreate={() => void newProject().catch((error) => notify(error.message))} onOpen={(id) => void openProjectHome(id).catch((error) => notify(error.message))} onDelete={(id) => void removeProject(id).catch((error) => notify(error.message))} onImport={(file) => void importPackage(file).catch((error) => notify(error instanceof Error ? error.message : '项目包导入失败'))} />{notice && <div className="canvas-status-toast app-status-toast" role="status">{notice}</div>}</>;
  }

  if (view === 'projectHome' && project) {
    return <><ProjectHome project={project} canvases={canvases} canvasModeLocked={!!embeddedMode} onBack={() => void returnHome()} onCreateCanvas={() => void createAndOpenCanvas(project.id).catch((error) => notify(error.message))} onOpenCanvas={(canvasId) => void openCanvas(project.id, canvasId).catch((error) => notify(error.message))} onRenameCanvas={(canvasId, title) => void renameCanvas(canvasId, title)} onDeleteCanvas={(canvasId) => void deleteCanvasById(canvasId).catch((error) => notify(error.message))} onRenameProject={(title) => void renameProject(title)} onDeleteProject={() => void removeCurrentProject().catch((error) => notify(error.message))} onExportProject={() => void exportPackage()} onNotify={notify} />{notice && <div className="canvas-status-toast app-status-toast" role="status">{notice}</div>}</>;
  }

  if (!project || !canvas) return null;

  return (
    <div className={`app-shell ${sideOpen ? '' : 'side-is-closed'}`}>
      <CanvasWorkspace
        projectId={project.id}
        projectTitle={project.title}
        canvasId={canvas.id}
        canvasTitle={canvas.title}
        canvases={canvases}
        projects={projects}
        saveStatus={saveStatus}
        canvasModeLocked={!!embeddedMode}
        hideAgentButton={embeddedWindow}
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
        onOpenAgent={() => { setSideView(embeddedWindow ? 'inspector' : 'agent'); setSideOpen(true); }}
        onOpenInspector={() => { setSideView('inspector'); setSideOpen(true); }}
        onExportProject={() => void exportPackage()}
        isExporting={isExporting}
        onNotify={notify}
      />
      {sideOpen && (
        <div
          className="panel-resize-handle"
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
          <button className={sideView === 'inspector' ? 'is-active' : ''} onClick={() => setSideView(sideView === 'inspector' ? 'agent' : 'inspector')} aria-label="画布设置"><SlidersHorizontal size={18} /></button>
          <strong className="workspace-side-title">{sideView === 'agent' ? '执行服务' : '节点属性'}</strong>
          <div className="chat-topbar-spacer" />
          <button onClick={() => setSideOpen(false)} aria-label="收起面板"><Maximize2 size={16} /></button>
        </header>
        <div className="chat-body">
          {!embeddedWindow && <div className="chat-view" hidden={sideView !== 'agent'}><MoonChatWindow /></div>}
          <div className="chat-view" hidden={sideView !== 'inspector'}><InspectorPanel /></div>
        </div>
      </aside>
      {!sideOpen && (
        <button
          className="reopen-chat"
          onClick={() => {
            if (embeddedWindow) setSideView('inspector');
            setSideOpen(true);
          }}
          aria-label={embeddedWindow ? '打开节点属性' : '打开执行服务'}
        >
          {embeddedWindow ? <SlidersHorizontal size={20} /> : <MessageCircle size={20} />}
        </button>
      )}
      {notice && <div className="canvas-status-toast app-status-toast" role="status">{notice}</div>}
    </div>
  );
}
