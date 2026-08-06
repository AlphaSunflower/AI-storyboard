import { useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import gsap from 'gsap';
import { SplitText } from 'gsap/SplitText';
import { useGSAP } from '@gsap/react';
import { useAuthStore } from '../stores/authStore';

// 注册 SplitText 插件（GSAP 3.13+ 全插件免费）
gsap.registerPlugin(SplitText);

export function LoginPage() {
  const [isRegister, setIsRegister] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const { login, register, isLoading, error } = useAuthStore();
  const navigate = useNavigate();
  const cardRef = useRef<HTMLDivElement>(null);

  // D9: 入场动画——卡片弹性浮入 + 标题逐字淡入（SplitText）+ 表单逐项淡入
  useGSAP(() => {
    if (!cardRef.current) return;
    gsap.fromTo(
      cardRef.current,
      { y: 28, opacity: 0, scale: 0.97 },
      { y: 0, opacity: 1, scale: 1, duration: 0.55, ease: 'back.out(1.4)' }
    );
    // 标题逐字动画：每字 y 上浮 + 淡入，完成后还原为纯文本（清理 SplitText 包裹的 span）
    const title = cardRef.current.querySelector<HTMLElement>('h1');
    const split = title ? new SplitText(title, { type: 'chars' }) : null;
    if (split?.chars?.length) {
      gsap.fromTo(
        split.chars,
        { y: 24, opacity: 0, scale: 0.9 },
        {
          y: 0, opacity: 1, scale: 1,
          duration: 0.45,
          stagger: 0.05,
          ease: 'power2.out',
          delay: 0.15,
          onComplete: () => {
            split.revert(); // 还原 DOM，避免残留 span
          },
        }
      );
    }
    gsap.fromTo(
      cardRef.current.querySelectorAll('form, p'),
      { y: 14, opacity: 0 },
      { y: 0, opacity: 1, duration: 0.4, stagger: 0.08, ease: 'power2.out', delay: 0.15 }
    );
  }, { scope: cardRef });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isRegister) {
      await register({ email, password, displayName });
    } else {
      await login({ email, password });
    }
    if (useAuthStore.getState().isAuthenticated) {
      navigate('/editor');
    }
  };

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: '100vh',
        background: 'var(--color-canvas)',
      }}
    >
      <div
        ref={cardRef}
        style={{
          background: 'white',
          borderRadius: 'var(--rounded-lg)',
          padding: 'var(--space-xl)',
          maxWidth: 400,
          width: '100%',
          boxShadow: '0 1px 3px rgba(20,20,19,0.08)',
        }}
      >
        <h1
          style={{
            font: 'var(--text-display-sm)',
            color: 'var(--color-ink)',
            marginBottom: 'var(--space-lg)',
            textAlign: 'center',
          }}
        >
          AI 分镜表
        </h1>
        <form onSubmit={handleSubmit}>
          {isRegister && (
            <div style={{ marginBottom: 'var(--space-md)' }}>
              <label
                style={{
                  display: 'block',
                  marginBottom: 4,
                  color: 'var(--color-muted)',
                }}
              >
                用户名
              </label>
              <input
                type="text"
                value={displayName}
                required
                onChange={(e) => setDisplayName(e.target.value)}
                style={inputStyle}
              />
            </div>
          )}
          <div style={{ marginBottom: 'var(--space-md)' }}>
            <label
              style={{
                display: 'block',
                marginBottom: 4,
                color: 'var(--color-muted)',
              }}
            >
              邮箱
            </label>
            <input
              type="email"
              value={email}
              required
              onChange={(e) => setEmail(e.target.value)}
              style={inputStyle}
            />
          </div>
          <div style={{ marginBottom: 'var(--space-lg)' }}>
            <label
              style={{
                display: 'block',
                marginBottom: 4,
                color: 'var(--color-muted)',
              }}
            >
              密码
            </label>
            <input
              type="password"
              value={password}
              required
              minLength={6}
              onChange={(e) => setPassword(e.target.value)}
              style={inputStyle}
            />
          </div>
          {error && (
            <p
              style={{
                color: 'var(--color-error)',
                marginBottom: 12,
                fontSize: 13,
              }}
            >
              {error}
            </p>
          )}
          <button
            type="submit"
            disabled={isLoading}
            style={{
              width: '100%',
              padding: '10px 20px',
              height: 40,
              background: 'var(--color-primary)',
              color: 'var(--color-on-primary)',
              border: 'none',
              borderRadius: 'var(--rounded-md)',
              fontSize: 14,
              fontWeight: 500,
              cursor: 'pointer',
            }}
          >
            {isLoading ? '请稍候...' : isRegister ? '注册' : '登录'}
          </button>
        </form>
        <p
          style={{
            textAlign: 'center',
            marginTop: 'var(--space-md)',
            fontSize: 13,
            color: 'var(--color-muted)',
          }}
        >
          {isRegister ? '已有账号？' : '没有账号？'}
          <button
            type="button"
            onClick={() => setIsRegister(!isRegister)}
            style={{
              background: 'none',
              border: 'none',
              color: 'var(--color-primary)',
              cursor: 'pointer',
              fontSize: 13,
              textDecoration: 'underline',
            }}
          >
            {isRegister ? '去登录' : '去注册'}
          </button>
        </p>
      </div>
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '10px 14px',
  height: 40,
  border: '1px solid var(--color-hairline)',
  borderRadius: 'var(--rounded-md)',
  fontSize: 14,
  background: 'var(--color-canvas)',
};
