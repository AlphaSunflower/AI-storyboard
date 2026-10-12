import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EditorPage } from './EditorPage';

vi.mock('../components/layout/AppHeader', () => ({ AppHeader: () => null }));
vi.mock('../components/common/GenerationProgress', () => ({ GenerationProgress: () => null }));
vi.mock('../components/editor/LeftSidebar', () => ({ LeftSidebar: () => null }));
vi.mock('../components/editor/SceneListPanel', () => ({ SceneListPanel: () => null }));
vi.mock('../components/editor/PreviewPanel', () => ({ PreviewPanel: () => null }));
vi.mock('../components/common/DraftRecoverBanner', () => ({ DraftRecoverBanner: () => null }));
vi.mock('../components/editor/MobileEditorHeader', () => ({ MobileEditorHeader: () => null }));
vi.mock('../components/editor/MobileSceneList', () => ({ MobileSceneList: () => null }));
vi.mock('../components/editor/MobileScenePreview', () => ({ MobileScenePreview: () => null }));
vi.mock('../components/editor/ScriptInputDrawer', () => ({ ScriptInputDrawer: () => null }));
vi.mock('../components/layout/MobileBottomNav', () => ({ MobileBottomNav: () => null }));
vi.mock('../components/agent/MobileSidebarContent', () => ({ MobileSidebarContent: () => null }));
vi.mock('../components/asset/AssetLibraryPanel', () => ({ AssetLibraryPanel: () => null }));
vi.mock('../components/AmbientGlow', () => ({ default: () => null }));
vi.mock('../hooks/useIsMobile', () => ({ useIsMobile: () => false }));

vi.mock('../components/agent/GenerationCenterPanel', () => ({
  GenerationCenterPanel: ({ onOpenRecords }: { onOpenRecords?: () => void }) => (
    <button type="button" onClick={onOpenRecords}>打开生成记录</button>
  ),
}));

vi.mock('../components/agent/GenerationRecordsModal', () => ({
  GenerationRecordsModal: ({ open }: { open: boolean }) => (
    open ? <div>生成记录弹窗</div> : null
  ),
}));

vi.mock('../stores/projectStore', () => ({
  getLastOpenedProjectId: () => null,
  useProjectStore: Object.assign(
    () => ({
      loadProjects: vi.fn().mockResolvedValue(undefined),
      checkDraft: vi.fn().mockResolvedValue(null),
      loadProject: vi.fn().mockResolvedValue(undefined),
      fetchAiModels: vi.fn(),
      createProject: vi.fn().mockResolvedValue({ id: 'p1' }),
      selectScene: vi.fn(),
    }),
    {
      getState: () => ({
        projects: [],
        currentProject: null,
        createProject: vi.fn().mockResolvedValue({ id: 'p1' }),
      }),
    },
  ),
}));

vi.mock('../stores/authStore', () => ({
  useAuthStore: Object.assign(
    () => ({ logout: vi.fn() }),
    { getState: () => ({ checkAuth: vi.fn().mockResolvedValue(false) }) },
  ),
}));

describe('EditorPage', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('opens generation records from the generation center', () => {
    render(
      <MemoryRouter>
        <EditorPage />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '打开生成记录' }));

    expect(screen.getByText('生成记录弹窗')).toBeTruthy();
  });
});
