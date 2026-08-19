import { useState } from 'react';

export function SettingsModal() {
  const [token, setToken] = useState(localStorage.getItem('accessToken') ?? '');

  const handleSave = () => {
    if (token.trim()) {
      localStorage.setItem('accessToken', token.trim());
    } else {
      localStorage.removeItem('accessToken');
    }
  };

  return (
    <div className="text-sm" style={{ color: 'var(--color-body)' }}>
      <p className="mb-3">配置认证 Token 和其他设置。</p>

      <label className="block text-xs mb-1" style={{ color: 'var(--color-muted)' }}>Access Token</label>
      <input
        type="text"
        value={token}
        onChange={(e) => setToken(e.target.value)}
        placeholder="Bearer token..."
        className="w-full px-3 py-2 text-sm mb-3 outline-none"
        style={{
          border: '1px solid var(--color-hairline)',
          borderRadius: 'var(--rounded-md)',
          color: 'var(--color-ink)',
        }}
      />

      <button
        onClick={handleSave}
        className="px-4 py-2 text-sm font-medium rounded-md"
        style={{ background: 'var(--color-primary)', color: 'var(--color-on-primary)', cursor: 'pointer' }}
      >
        保存
      </button>

      <p className="mt-3 text-xs" style={{ color: 'var(--color-muted)' }}>
        Token 存储在 localStorage 中，用于 API 请求认证。
      </p>
    </div>
  );
}
