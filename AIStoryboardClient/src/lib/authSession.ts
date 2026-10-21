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
  // 显式登录/清理视为新的会话边界：清掉刷新失败的冷却，避免拦掉正常的首次刷新
  refreshFailedAt = 0;
  redirectingToLogin = false;
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
  refreshFailedAt = 0;
  localStorage.removeItem('user');
  localStorage.removeItem('accessToken');
}

let redirectingToLogin = false;

/**
 * 401 且刷新失败时的唯一出口：清掉本地会话并强制回登录页。
 *
 * <p>axios 链路一直有这道兜底，但 `authorizedFetch`（SSE、上传、轮询等）此前只把 401
 * 原样返回，调用方各自弹「未授权」，页面停在一个再也发不出请求的会话里。
 * 这里做集中处理：并发 401 只跳一次，已经在 /login 时不再重复跳。
 */
export function redirectToLogin(): void {
  clearAuthSession();
  if (typeof window === 'undefined') return;
  if (window.location.pathname.startsWith('/login')) return;
  if (redirectingToLogin) return;
  redirectingToLogin = true;
  window.location.assign('/login');
}

/**
 * 刷新失败后的冷却期。
 *
 * <p>没有这个冷却期时，每个并发/轮询请求发现 401 都会各自触发一次刷新；
 * 刷新本身也 401（token 过期、Cookie 丢了）时，页面就会以每次请求一个的节奏
 * 持续打 `/api/auth/refresh`（实测 55 秒 20 次），白白烧网络与主线程。
 * 失败后 10 秒内直接返回 null，让调用方走自己的兜底/跳登录。
 */
const REFRESH_FAILURE_COOLDOWN_MS = 10_000;
let refreshFailedAt = 0;

/**
 * 刷新 Access Token。
 *
 * @param force 401 重试时必须强制刷新；普通启动/首次请求可以先复用内存 token。
 */
export async function refreshAccessToken(force = false): Promise<string | null> {
  if (!force && accessToken) return accessToken;
  if (refreshPromise) return refreshPromise;
  if (!force && refreshFailedAt > 0 && Date.now() - refreshFailedAt < REFRESH_FAILURE_COOLDOWN_MS) {
    return null;
  }
  refreshPromise = (async () => {
    const peerToken = await requestTokenFromPeer();
    if (peerToken) {
      accessToken = peerToken;
      refreshFailedAt = 0;
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
        // 顺序很重要：clearAuthSession 会重置冷却，失败时间戳必须写在它之后
        refreshFailedAt = Date.now();
        return null;
      }
      const body = await response.json() as { data?: AuthPayload };
      const token = body.data?.accessToken ?? null;
      accessToken = token;
      if (token) {
        refreshFailedAt = 0;
        broadcastToken(token);
      } else {
        refreshFailedAt = Date.now();
      }
      if (body.data?.userId && body.data.displayName) {
        persistUser({ userId: body.data.userId, displayName: body.data.displayName });
      }
      return token;
    } catch {
      clearAuthSession();
      refreshFailedAt = Date.now();
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
  if (response.status === 401) {
    // 先尝试刷新；刷新失败或重试后仍然 401，就强制回登录页，不能把 401 丢给调用方
    // 各自弹错（否则 SSE/上传/轮询会停在一个永远未授权的页面上）。
    const refreshed = await refreshAccessToken(true);
    if (refreshed) {
      headers.set('Authorization', `Bearer ${getAccessToken()}`);
      response = await fetch(input, { ...init, headers });
    }
    if (response.status === 401) {
      redirectToLogin();
    }
  }
  return response;
}
