import client from './client';

/** 主后端统一响应包装 */
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  userId: string;
  displayName: string;
}

/** 登录/注册 API:与 AI 分镜系统共享同一主后端账号体系(/api/auth/*) */
export const authApi = {
  login: (email: string, password: string) =>
    client.post<ApiResponse<LoginResponse>>('/auth/login', { email, password }),
  register: (email: string, password: string, displayName: string) =>
    client.post<ApiResponse<LoginResponse>>('/auth/register', { email, password, displayName }),
};

/** 持久化登录态(token 与主前端同 key,同源挂载时互通) */
export function persistLogin(res: LoginResponse) {
  localStorage.setItem('accessToken', res.accessToken);
  localStorage.setItem('refreshToken', res.refreshToken);
  localStorage.setItem('user', JSON.stringify({ userId: res.userId, displayName: res.displayName }));
}

export function isLoggedIn(): boolean {
  return !!localStorage.getItem('accessToken');
}
