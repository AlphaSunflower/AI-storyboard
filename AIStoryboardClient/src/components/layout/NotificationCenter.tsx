import { useNavigate } from 'react-router-dom';
import { Bell, CheckCheck, X } from 'lucide-react';
import { useNotificationStore } from '../../stores/notificationStore';
import type { UserNotification } from '../../api/notifications';
import './NotificationCenter.css';

function relativeTime(value: string): string {
  const timestamp = new Date(value).getTime();
  if (Number.isNaN(timestamp)) return '';
  const minutes = Math.floor((Date.now() - timestamp) / 60_000);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days} 天前`;
  return new Date(timestamp).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' });
}

/** 站内通知抽屉：手机端底部弹出，桌面端右侧滑出。 */
export function NotificationCenter() {
  const navigate = useNavigate();
  const open = useNotificationStore((state) => state.drawerOpen);
  const items = useNotificationStore((state) => state.items);
  const unread = useNotificationStore((state) => state.unread);
  const loading = useNotificationStore((state) => state.loading);
  const closeDrawer = useNotificationStore((state) => state.closeDrawer);
  const markRead = useNotificationStore((state) => state.markRead);
  const markAllRead = useNotificationStore((state) => state.markAllRead);

  if (!open) return null;

  const handleOpen = async (item: UserNotification) => {
    await markRead(item.id);
    closeDrawer();
    if (item.linkType === 'conversation' && item.linkId) {
      navigate('/chat', { state: { conversationId: item.linkId } });
    }
  };

  return (
    <div className="notification-center" role="dialog" aria-modal="true" aria-label="通知">
      <button type="button" className="notification-center__backdrop" aria-label="关闭通知" onClick={closeDrawer} />
      <section className="notification-center__panel">
        <header className="notification-center__head">
          <span className="notification-center__title">
            <Bell size={17} />
            通知
            {unread > 0 && <em>{unread > 99 ? '99+' : unread}</em>}
          </span>
          <div className="notification-center__actions">
            <button type="button" onClick={() => void markAllRead()} disabled={unread === 0}>
              <CheckCheck size={15} /> 全部已读
            </button>
            <button type="button" className="notification-center__close" aria-label="关闭" onClick={closeDrawer}>
              <X size={17} />
            </button>
          </div>
        </header>

        <div className="notification-center__list">
          {loading && items.length === 0 && <p className="notification-center__empty">正在读取…</p>}
          {!loading && items.length === 0 && (
            <div className="notification-center__empty">
              <Bell size={22} />
              <span>还没有通知</span>
              <small>图片和视频生成完成后会在这里提醒你</small>
            </div>
          )}
          {items.map((item) => (
            <button
              type="button"
              key={item.id}
              className={`notification-center__item${item.readAt ? '' : ' is-unread'}`}
              onClick={() => void handleOpen(item)}
            >
              <span className="notification-center__dot" aria-hidden="true" />
              <span className="notification-center__body">
                <strong>{item.title}</strong>
                {item.body && <span>{item.body}</span>}
                <time>{relativeTime(item.createdAt)}</time>
              </span>
            </button>
          ))}
        </div>
      </section>
    </div>
  );
}
