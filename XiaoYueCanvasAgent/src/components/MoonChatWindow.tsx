import { getJobToken } from '../services/jobAuth';
import { useCanvasStore } from '../store/canvasStore';

/**
 * 画布「执行服务」面板内嵌 Moon 对话小窗：iframe 复用 AIStoryboardClient 现成的 /chat 页。
 * 生产同源部署下由 HttpOnly refresh cookie 恢复内存 Access Token，不通过 URL 传凭证。
 * media：聊天页「去画布编辑」带过来的媒体参数，原样透传，画布编辑会话据此挂载这张素材。
 */
export function MoonChatWindow({ media = '' }: { media?: string } = {}) {
  const token = getJobToken();
  const projectId = useCanvasStore((state) => state.projectId);
  const canvasId = useCanvasStore((state) => state.canvasId);
  if (!token) {
    return <div className="moon-chat-frame">加载 Moon 智能体…（请先登录）</div>;
  }
  // 未显式配置 VITE_MOON_CHAT_URL 时：开发环境指向本地 Vite(5173)，
  // 生产环境走同源相对路径（画布子站与主站由同一个 nginx 托管，见 deploy/stack/nginx/default.conf）。
  const configuredBase = (import.meta.env.VITE_MOON_CHAT_URL as string | undefined)?.trim();
  const base = configuredBase
    ? configuredBase.replace(/\/$/, '')
    : (import.meta.env.DEV ? 'http://localhost:5173' : '');
  const params = new URLSearchParams({ embed: '1' });
  if (projectId && canvasId) {
    params.set('surface', 'canvas-edit');
    params.set('projectId', projectId);
    params.set('canvasId', canvasId);
    // 子站恒定画布模式：默认开启且不提供退出入口
    params.set('canvasLock', '1');
  }
  if (media) params.set('media', media);

  return (
    <iframe
      src={`${base}/chat?${params.toString()}`}
      title="Moon 智能体"
      className="moon-chat-frame"
      allow="clipboard-write; microphone"
    />
  );
}
