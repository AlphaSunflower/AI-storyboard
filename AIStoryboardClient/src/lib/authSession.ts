import { BACKEND_URL } from '../config';

interface AuthPayload {
  accessToken?: string;
  userId?: string;
  displayName?: string;
}

interface AuthChannelMessage {
  type: 'token-request' | 'token-response' | 'token' | 'logout';
  requestId?: string;
  token?: string | null;
}

const AUTH_CHANNEL_NAME = 'moon-auth-session';
const PEER_TOKEN_TIMEOUT_MS = 180;

let accessToken: string | null = null;
let refreshPromise: Promise<string | null> | null = null;
let authChannel: BroadcastChannel | null = null;
let peerRequestSeq = 0;
const peerRequestResolvers = new Map<string, (token: string | null) => void>();

/**
 * 同源标签页之间只共享 Access Token，不落 localStorage。
 *
 * 新标签页没有内存里的 token，但浏览器可能因为第三方 Cookie 策略、Secure Cookie
 * 或跨站部署拿不到 refresh_token。此时先向已经登录的标签页要一份内存 token，
 * 拿到就直接进入 /chat；没有其他标签页时才回退到 /api/auth/refresh。
 */
function ensureAuthChannel(): BroadcastChannel | null {
  if (authChannel) return authChannel;
  if (typeof window === 'undefined' || typeof window.BroadcastChannel !== 'function') {
    return null;
  }
  const channel = new window.BroadcastChannel(AUTH_CHANNEL_NAME);
  channel.onmessage = (event: MessageEvent<AuthChannelMessage>) => {
    const message = event.data;
    if (!message) return;
    if (message.type === 'token-request' && message.requestId) {
      if (accessToken) {
        channel.postMessage({
          type: 'token-response',
          requestId: message.requestId,
          token: accessToken,
        } satisfies AuthChannelMessage);
      }
      return;
    }
    if (message.type === 'token-response' && message.requestId) {
      const resolve = peerRequestResolvers.get(message.requestId);
      if (resolve) {
        peerRequestResolvers.delete(message.requestId);
        resolve(message.token ?? null);
      }
      return;
    }
    if (message.type === 'token') {
      accessToken = message.token ?? null;
      return;
    }
    if (message.type === 'logout') {
      accessToken = null;
      localStorage.removeItem('user');
      localStorage.removeItem('accessToken');
      if (window.location.pathname !== '/login') window.location.assign('/login');
    }
  };
  authChannel = channel;
  return channel;
}

function broadcastToken(token: string | null): void {
  ensureAuthChannel()?.postMessage({ type: 'token', token } satisfies AuthChannelMessage);
}

function requestTokenFromPeer(): Promise<string | null> {
  const channel = ensureAuthChannel();
  if (!channel) return Promise.resolve(null);
  const requestId = `peer-${Date.now()}-${peerRequestSeq += 1}`;
  return new Promise((resolve) => {
    const timer = window.setTimeout(() => {
      peerRequestResolvers.delete(requestId);
      resolve(null);
    }, PEER_TOKEN_TIMEOUT_MS);
    peerRequestResolvers.set(requestId, (token) => {
      window.clearTimeout(timer);
      resolve(token);
    });
    channel.postMessage({ type: 'token-request', requestId } satisfies AuthChannelMessage);
  });
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
  localStorage.removeItem('accessToken');
  if (token) {
    broadcastToken(token);
  } else {
    // 退出登录或测试清理：关闭通道，避免残留监听。
    authChannel?.close();
    authChannel = null;
    peerRequestResolvers.forEach((resolve) => resolve(null));
    peerRequestResolvers.clear();
  }
}

export function broadcastLogout(): void {
  const channel = ensureAuthChannel();
  channel?.postMessage({ type: 'logout' } satisfies AuthChannelMessage);
  accessToken = null;
  localStorage.removeItem('user');
  localStorage.removeItem('accessToken');
  // 先让消息入队，再关闭通道；立即 close 会丢掉同一次任务里的 postMessage。
  window.setTimeout(() => {
    channel?.close();
    if (authChannel === channel) authChannel = null;
  }, 0);
}

export function persistUser(user: { userId: string; displayName: string }): void {
  localStorage.setItem('user', JSON.stringify(user));
}

export function clearAuthSession(): void {
  accessToken = null;
  localStorage.removeItem('user');
  localStorage.removeItem('accessToken');
}

/**
 * 刷新 Access Token。
 *
 * @param force 401 重试时必须强制刷新；普通启动/首次请求可以先复用内存 token。
 */
export async function refreshAccessToken(force = false): Promise<string | null> {
  if (!force && accessToken) return accessToken;
  if (refreshPromise) return refreshPromise;
  refreshPromise = (async () => {
    const peerToken = await requestTokenFromPeer();
    if (peerToken) {
      accessToken = peerToken;
      return peerToken;
    }
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
      if (token) broadcastToken(token);
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
  const token = getAccessToken() ?? await refreshAccessToken();
  const headers = new Headers(init.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
  let response = await fetch(input, { ...init, headers });
  if (response.status === 401 && await refreshAccessToken(true)) {
    headers.set('Authorization', `Bearer ${getAccessToken()}`);
    response = await fetch(input, { ...init, headers });
  }
  return response;
}
