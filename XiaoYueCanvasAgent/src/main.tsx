import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@xyflow/react/dist/style.css';
import './index.css';
import './styles.css';
// 玻璃态表层：必须排在 styles.css 之后（同优先级下后加载者生效），
// 且只覆盖画布组件，不触碰 Moon 智能体面板。
import './glass.css';
import App from './App';
import { installRailDebug } from './railDebug';

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
  installRailDebug();
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}
