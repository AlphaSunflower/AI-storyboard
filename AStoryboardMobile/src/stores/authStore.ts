import { create } from 'zustand';
import { authApi } from '../api/auth';
import type { LoginRequest, RegisterRequest } from '../api/auth';
import { storage } from '../utils/storage';

interface User {
  userId: string;
  displayName: string;
}

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
  checkAuth: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isAuthenticated: false,
  isLoading: false,
  error: null,

  login: async (data) => {
    set({ isLoading: true, error: null });
    try {
      const res = await authApi.login(data);
      const { accessToken, refreshToken, userId, displayName } = res.data.data;
      await storage.setItem('accessToken', accessToken);
      await storage.setItem('refreshToken', refreshToken);
      await storage.setItem('user', JSON.stringify({ userId, displayName }));
      set({ user: { userId, displayName }, isAuthenticated: true, isLoading: false });
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      set({ error: err.response?.data?.message || '登录失败', isLoading: false });
    }
  },

  register: async (data) => {
    set({ isLoading: true, error: null });
    try {
      const res = await authApi.register(data);
      const { accessToken, refreshToken, userId, displayName } = res.data.data;
      await storage.setItem('accessToken', accessToken);
      await storage.setItem('refreshToken', refreshToken);
      await storage.setItem('user', JSON.stringify({ userId, displayName }));
      set({ user: { userId, displayName }, isAuthenticated: true, isLoading: false });
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      set({ error: err.response?.data?.message || '注册失败', isLoading: false });
    }
  },

  logout: async () => {
    await storage.removeItem('accessToken');
    await storage.removeItem('refreshToken');
    await storage.removeItem('user');
    set({ user: null, isAuthenticated: false });
  },

  checkAuth: async () => {
    const token = await storage.getItem('accessToken');
    if (token) {
      const userStr = await storage.getItem('user');
      const user = userStr ? JSON.parse(userStr) : null;
      set({ isAuthenticated: true, user });
    }
  },
}));
