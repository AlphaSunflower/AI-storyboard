import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authApi } from '../api/auth';
import { clearAuthSession } from '../lib/authSession';
import { useAuthStore } from './authStore';
import { useToastStore } from './toastStore';

beforeEach(() => {
  clearAuthSession();
  useAuthStore.setState({ user: null, isAuthenticated: false, isLoading: false, error: null });
  useToastStore.setState({ toasts: [] });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe('authStore 通知', () => {
  it('confirms a successful login', async () => {
    vi.spyOn(authApi, 'login').mockResolvedValue({
      data: { data: { accessToken: 'token-1', userId: 'u1', displayName: '小月' } },
    } as never);

    await useAuthStore.getState().login({ email: 'moon@example.com', password: 'secret' });

    expect(useToastStore.getState().toasts[0]).toMatchObject({
      title: '登录成功',
      description: '欢迎回来，小月',
      variant: 'success',
    });
  });

  it('confirms a successful registration', async () => {
    vi.spyOn(authApi, 'register').mockResolvedValue({
      data: { data: { accessToken: 'token-2', userId: 'u2', displayName: '新同学' } },
    } as never);

    await useAuthStore.getState().register({ email: 'new@example.com', password: 'secret', displayName: '新同学' });

    expect(useToastStore.getState().toasts[0]).toMatchObject({
      title: '注册成功',
      description: '欢迎加入，新同学',
    });
  });

  it('confirms logging out before the page reload', () => {
    vi.useFakeTimers();
    vi.spyOn(authApi, 'logout').mockResolvedValue({} as never);

    useAuthStore.getState().logout();

    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(useToastStore.getState().toasts[0]).toMatchObject({ title: '已退出登录', variant: 'info' });
  });
});
