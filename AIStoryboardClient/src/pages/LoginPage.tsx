import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/authStore';

export function LoginPage() {
  const [isRegister, setIsRegister] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const { login, register, isLoading, error } = useAuthStore();
  const navigate = useNavigate();

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
