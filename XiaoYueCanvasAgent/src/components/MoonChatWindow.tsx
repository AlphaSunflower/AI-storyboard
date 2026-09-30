import { getJobToken } from '../services/jobAuth';
import { useCanvasStore } from '../store/canvasStore';

/**
 * 画布「执行服务」面板内嵌 Moon 对话小窗：iframe 复用 AIStoryboardClient 现成的 /chat 页。
 * 生产同源部署下由 HttpOnly refresh cookie 恢复内存 Access Token，不通过 URL 传凭证。
 */
export function MoonChatWindow() {
  const token = getJobToken();
  const projectId = useCanvasStore((state) => state.projectId);
  const canvasId = useCanvasStore((state) => state.canvasId);
  if (!token) {
    return <div className="moon-chat-frame">加载 Moon 智能体…（请先登录）</div>;
  }
  const base = (import.meta.env.VITE_MOON_CHAT_URL as string | undefined)?.trim().replace(/\/$/, '') || 'http://localhost:5173';
  const params = new URLSearchParams({ embed: '1' });
  if (projectId && canvasId) {
    params.set('surface', 'canvas-edit');
    params.set('projectId', projectId);
    params.set('canvasId', canvasId);
  }

  return (
    <iframe
      src={`${base}/chat?${params.toString()}`}
      title="Moon 智能体"
      className="moon-chat-frame"
      allow="clipboard-write; microphone"
    />
  );
}
