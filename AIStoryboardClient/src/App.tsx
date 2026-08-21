import { Routes, Route } from 'react-router-dom';
import { LoginPage } from './pages/LoginPage';
import { EditorPage } from './pages/EditorPage';
import { DocsPage } from './pages/DocsPage';
import { ChatPage } from './pages/ChatPage';
import NotFoundBrickBreaker from './components/ui/8bit/blocks/not-found-brick-breaker';

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/docs" element={<DocsPage />} />
      <Route path="/chat" element={<ChatPage />} />
      <Route path="/editor" element={<EditorPage />} />
      <Route path="*" element={<NotFoundBrickBreaker className="min-h-svh" href="/chat" />} />
    </Routes>
  );
}

export default App;
