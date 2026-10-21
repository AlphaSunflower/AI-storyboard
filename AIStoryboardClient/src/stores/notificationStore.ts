import { create } from 'zustand';
import { notificationApi, type UserNotification } from '../api/notifications';

interface NotificationState {
  items: UserNotification[];
  unread: number;
  loading: boolean;
  drawerOpen: boolean;
  refreshUnread: () => Promise<void>;
  openDrawer: () => Promise<void>;
  closeDrawer: () => void;
  markRead: (id: string) => Promise<void>;
  markAllRead: () => Promise<void>;
  reset: () => void;
}

/**
 * 站内通知：底部「智能体对话」角标与通知抽屉共用同一份未读数。
 * 轮询由 `useNotificationPolling` 负责，store 只做数据与幂等更新。
 */
export const useNotificationStore = create<NotificationState>((set, get) => ({
  items: [],
  unread: 0,
  loading: false,
  drawerOpen: false,

  refreshUnread: async () => {
    try {
      const response = await notificationApi.unreadCount();
      set({ unread: response.data.data?.count ?? 0 });
    } catch {
      // 未登录 / 网络异常时保持上一次数值，不弹错误打断对话
    }
  },

  openDrawer: async () => {
    set({ drawerOpen: true, loading: true });
    try {
      const response = await notificationApi.list(50);
      set({ items: response.data.data ?? [], loading: false });
      await get().refreshUnread();
    } catch {
      set({ loading: false });
    }
  },

  closeDrawer: () => set({ drawerOpen: false }),

  markRead: async (id) => {
    const target = get().items.find((item) => item.id === id);
    if (!target || target.readAt) return;
    set((state) => ({
      items: state.items.map((item) => (item.id === id ? { ...item, readAt: new Date().toISOString() } : item)),
      unread: Math.max(0, state.unread - 1),
    }));
    try {
      await notificationApi.markRead(id);
    } catch {
      await get().refreshUnread();
    }
  },

  markAllRead: async () => {
    const now = new Date().toISOString();
    set((state) => ({
      items: state.items.map((item) => (item.readAt ? item : { ...item, readAt: now })),
      unread: 0,
    }));
    try {
      await notificationApi.markAllRead();
    } catch {
      await get().refreshUnread();
    }
  },

  reset: () => set({ items: [], unread: 0, drawerOpen: false, loading: false }),
}));
