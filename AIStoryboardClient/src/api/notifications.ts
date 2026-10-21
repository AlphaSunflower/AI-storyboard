import client from './client';

export interface UserNotification {
  id: string;
  type: 'SCHEDULE_RUN' | 'SYSTEM' | string;
  title: string;
  body?: string | null;
  linkType?: 'conversation' | 'schedule' | 'none' | string | null;
  linkId?: string | null;
  readAt?: string | null;
  createdAt: string;
}

export const notificationApi = {
  list: (limit = 50) => client.get<{ data: UserNotification[] }>('/agent/notifications', { params: { limit } }),
  unreadCount: () => client.get<{ data: { count: number } }>('/agent/notifications/unread-count'),
  markRead: (id: string) => client.post<{ data: boolean }>(`/agent/notifications/${id}/read`),
  markAllRead: () => client.post<{ data: { updated: number } }>('/agent/notifications/read-all'),
};
