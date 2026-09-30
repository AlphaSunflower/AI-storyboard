import { BACKEND_URL } from '../config';

interface AuthPayload {
  accessToken?: string;
  userId?: string;
  displayName?: string;
}

let accessToken: string | null = null;
let refreshPromise: Promise<string | null> | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
  localStorage.removeItem('accessToken');
}

export function persistUser(user: { userId: string; displayName: string }): void {
  localStorage.setItem('user', JSON.stringify(user));
}

export function clearAuthSession(): void {
  accessToken = null;
  localStorage.removeItem('user');
  localStorage.removeItem('accessToken');
}

export async function refreshAccessToken(): Promise<string | null> {
  if (refreshPromise) return refreshPromise;
  refreshPromise = (async () => {
    try {
      const response = await fetch(`${BACKEND_URL}/api/auth/refresh`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      });
      if (!response.ok) {
        clearAuthSession();
        return null;
      }
      const body = await response.json() as { data?: AuthPayload };
      const token = body.data?.accessToken ?? null;
      accessToken = token;
      if (body.data?.userId && body.data.displayName) {
        persistUser({ userId: body.data.userId, displayName: body.data.displayName });
      }
      return token;
    } catch {
      clearAuthSession();
      return null;
    } finally {
      refreshPromise = null;
    }
  })();
  return refreshPromise;
}

export async function authorizedFetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
  // 旧版本曾把 Access Token 落在 localStorage；任何请求都顺手清除，避免残留复活。
  localStorage.removeItem('accessToken');
  const token = getAccessToken();
  const headers = new Headers(init.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
  let response = await fetch(input, { ...init, headers });
  if (response.status === 401 && await refreshAccessToken()) {
    headers.set('Authorization', `Bearer ${getAccessToken()}`);
    response = await fetch(input, { ...init, headers });
  }
  return response;
}
