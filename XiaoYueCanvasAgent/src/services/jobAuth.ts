// 从 /chat 跳转进入时的 JWT 注入：解析 ?token=&userId=&name=，存 localStorage，清理 URL。
// jobAdapter 通过 getJobToken() 取 token，非 SSE 端点发 Authorization: Bearer，SSE 订阅发 ?access_token=。
const TOKEN_KEY = 'morrow_job_token';
const USER_KEY = 'morrow_job_user';

export function captureUrlAuth(): void {
  const params = new URLSearchParams(window.location.search);
  const token = params.get('token');
  if (!token) return;
  localStorage.setItem(TOKEN_KEY, token);
  const userId = params.get('userId');
  if (userId) localStorage.setItem(USER_KEY, userId);
  // 清理地址栏（避免 token 残留/被分享）
  params.delete('token');
  params.delete('userId');
  params.delete('name');
  const qs = params.toString();
  window.history.replaceState({}, '', window.location.pathname + (qs ? '?' + qs : ''));
}

export function getJobToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

// 画布登录成功后写入 token（供 jobAdapter 携带）
export function setJobToken(token: string, userId?: string): void {
  localStorage.setItem(TOKEN_KEY, token);
  if (userId) localStorage.setItem(USER_KEY, userId);
}

export function getJobUserId(): string | null {
  return localStorage.getItem(USER_KEY);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

// JWT payload 的 exp 是否已过期（无 token 视为过期；非 JWT 返回值交 401 处理）
export function tokenExpired(): boolean {
  const token = getJobToken();
  if (!token) return true;
  const parts = token.split('.');
  if (parts.length < 2) return false;
  try {
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = b64.padEnd(b64.length + ((4 - (b64.length % 4)) % 4), '=');
    const payload = JSON.parse(atob(padded));
    if (!payload || typeof payload.exp !== 'number') return false;
    return payload.exp * 1000 < Date.now();
  } catch {
    return false;
  }
}
