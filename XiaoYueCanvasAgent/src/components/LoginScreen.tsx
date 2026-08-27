import { useState } from 'react';
import { setJobToken } from '../services/jobAuth';

// 画布登录页（仿 5173 前端：email + password，走 ai分镜 /api/auth/login，复用同一批用户数据）
export function LoginScreen({ onLogin }: { onLogin: () => void }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 登录走网关 → ai-storyboard（dev 靠 vite proxy，生产靠网关同域）
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true); setError(null);
    try {
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        credentials: 'omit',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => null) as { message?: string } | null;
        throw new Error(body?.message || `登录失败 (${res.status})`);
      }
      const data = await res.json() as { data?: { accessToken?: string; userId?: string } };
      if (!data.data?.accessToken) throw new Error('登录响应缺少 accessToken');
      setJobToken(data.data.accessToken, data.data.userId);
      onLogin();
    } catch (e) {
      setError(e instanceof Error ? e.message : '登录失败');
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100dvh', display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: 'var(--canvas)', padding: '24px',
      }}
    >
      <div
        style={{
          background: 'var(--card)', border: '1px solid rgba(255,255,255,0.06)', borderRadius: 16,
          padding: '28px 24px', width: '100%', maxWidth: 380, boxShadow: '0 12px 40px rgba(0,0,0,0.4)',
        }}
      >
        <span style={{ color: 'var(--clay)', fontSize: 13, fontWeight: 500, letterSpacing: '0.12em' }}>
          MORROW 设计画布
        </span>
        <h2 style={{ color: 'var(--paper)', fontSize: 22, fontWeight: 600, margin: '6px 0 4px' }}>欢迎回来</h2>
        <p style={{ color: 'rgba(238,239,223,0.6)', fontSize: 13, marginBottom: 20 }}>
          登录后继续你的创作（同一账号数据）
        </p>
        <form onSubmit={submit}>
          <label style={{ display: 'block', color: 'rgba(238,239,223,0.7)', fontSize: 13, marginBottom: 4 }}>邮箱</label>
          <input
            type="email" value={email} required autoFocus
            onChange={(e) => setEmail(e.target.value)}
            style={inputStyle}
          />
          <div style={{ height: 14 }} />
          <label style={{ display: 'block', color: 'rgba(238,239,223,0.7)', fontSize: 13, marginBottom: 4 }}>密码</label>
          <input
            type="password" value={password} required minLength={6}
            onChange={(e) => setPassword(e.target.value)}
            style={inputStyle}
          />
          {error && (
            <p style={{ color: '#ff8888', fontSize: 13, margin: '12px 0 0' }}>{error}</p>
          )}
          <button
            type="submit" disabled={loading}
            style={{
              width: '100%', height: 42, marginTop: 18, borderRadius: 10, border: 'none',
              background: 'var(--clay)', color: '#141414', fontSize: 14, fontWeight: 600,
              cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1,
            }}
          >
            {loading ? '请稍候...' : '登录'}
          </button>
        </form>
      </div>
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  width: '100%', padding: '10px 14px', height: 40,
  border: '1px solid rgba(255,255,255,0.1)', borderRadius: 10,
  background: 'rgba(255,255,255,0.05)', color: 'var(--paper)', fontSize: 14,
  outline: 'none', boxSizing: 'border-box',
};
