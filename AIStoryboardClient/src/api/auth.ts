import client from './client';

// 通用响应类型
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  userId: string;
  displayName: string;
}

export const authApi = {
  login: (data: LoginRequest) =>
    client.post<ApiResponse<LoginResponse>>('/auth/login', data),
  register: (data: RegisterRequest) =>
    client.post<ApiResponse<LoginResponse>>('/auth/register', data),
  refresh: (refreshToken: string) =>
    client.post<ApiResponse<LoginResponse>>('/auth/refresh', { refreshToken }),
};
