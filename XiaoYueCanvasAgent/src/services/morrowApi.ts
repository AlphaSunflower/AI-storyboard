import { getJobToken } from './jobAuth';

// Morrow 项目/画布实体走后端画布服务。dev 靠 vite proxy，生产靠网关同域。
const baseUrl = '/api/canvas';

/** 带 JWT 的后端 API 调用（非 SSE）。 */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers ?? {});
  if (init.body !== undefined && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const token = getJobToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  return fetch(`${baseUrl}${path}`, { ...init, headers });
}

/** 解析后端 JSON 负载（{code,message,data} 约定 → 直接返回 data；非约定则原样）。 */
export async function apiJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await apiFetch(path, init);
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) {
      window.dispatchEvent(new CustomEvent('morrow:auth-rejected', {
        detail: { status: response.status, path },
      }));
    }
    let message = `HTTP ${response.status}`;
    try {
      const body = await response.json();
      if (body && body.message) message = body.message;
      else if (body && body.code && body.code !== 0) message = `${body.code}: ${body.message ?? ''}`;
    } catch { /* 非 JSON 负载 */ }
    throw new Error(message);
  }
  return response.json() as Promise<T>;
}

export { baseUrl };
