import { getJobToken, getJobUserId } from '../services/jobAuth';

/**
 * 画布「执行服务」面板内嵌 Moon 对话小窗：iframe 复用 AIStoryboardClient 现成的 /chat 页。
 * 画布与 Moon 前端共享 JWT secret + 同一 users 表——画布 token 即 Moon accessToken，可直接作为 iframe 的 ?token=；
 * 跨端口 iframe 经 ?token= 注入让 /chat 自动登录，?embed=1 走桌面紧凑模式（隐藏 rail/侧栏）。
 * 仅在已登录画布中渲染（chat-view 只在 authed 下进入），故 getJobToken() 可直接读取。
 */
export function MoonChatWindow() {
  const token = getJobToken();
  const userId = getJobUserId();
  if (!token) {
    return <div className="moon-chat-frame">加载 Moon 智能体…（请先登录）</div>;
  }
  const base = (import.meta.env.VITE_MOON_CHAT_URL as string | undefined)?.trim().replace(/\/$/, '') || 'http://localhost:5173';
  const params = new URLSearchParams({ embed: '1' });
  params.set('token', token);
  if (userId) params.set('userId', userId);
  // 尝试从 JWT payload 取展示名（可选；缺省由 /chat 侧从 token 兜底）
  let name = '';
  try {
    const b64 = token.split('.')[1]?.replace(/-/g, '+').replace(/_/g, '/');
    if (b64) {
      const padded = b64.padEnd(b64.length + ((4 - (b64.length % 4)) % 4), '=');
      const payload = JSON.parse(atob(padded));
      name = payload?.displayName ?? payload?.name ?? payload?.nickname ?? '';
    }
  } catch { /* @ponytail: JWT 解析失败则省略 name，/chat 侧从 token 兜底 */ }
  if (name) params.set('name', name);

  return (
    <iframe
      src={`${base}/chat?${params.toString()}`}
      title="Moon 智能体"
      className="moon-chat-frame"
      allow="clipboard-write; microphone"
    />
  );
}
