import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { useLibraryTaskStore } from './stores/libraryTaskStore';

vi.mock('./pages/LoginPage', () => ({ LoginPage: () => <div>登录</div> }));
vi.mock('./pages/EditorPage', () => ({ EditorPage: () => <div>编辑器</div> }));
vi.mock('./pages/DocsPage', () => ({ DocsPage: () => <div>文档</div> }));
vi.mock('./pages/ChatPage', () => ({ ChatPage: () => <div>聊天</div> }));
vi.mock('./pages/StoryboardLibraryPage', () => ({ StoryboardLibraryPage: () => <div>分镜库</div> }));
vi.mock('./pages/LibraryWorkspacePage', () => ({ LibraryWorkspacePage: () => <div>项目库</div> }));
vi.mock('./components/ui/8bit/blocks/not-found-brick-breaker', () => ({
  default: () => <div>未找到</div>,
}));

describe('App task queue', () => {
  beforeEach(() => {
    useLibraryTaskStore.setState({
      panelOpen: true,
      tasks: [{
        id: 'upload:1',
        kind: 'upload',
        label: '角色参考.png',
        status: 'uploading',
        progress: 40,
      }],
    });
  });

  afterEach(() => {
    cleanup();
    useLibraryTaskStore.setState({ panelOpen: false, tasks: [] });
  });

  it('shows background library work outside the library route', () => {
    render(
      <MemoryRouter initialEntries={['/login']}>
        <App />
      </MemoryRouter>,
    );

    expect(screen.getByLabelText('素材任务队列')).toBeTruthy();
  });
});
