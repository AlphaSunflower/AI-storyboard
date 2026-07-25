import { useEffect, useState, useCallback } from 'react';
import { AppHeader } from '../components/layout/AppHeader';
import { GenerationProgress } from '../components/common/GenerationProgress';
import { LeftSidebar } from '../components/editor/LeftSidebar';
import { SceneListPanel } from '../components/editor/SceneListPanel';
import { PreviewPanel } from '../components/editor/PreviewPanel';
import { DraftRecoverBanner } from '../components/common/DraftRecoverBanner';
import { useProjectStore } from '../stores/projectStore';
import type { ProjectResponse } from '../api/projects';

export function EditorPage() {
  const { loadProjects, checkDraft, loadProject } = useProjectStore();
  const [showDraftBanner, setShowDraftBanner] = useState(false);
  const [draftProject, setDraftProject] = useState<ProjectResponse | null>(null);
  const [middleWidth, setMiddleWidth] = useState(380);

  useEffect(() => {
    loadProjects();
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
  }, [loadProjects, checkDraft]);

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

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100vh',
        overflow: 'hidden',
      }}
    >
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
    </div>
  );
}
