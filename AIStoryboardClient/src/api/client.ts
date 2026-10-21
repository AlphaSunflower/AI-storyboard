import axios from 'axios';
import { BACKEND_URL, API_TIMEOUT } from '../config';
import {
  getAccessToken,
  redirectToLogin,
  refreshAccessToken,
} from '../lib/authSession';

const client = axios.create({
  baseURL: BACKEND_URL + '/api',
  withCredentials: true,
  timeout: API_TIMEOUT,
  headers: { 'Content-Type': 'application/json' },
});

client.interceptors.request.use(async (config) => {
  const isAuthPath = config.url?.startsWith('/auth/');
  const token = getAccessToken() ?? (isAuthPath ? null : await refreshAccessToken());
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config as { _retryAuth?: boolean; url?: string; headers?: Record<string, string> } | undefined;
    const isRefreshRequest = original?.url?.includes('/auth/refresh');
    if (error.response?.status === 401 && !isRefreshRequest) {
      // 只重试一次；重试后仍是 401（或本来就没有 config）也必须回登录页，
      // 不能因为 `_retryAuth` 已置位就把 401 继续丢给业务层。
      if (original && !original._retryAuth) {
        original._retryAuth = true;
        const token = await refreshAccessToken();
        if (token) {
          original.headers = original.headers ?? {};
          original.headers.Authorization = `Bearer ${token}`;
          return client(original);
        }
      }
      redirectToLogin();
    }
    return Promise.reject(error);
  }
);

export default client;
