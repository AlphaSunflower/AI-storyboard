import { useAuthStore } from '../../stores/authStore';

export function SettingsContent() {
  const { user, logout } = useAuthStore();

  return (
    <div className="flex flex-col gap-6 py-4">
      <div className="flex items-center gap-3">
        <div
          className="w-10 h-10 rounded-full flex items-center justify-center text-sm font-semibold"
          style={{ background: 'var(--color-primary)', color: '#fff' }}
        >
          {user?.displayName?.[0] ?? '?'}
        </div>
        <span className="text-sm font-medium" style={{ color: 'var(--color-ink)' }}>
          {user?.displayName ?? '未知用户'}
        </span>
      </div>
      <button
        onClick={logout}
        className="w-full py-2.5 rounded-lg text-sm font-medium transition-opacity hover:opacity-80"
        style={{ background: 'var(--color-hairline)', color: 'var(--color-ink)' }}
      >
        退出登录
      </button>
    </div>
  );
}
