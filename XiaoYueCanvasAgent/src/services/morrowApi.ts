import { bootstrapJobAuth, clearToken, getJobToken } from './jobAuth';

// Morrow 项目/画布实体走后端画布服务。dev 靠 vite proxy，生产靠网关同域。
const baseUrl = '/api/canvas';

export class ApiRequestError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly path: string,
    readonly code?: number | string,
  ) {
    super(message);
    this.name = 'ApiRequestError';
  }
}

/** 带 JWT 的后端 API 调用（非 SSE）。 */
export async function apiFetch(path: string, init: RequestInit = {}, retry = true): Promise<Response> {
  const headers = new Headers(init.headers ?? {});
  if (init.body !== undefined && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const requestToken = getJobToken() ?? await bootstrapJobAuth();
  if (requestToken) headers.set('Authorization', `Bearer ${requestToken}`);
  const response = await fetch(`${baseUrl}${path}`, { ...init, headers });
  if (response.status === 401 && retry) {
    const currentToken = getJobToken();
    if (currentToken && currentToken !== requestToken) {
      return apiFetch(path, init, false);
    }
    clearToken();
    const refreshed = await bootstrapJobAuth();
    if (refreshed) return apiFetch(path, init, false);
  }
  return response;
}

/** 解析后端 JSON 负载（{code,message,data} 约定 → 直接返回 data；非约定则原样）。 */
export async function apiJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const requestToken = getJobToken();
  const response = await apiFetch(path, init);
  if (!response.ok) {
    // 只有 401 代表凭证失效；403 是业务权限拒绝，不应清 token 并退出登录。
    if (response.status === 401) {
      window.dispatchEvent(new CustomEvent('morrow:auth-rejected', {
        detail: { status: response.status, path, token: requestToken },
      }));
    } else if (response.status === 403) {
      window.dispatchEvent(new CustomEvent('morrow:permission-denied', {
        detail: { status: response.status, path },
      }));
    }
    let message = `HTTP ${response.status}`;
    let code: number | string | undefined;
    try {
      const body = await response.json();
      if (body && body.message) message = body.message;
      else if (body && body.code && body.code !== 0) message = `${body.code}: ${body.message ?? ''}`;
      if (body && (typeof body.code === 'number' || typeof body.code === 'string')) code = body.code;
    } catch { /* 非 JSON 负载 */ }
    throw new ApiRequestError(message, response.status, path, code);
  }
  return response.json() as Promise<T>;
}

export { baseUrl };
