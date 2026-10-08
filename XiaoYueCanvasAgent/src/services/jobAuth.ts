let accessToken: string | null = null;
let userId: string | null = null;
let refreshPromise: Promise<string | null> | null = null;

interface RefreshResponse {
  data?: {
    accessToken?: string;
    userId?: string;
  };
}

export async function bootstrapJobAuth(): Promise<string | null> {
  if (accessToken && !tokenExpired()) return accessToken;
  if (refreshPromise) return refreshPromise;
  accessToken = null;
  refreshPromise = (async () => {
    try {
      const response = await fetch('/api/auth/refresh', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      });
      if (!response.ok) return null;
      const body = await response.json() as RefreshResponse;
      accessToken = body.data?.accessToken ?? null;
      userId = body.data?.userId ?? userId;
      return accessToken;
    } catch {
      return null;
    } finally {
      refreshPromise = null;
    }
  })();
  return refreshPromise;
}

export function getJobToken(): string | null {
  return accessToken;
}

export function setJobToken(token: string, nextUserId?: string): void {
  accessToken = token;
  if (nextUserId) userId = nextUserId;
}

export function clearToken(): void {
  accessToken = null;
  userId = null;
  refreshPromise = null;
}

export function getJobUserId(): string | null {
  return userId;
}

export async function getStreamTicket(path: string): Promise<string | null> {
  const token = accessToken ?? await bootstrapJobAuth();
  if (!token) return null;
  const response = await fetch('/api/auth/stream-ticket', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ path }),
  });
  if (!response.ok) return null;
  const body = await response.json() as { data?: { ticket?: string } };
  return body.data?.ticket ?? null;
}

export function tokenExpired(): boolean {
  if (!accessToken) return true;
  const expiresAt = jwtExpiresAt(accessToken);
  if (expiresAt == null) return true;
  return expiresAt <= Date.now() + 30_000;
}

function jwtExpiresAt(token: string): number | null {
  try {
    const payload = token.split('.')[1];
    if (!payload) return null;
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=');
    const body = JSON.parse(atob(padded)) as { exp?: number };
    return typeof body.exp === 'number' ? body.exp * 1000 : null;
  } catch {
    return null;
  }
}
