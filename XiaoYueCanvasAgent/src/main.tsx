import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@xyflow/react/dist/style.css';
import './index.css';
import './styles.css';
import App from './App';
import { captureUrlAuth } from './services/jobAuth';

// 浏览器按 Origin 隔离 localStorage 与 IndexedDB；开发环境统一使用 localhost，
// 避免 localhost 与 127.0.0.1 互相看不到登录 token 和本地素材。
const redirectToCanonicalDevOrigin = () => {
  if (!import.meta.env.DEV || window.location.hostname !== '127.0.0.1') return false;
  const url = new URL(window.location.href);
  url.hostname = 'localhost';
  window.location.replace(url.toString());
  return true;
};

if (!redirectToCanonicalDevOrigin()) {
  captureUrlAuth();
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}
