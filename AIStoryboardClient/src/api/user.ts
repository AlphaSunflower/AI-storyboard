import client from './client';
import type { ApiResponse } from './auth';
import type { AgentApprovalMode } from './agent';
import type { ThemePreference } from '../lib/theme';

export interface Profile {
  userId: string;
  displayName: string;
  email: string;
  /** member / admin；管理员额外拥有「反馈收件箱」入口。 */
  role?: string;
}

export interface UserStats {
  imageCount: number;
  videoCount: number;
  projectCount: number;
}

/** 繁忙时发送：排队等当前回合结束，或插队到当前回合。 */
export type BusySendBehavior = 'queue' | 'steer';
/** 工作步骤展示：只看思考 / 标准（含工具名）/ 详细（当前状态）。 */
export type WorkStepDisplay = 'thinking' | 'standard' | 'detailed';

export interface AccountSettings {
  defaultApprovalMode: AgentApprovalMode;
  busySendBehavior: BusySendBehavior;
  workStepDisplay: WorkStepDisplay;
  themePreference: ThemePreference;
}

export interface FeedbackResult {
  id: string;
  status: string;
}

export type FeedbackStatus = 'open' | 'resolved';

export interface FeedbackAdminItem {
  id: string;
  userId: string;
  userDisplayName: string | null;
  userEmail: string | null;
  content: string;
  imageUrls: string[];
  status: FeedbackStatus;
  createdAt: string;
}

export const userApi = {
  getProfile: () => client.get<ApiResponse<Profile>>('/user/profile'),
  updateProfile: (data: { displayName?: string; email?: string }) =>
    client.put<ApiResponse<Profile>>('/user/profile', data),
  changePassword: (data: { oldPassword: string; newPassword: string }) =>
    client.put<ApiResponse<void>>('/user/password', data),
  getStats: () => client.get<ApiResponse<UserStats>>('/user/stats'),
  getSettings: () => client.get<ApiResponse<AccountSettings>>('/user/settings'),
  updateSettings: (data: Partial<AccountSettings>) =>
    client.put<ApiResponse<AccountSettings>>('/user/settings', data),
  submitFeedback: (data: { content: string; imageUrls: string[] }) =>
    client.post<ApiResponse<FeedbackResult>>('/user/feedback', data),
  getFeedbackInbox: () => client.get<ApiResponse<FeedbackAdminItem[]>>('/user/feedback'),
  updateFeedbackStatus: (id: string, status: FeedbackStatus) =>
    client.patch<ApiResponse<void>>(`/user/feedback/${id}`, { status }),
};
