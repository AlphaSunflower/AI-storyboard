import { useEffect, useState, useCallback } from 'react';
import { AppHeader } from '../components/layout/AppHeader';
import { GenerationProgress } from '../components/common/GenerationProgress';
import { LeftSidebar } from '../components/editor/LeftSidebar';
import { SceneListPanel } from '../components/editor/SceneListPanel';
import { PreviewPanel } from '../components/editor/PreviewPanel';
import { DraftRecoverBanner } from '../components/common/DraftRecoverBanner';
import { ToastContainer } from '../components/common/Toast';
import { AgentFab } from '../components/agent/AgentFab';
import { AgentDrawer } from '../components/agent/AgentDrawer';
import { TaskFab } from '../components/common/TaskFab';
import { MobileEditorHeader } from '../components/editor/MobileEditorHeader';
import { MobileSceneList } from '../components/editor/MobileSceneList';
import { MobileScenePreview } from '../components/editor/MobileScenePreview';
import { ScriptInputDrawer } from '../components/editor/ScriptInputDrawer';
import { MobileBottomNav } from '../components/layout/MobileBottomNav';
import { MobileSidebarContent } from '../components/agent/MobileSidebarContent';
import { AssetLibraryPanel } from '../components/asset/AssetLibraryPanel';
import AmbientGlow from '../components/AmbientGlow';
import { useIsMobile } from '../hooks/useIsMobile';
import { useProjectStore } from '../stores/projectStore';
import { useAuthStore } from '../stores/authStore';
import type { ProjectResponse } from '../api/projects';

export function EditorPage() {
  const { loadProjects, checkDraft, loadProject, fetchAiModels, createProject, selectScene } = useProjectStore();
  const isMobile = useIsMobile();
  const [showDraftBanner, setShowDraftBanner] = useState(false);
  const [draftProject, setDraftProject] = useState<ProjectResponse | null>(null);
  const [middleWidth, setMiddleWidth] = useState(380);
  // 手机端状态
  const [mobileView, setMobileView] = useState<'list' | 'preview'>('list');
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [scriptOpen, setScriptOpen] = useState(false);
  const [assetsOpen, setAssetsOpen] = useState(false);

  useEffect(() => {
    const init = async () => {
      await loadProjects();
      fetchAiModels();   // 拉取网关生图/生视频模型列表（失败静默保持默认）
      // 默认进入最近修改的项目（projects 已按 updated_at DESC 排序，projects[0] 即最近）；
      // 无项目时自动创建「默认项目」并进入（保证始终有项目可加分镜/与智能体沟通）
      const { projects, currentProject, createProject } = useProjectStore.getState();
      if (!currentProject) {
        if (projects.length > 0) {
          await loadProject(projects[0].id);
        } else {
          const p = await createProject('默认项目', 'movie', '16:9');
          await loadProject(p.id);
        }
      }
      checkDraft()
        .then((draft) => {
          if (draft) {
            setDraftProject(draft);
            setShowDraftBanner(true);
          }
        })
        .catch(() => {
          // silently ignore draft check failures
        });
    };
    init();

    // URL token auto-login: supports cross-system JWT exchange
    const params = new URLSearchParams(window.location.search);
    const token = params.get('token');
    const refresh = params.get('refresh');
    const userId = params.get('userId');
    const name = params.get('name');
    if (token) {
      localStorage.setItem('accessToken', token);
      if (refresh) localStorage.setItem('refreshToken', refresh);
      if (userId && name) localStorage.setItem('user', JSON.stringify({ userId, displayName: name }));
      window.history.replaceState({}, '', '/editor');
      useAuthStore.getState().checkAuth();
    }
  }, [loadProjects, checkDraft, loadProject, createProject]);

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
      setMiddleWidth(Math.min(600, Math.max(380, newWidth)));
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
      <div className="page-in" style={{ height: '100vh', display: 'flex', flexDirection: 'column', background: 'white', overflow: 'hidden', animation: 'page-in 0.4s cubic-bezier(0.16, 1, 0.3, 1)' }}>
        <AmbientGlow />
        <MobileEditorHeader onMenuClick={() => setSidebarOpen(true)} onScript={() => setScriptOpen(true)} />

        <div style={{ flex: 1, minHeight: 0, overflow: 'hidden' }}>
          {mobileView === 'list' ? (
            <MobileSceneList onSelectScene={(id) => { selectScene(id); setMobileView('preview'); }} />
          ) : (
            <MobileScenePreview onBack={() => setMobileView('list')} />
          )}
        </div>

        <MobileBottomNav onOpenAssets={() => setAssetsOpen(true)} />

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
                onOpenAssets={() => { setAssetsOpen(true); setSidebarOpen(false); }}
                onNavigate={(path) => { window.location.href = path; setSidebarOpen(false); }}
                logout={logout}
              />
            </div>
            <style>{`@keyframes mobileSlideIn { from { transform: translateX(-100%); } to { transform: translateX(0); } }`}</style>
          </div>
        )}

        {scriptOpen && <ScriptInputDrawer onClose={() => setScriptOpen(false)} />}
        {assetsOpen && <AssetLibraryPanel onClose={() => setAssetsOpen(false)} />}
        <ToastContainer />
        <GenerationProgress />
      </div>
    );
  }

  /* ═══════════════ 桌面端布局（>768px）═══════════════ */
  return (
    <div
      className="page-in"
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100vh',
        overflow: 'hidden',
        animation: 'page-in 0.5s cubic-bezier(0.16, 1, 0.3, 1)',
      }}
    >
      {/* 页面氛围光晕（fixed 最底层，pointer-events none） */}
      <AmbientGlow />
      {/* Top bar */}
      <AppHeader />

      {/* Toast notifications */}
      <ToastContainer />

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
      <div
        style={{
          display: 'flex',
          flex: 1,
          overflow: 'hidden',
        }}
      >
        <LeftSidebar />
        <SceneListPanel width={middleWidth} />
        <div
          onMouseDown={handleDragStart}
          style={{
            width: '4px',
            cursor: 'col-resize',
            background: 'transparent',
            transition: 'background 0.15s',
            flexShrink: 0,
          }}
          onMouseEnter={(e) => { (e.target as HTMLElement).style.background = 'var(--color-primary)'; }}
          onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
        />
        <PreviewPanel />
      </div>

      {/* 智能体窗口 */}
      <AgentFab />
      <AgentDrawer />
      {/* 任务中心悬浮球（右下角 ☾ 上方，聚合进行中的生成任务） */}
      <TaskFab />
    </div>
  );
}
