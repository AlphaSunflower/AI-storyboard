import { useState } from 'react';
import { setJobToken } from '../services/jobAuth';

// 画布登录页（Email + Password，走网关 /api/auth/login，复用同一批用户数据）
// 深墨绿 + 荧光绿工作台质感：主强调色统一为 --acid（与画布一致），不再混用珊瑚红 --clay。
export function LoginScreen({ onLogin, message }: { onLogin: () => void; message?: string | null }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true); setError(null);
    try {
      const res = await fetch('/api/auth/login', {
        method: 'POST', credentials: 'include',
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
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败');
      setLoading(false);
    }
  };

  const inputClass = 'h-10 w-full rounded-[10px] border border-[var(--border-light)] bg-[rgba(255,255,255,0.05)] px-3.5 text-sm text-foreground placeholder:text-muted-foreground outline-none transition focus:border-[var(--acid)] focus:ring-2 focus:ring-[rgba(244,244,245,0.18)]';

  return (
    <div className="login-shell view-shell min-h-dvh flex items-center justify-center px-6"
      style={{ background: 'radial-gradient(circle at 18% 12%, rgba(244,244,245,0.10), transparent 34%), radial-gradient(circle at 84% 88%, rgba(24,24,24,0.9), transparent 46%), var(--canvas)' }}>
      <div className="login-card w-full max-w-sm rounded-2xl border border-[var(--border-dark)] bg-card p-7 shadow-[0_18px_50px_rgba(0,0,0,0.55)]">
        <span className="flex items-center gap-2 font-mono text-[11px] tracking-[0.2em] text-acid">
          <span className="inline-block h-2 w-2 rounded-full bg-acid shadow-[0_0_8px_rgba(244,244,245,0.8)]" />
          MORROW 设计画布
        </span>
        <h2 className="mt-4 text-2xl font-semibold tracking-tight text-foreground">欢迎回来</h2>
        <p className="mt-1 text-[13px] leading-relaxed text-muted-foreground">登录后继续你的创作（同一账号数据）</p>
        {message && <p className="mt-3 rounded-lg border border-[rgba(145,145,145,.24)] bg-[rgba(145,145,145,.08)] px-3 py-2 text-[12px] text-[#bcbcbc]" role="alert">{message}</p>}
        <form onSubmit={submit} className="mt-6">
          <label className="text-[13px] text-muted-foreground">邮箱</label>
          <input type="email" value={email} required autoFocus onChange={(e) => setEmail(e.target.value)} className={`mt-1.5 ${inputClass}`} placeholder="you@example.com" />
          <div className="mt-4" />
          <label className="text-[13px] text-muted-foreground">密码</label>
          <input type="password" value={password} required minLength={6} onChange={(e) => setPassword(e.target.value)} className={`mt-1.5 ${inputClass}`} placeholder="••••••" />
          {error && <p className="mt-3 text-[13px] text-destructive">{error}</p>}
          <button type="submit" disabled={loading}
            className="mt-6 h-11 w-full rounded-[10px] bg-acid font-semibold text-ink transition hover:brightness-105 disabled:opacity-60">
            {loading ? '请稍候…' : '登录'}
          </button>
        </form>
      </div>
    </div>
  );
}
