import { create } from 'zustand';
import { authApi } from '../api/auth';
import type { LoginRequest, RegisterRequest } from '../api/auth';

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
  logout: () => void;
  checkAuth: () => void;
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
      localStorage.setItem('accessToken', accessToken);
      localStorage.setItem('refreshToken', refreshToken);
      localStorage.setItem('user', JSON.stringify({ userId, displayName }));
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
      localStorage.setItem('accessToken', accessToken);
      localStorage.setItem('refreshToken', refreshToken);
      localStorage.setItem('user', JSON.stringify({ userId, displayName }));
      set({ user: { userId, displayName }, isAuthenticated: true, isLoading: false });
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      set({ error: err.response?.data?.message || '注册失败', isLoading: false });
    }
  },

  logout: () => {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    localStorage.removeItem('user');
    set({ user: null, isAuthenticated: false });
    window.location.href = '/login';
  },

  checkAuth: () => {
    const token = localStorage.getItem('accessToken');
    if (token) {
      const userStr = localStorage.getItem('user');
      const user = userStr ? JSON.parse(userStr) : null;
      set({ isAuthenticated: true, user });
    }
  },
}));
