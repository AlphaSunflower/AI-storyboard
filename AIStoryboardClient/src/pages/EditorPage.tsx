import { useEffect, useState } from 'react';
import { AppHeader } from '../components/layout/AppHeader';
import { GenerationProgress } from '../components/common/GenerationProgress';
import { ScriptInputPanel } from '../components/editor/ScriptInputPanel';
import { SceneListPanel } from '../components/editor/SceneListPanel';
import { PreviewPanel } from '../components/editor/PreviewPanel';
import { DraftRecoverBanner } from '../components/common/DraftRecoverBanner';
import { useProjectStore } from '../stores/projectStore';
import type { ProjectResponse } from '../api/projects';

export function EditorPage() {
  const { loadProjects, checkDraft, loadProject } = useProjectStore();
  const [showDraftBanner, setShowDraftBanner] = useState(false);
  const [draftProject, setDraftProject] = useState<ProjectResponse | null>(null);

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
        <ScriptInputPanel />
        <SceneListPanel />
        <PreviewPanel />
      </div>
    </div>
  );
}
