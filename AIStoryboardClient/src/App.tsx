import { Routes, Route, Navigate } from 'react-router-dom';
import { LoginPage } from './pages/LoginPage';
import { EditorPage } from './pages/EditorPage';
import { DocsPage } from './pages/DocsPage';
import { ChatPage } from './pages/ChatPage';
import { StoryboardLibraryPage } from './pages/StoryboardLibraryPage';
import NotFoundBrickBreaker from './components/ui/8bit/blocks/not-found-brick-breaker';
import { LibraryWorkspacePage } from './pages/LibraryWorkspacePage';
import { LibraryTaskQueue } from './components/library/LibraryTaskQueue';
import { ToastHost } from './components/common/ToastHost';

function App() {
  return (
    <>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<Navigate to="/chat" replace />} />
        <Route path="/docs" element={<DocsPage />} />
        <Route path="/chat" element={<ChatPage />} />
        <Route path="/chat/storyboards" element={<StoryboardLibraryPage />} />
        <Route path="/library" element={<LibraryWorkspacePage />} />
        <Route path="/editor" element={<EditorPage />} />
        <Route path="*" element={<NotFoundBrickBreaker className="min-h-svh" href="/chat" />} />
      </Routes>
      <LibraryTaskQueue />
      <ToastHost />
    </>
  );
}

export default App;
