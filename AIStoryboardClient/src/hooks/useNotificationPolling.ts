import { useEffect } from 'react';
import { useNotificationStore } from '../stores/notificationStore';

/**
 * 通知未读数轮询：打开页面、回到前台、每 60 秒各拉一次。
 * 没有推送通道时，这是「生成任务完成」能被看到的最低保障。
 */
export function useNotificationPolling(): void {
  const refreshUnread = useNotificationStore((state) => state.refreshUnread);

  useEffect(() => {
    void refreshUnread();
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refreshUnread();
    };
    document.addEventListener('visibilitychange', onVisible);
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void refreshUnread();
    }, 60_000);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      window.clearInterval(timer);
    };
  }, [refreshUnread]);
}
