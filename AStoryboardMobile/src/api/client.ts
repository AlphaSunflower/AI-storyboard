import axios from 'axios';
import { router } from 'expo-router';
import { BACKEND_URL } from '../config';
import { storage } from '../utils/storage';

const client = axios.create({
  baseURL: BACKEND_URL + '/api',
  timeout: 120000,
  headers: { 'Content-Type': 'application/json' },
});

// 401 → 尝试用 refreshToken 刷新，成功重放原请求；失败清 token 并跳登录
let refreshing: Promise<string | null> | null = null;

async function tryRefresh(): Promise<string | null> {
  const refreshToken = await storage.getItem('refreshToken');
  if (!refreshToken) return null;
  try {
    const resp = await fetch(BACKEND_URL + '/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });
    const json = await resp.json();
    const accessToken = json?.data?.accessToken as string | undefined;
    const newRefresh = json?.data?.refreshToken as string | undefined;
    if (!accessToken) return null;
    await storage.setItem('accessToken', accessToken);
    if (newRefresh) await storage.setItem('refreshToken', newRefresh);
    return accessToken;
  } catch {
    return null;
  }
}

client.interceptors.request.use(async (config) => {
  const token = await storage.getItem('accessToken');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  (response) => response,
  async (error) => {
    const config = error.config;
    const isAuthPath = config?.url?.startsWith('/auth/');
    if (error.response?.status === 401 && config && !config._retried && !isAuthPath) {
      config._retried = true;
      refreshing = refreshing ?? tryRefresh();
      const newToken = await refreshing;
      refreshing = null;
      if (newToken) {
        config.headers.Authorization = `Bearer ${newToken}`;
        return client(config);
      }
    }
    if (error.response?.status === 401) {
      await storage.removeItem('accessToken');
      await storage.removeItem('refreshToken');
      await storage.removeItem('user');
      try {
        router.replace('/login');
      } catch { /* navigation best-effort */ }
    }
    return Promise.reject(error);
  }
);

export default client;
