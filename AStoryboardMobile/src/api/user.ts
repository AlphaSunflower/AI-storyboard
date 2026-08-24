import client from './client';
import type { ApiResponse } from './auth';

export interface Profile {
  userId: string;
  displayName: string;
  email: string;
}

export interface UserStats {
  imageCount: number;
  videoCount: number;
  projectCount: number;
}

export const userApi = {
  getProfile: () => client.get<ApiResponse<Profile>>('/user/profile'),
  updateProfile: (data: { displayName?: string; email?: string }) =>
    client.put<ApiResponse<Profile>>('/user/profile', data),
  changePassword: (data: { oldPassword: string; newPassword: string }) =>
    client.put<ApiResponse<void>>('/user/password', data),
  getStats: () => client.get<ApiResponse<UserStats>>('/user/stats'),
};
