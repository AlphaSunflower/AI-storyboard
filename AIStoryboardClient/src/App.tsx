import { Routes, Route, Navigate } from 'react-router-dom';
import { LoginPage } from './pages/LoginPage';
import { EditorPage } from './pages/EditorPage';
import { DocsPage } from './pages/DocsPage';
import { ChatPage } from './pages/ChatPage';

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/docs" element={<DocsPage />} />
      <Route path="/chat" element={<ChatPage />} />
      <Route path="/editor" element={<EditorPage />} />
      <Route path="*" element={<Navigate to="/editor" replace />} />
    </Routes>
  );
}

export default App;
