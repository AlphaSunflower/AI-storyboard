import { useEffect, useState, useCallback } from 'react';
import { AppHeader } from '../components/layout/AppHeader';
import { GenerationProgress } from '../components/common/GenerationProgress';
import { LeftSidebar } from '../components/editor/LeftSidebar';
import { SceneListPanel } from '../components/editor/SceneListPanel';
import { PreviewPanel } from '../components/editor/PreviewPanel';
import { DraftRecoverBanner } from '../components/common/DraftRecoverBanner';
import { GenerationCenterPanel } from '../components/agent/GenerationCenterPanel';
import { AssetDetailHost } from '../components/agent/AssetDetailHost';
import { GenerationRecordsModal } from '../components/agent/GenerationRecordsModal';
import { MobileEditorHeader } from '../components/editor/MobileEditorHeader';
import { MobileSceneList } from '../components/editor/MobileSceneList';
import { MobileScenePreview } from '../components/editor/MobileScenePreview';
import { ScriptInputDrawer } from '../components/editor/ScriptInputDrawer';
import { MobileBottomNav } from '../components/layout/MobileBottomNav';
import { MobileSidebarContent } from '../components/agent/MobileSidebarContent';
import AmbientGlow from '../components/AmbientGlow';
import { useIsMobile } from '../hooks/useIsMobile';
import { useProjectStore, getLastOpenedProjectId } from '../stores/projectStore';
import { useAuthStore } from '../stores/authStore';
import type { ProjectResponse } from '../api/projects';
import { EMBEDDED_APP_READY_MESSAGE } from '../lib/embeddedAppMessages';
import '../styles/editor.css';

export function EditorPage() {
  const { loadProjects, checkDraft, loadProject, fetchAiModels, createProject, selectScene } = useProjectStore();
  const scenes = useProjectStore((s) => s.scenes);
  const selectedSceneId = useProjectStore((s) => s.selectedSceneId);
  const isMobile = useIsMobile();
  const embedded = new URLSearchParams(window.location.search).get('embedded') === '1';
  const [showDraftBanner, setShowDraftBanner] = useState(false);
  const [draftProject, setDraftProject] = useState<ProjectResponse | null>(null);
  const [middleWidth, setMiddleWidth] = useState(() =>
    Math.min(400, Math.max(320, Math.round((typeof window !== 'undefined' ? window.innerWidth : 1440) * 0.3))),
  );
  // 手机端状态
  const [mobileView, setMobileView] = useState<'list' | 'preview'>('list');
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [scriptOpen, setScriptOpen] = useState(false);
  const [recordsOpen, setRecordsOpen] = useState(false);

  useEffect(() => {
    if (!embedded) return;
    const sendReady = () => {
      window.parent.postMessage(
        { type: EMBEDDED_APP_READY_MESSAGE, key: 'editor' },
        window.location.origin,
      );
    };
    const frame = window.requestAnimationFrame(() => window.setTimeout(sendReady, 0));
    const retry = window.setTimeout(sendReady, 300);
    return () => {
      window.cancelAnimationFrame(frame);
      window.clearTimeout(retry);
    };
  }, [embedded]);

  useEffect(() => {
    const init = async () => {
      await loadProjects();
      fetchAiModels();   // 拉取网关生图/生视频模型列表（失败静默保持默认）
      const params = new URLSearchParams(window.location.search);
      const requestedProjectId = params.get('projectId');
      const requestedSceneId = params.get('sceneId');
      // 默认进入最近修改的项目（projects 已按 updated_at DESC 排序，projects[0] 即最近）；
      // 无项目时自动创建「默认项目」并进入（保证始终有项目可加分镜/与智能体沟通）
      const { projects, currentProject, createProject } = useProjectStore.getState();
      const requestedProject = requestedProjectId
        ? projects.find((project) => project.id === requestedProjectId)
        : undefined;
      if (requestedProject) {
        await loadProject(requestedProject.id);
      } else if (currentProject) {
        // 路由返回 /editor 时强制拉取一次，避免复用聊天生成后已经过期的 Zustand 快照
        await loadProject(currentProject.id);
      } else {
        // 优先恢复「最近进入」的项目（刷新/重登），再到最近修改的（projects[0]）
        const lastId = getLastOpenedProjectId();
        const target = projects.find((p) => p.id === lastId) ?? projects[0];
        if (target) {
          await loadProject(target.id);
        } else {
          const p = await createProject('默认项目', 'movie', '16:9');
          await loadProject(p.id);
        }
      }
      if (requestedSceneId) {
        const scenes = useProjectStore.getState().scenes;
        if (scenes.some((scene) => scene.id === requestedSceneId)) {
          selectScene(requestedSceneId);
        }
      }
      checkDraft()
        .then((draft) => {
          if (draft && !requestedProjectId) {
            setDraftProject(draft);
            setShowDraftBanner(true);
          }
        })
        .catch(() => {
          // silently ignore draft check failures
        });
    };
    init();

    void useAuthStore.getState().checkAuth();
  }, [loadProjects, checkDraft, loadProject, createProject, fetchAiModels, selectScene]);

  // 模型目录可能在后台被改（admin 切默认模型 / 停用渠道）：回到前台或每 60s 静默刷新一次。
  // 网关目录版本号没变时 store 内部会直接跳过整个 setState，所以这里的轮询几乎无渲染开销。
  useEffect(() => {
    const refresh = () => { void fetchAiModels(); };
    const onVisible = () => {
      if (document.visibilityState === 'visible') refresh();
    };
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', onVisible);
    const timer = window.setInterval(refresh, 60_000);
    return () => {
      window.removeEventListener('focus', refresh);
      document.removeEventListener('visibilitychange', onVisible);
      window.clearInterval(timer);
    };
  }, [fetchAiModels]);

  // 打开项目后默认选中第一镜：右侧预览立即有内容，而不是一块空白等用户点。
  useEffect(() => {
    const sceneList = Array.isArray(scenes) ? scenes : [];
    if (sceneList.length === 0) return;
    if (selectedSceneId && sceneList.some((scene) => scene.id === selectedSceneId)) return;
    selectScene(sceneList[0].id);
  }, [scenes, selectedSceneId, selectScene]);

  const handleRecoverDraft = () => {
    if (draftProject) {
      loadProject(draftProject.id);
    }
    setShowDraftBanner(false);
  };

  const handleDismissDraft = () => {
    setShowDraftBanner(false);
  };

  const handleDragStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startWidth = middleWidth;

    const onMouseMove = (ev: MouseEvent) => {
      const newWidth = startWidth + ev.clientX - startX;
      setMiddleWidth(Math.min(560, Math.max(320, newWidth)));
    };

    const onMouseUp = () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
    };

    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
  }, [middleWidth]);

  const logout = useAuthStore((s) => s.logout);

  /* ═══════════════ 手机端布局（≤768px）═══════════════ */
  if (isMobile) {
    return (
      <div className="editor-shell" style={{ display: 'flex' }}>
        <AmbientGlow />
        <MobileEditorHeader
          showMenu={!embedded}
          showScript={mobileView === 'list'}
          onMenuClick={() => setSidebarOpen(true)}
          onScript={() => setScriptOpen(true)}
        />

        <div style={{ flex: 1, minHeight: 0, overflow: 'hidden' }}>
          {mobileView === 'list' ? (
            <MobileSceneList
              embedded={embedded}
              onSelectScene={(id) => { selectScene(id); setMobileView('preview'); }}
            />
          ) : (
            <MobileScenePreview onBack={() => setMobileView('list')} />
          )}
        </div>

        <MobileBottomNav embeddedEditor={embedded} fixed={false} />

        {/* 侧栏 overlay（复用 /chat） */}
        {sidebarOpen && (
          <div onClick={() => setSidebarOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 100, background: 'rgba(0,0,0,0.35)' }}>
            <div onClick={(e) => e.stopPropagation()} style={{
              position: 'absolute', top: 0, left: 0, bottom: 0,
              width: '80vw', maxWidth: 320, background: 'var(--color-surface-soft)',
              display: 'flex', flexDirection: 'column',
              boxShadow: '4px 0 24px rgba(0,0,0,0.12)',
              animation: 'mobileSlideIn 0.25s ease-out',
            }}>
              <MobileSidebarContent
                onSelectConversation={() => {}} // /editor 无会话操作
                onClose={() => setSidebarOpen(false)}
                onOpenSettings={() => setSidebarOpen(false)}
                logout={logout}
              />
            </div>
            <style>{`@keyframes mobileSlideIn { from { transform: translateX(-100%); } to { transform: translateX(0); } }`}</style>
          </div>
        )}

        {scriptOpen && <ScriptInputDrawer onClose={() => setScriptOpen(false)} />}
        <GenerationProgress />
      </div>
    );
  }

  /* ═══════════════ 桌面端布局（>768px）═══════════════ */
  return (
    <div className="editor-shell">
      {/* 页面氛围光晕（fixed 最底层，pointer-events none） */}
      <AmbientGlow />
      {/* Top bar */}
      <AppHeader />

      {/* Generation progress indicator */}
      <GenerationProgress />

      {/* Draft recovery banner */}
      {showDraftBanner && draftProject && (
        <DraftRecoverBanner
          projectName={draftProject.name}
          onRecover={handleRecoverDraft}
          onDismiss={handleDismissDraft}
        />
      )}

      {/* Three-panel layout */}
      <div className="editor-body">
        <LeftSidebar />
        <SceneListPanel width={middleWidth} />
        <div
          className="editor-resizer"
          role="separator"
          aria-orientation="vertical"
          aria-label="调整分镜列表宽度"
          onMouseDown={handleDragStart}
        />
        <PreviewPanel />
      </div>

      {/* 子窗口内不展示生成中心，避免与外部应用窗口重复。
          编辑器不挤压三栏，生成中心在这里是覆盖式抽屉，不参与内容让位。 */}
      {!embedded && (
        <GenerationCenterPanel
          onOpenRecords={() => setRecordsOpen(true)}
          recordsOpen={recordsOpen}
        />
      )}
      {!embedded && (
        <GenerationRecordsModal open={recordsOpen} onClose={() => setRecordsOpen(false)} />
      )}
      {/* 生成中心网格点开的素材详情弹窗（编辑器里没有聊天气泡，但网格同样要点开看）。 */}
      {!embedded && <AssetDetailHost />}
    </div>
  );
}
