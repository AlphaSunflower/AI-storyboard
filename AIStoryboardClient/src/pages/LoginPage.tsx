import { useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import gsap from 'gsap';
import { SplitText } from 'gsap/SplitText';
import { useGSAP } from '@gsap/react';
import { useAuthStore } from '../stores/authStore';
import Particles from '../components/Particles';
import SplashCursor from '../components/SplashCursor';
import ParticleText from '../components/ParticleText';

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
        position: 'relative',
        minHeight: '100vh',
        overflow: 'hidden',
        background: 'var(--color-surface-dark)',
      }}
    >
      <div style={{ position: 'absolute', inset: 0 }}>
        <Particles
          particleColors={["#cc785c"]}
          particleCount={200}
          particleSpread={10}
          speed={0.1}
          particleBaseSize={100}
          moveParticlesOnHover={true}
          alphaParticles={false}
          disableRotation={false}
        />
      </div>
      <SplashCursor RAINBOW_MODE={false} COLOR="#cc785c" />
      {/* 顶部导航栏：ParticleText 品牌名（珊瑚→琥珀渐变） */}
      <nav
        style={{
          position: 'absolute',
          top: 0,
          left: 0,
          right: 0,
          height: 64,
          zIndex: 3,
          display: 'flex',
          alignItems: 'center',
          padding: '0 var(--space-lg)',
        }}
      >
        <ParticleText
          text="AlphaSunflower AI分镜"
          particleSize={2}
          density={3}
          color="#cc785c"
          highlightColor="#e8a55a"
          scatter={120}
          gatherDuration={1200}
          stagger={200}
          pointerRepel={30}
          repelRadius={110}
          idleDrift={0.6}
          trigger="hover"
          fontSize={26}
          fontWeight={700}
          glow
          style={{ width: 420, height: 64, minHeight: 64 }}
        />
      </nav>
      <div
        style={{
          position: 'relative',
          zIndex: 2,
          pointerEvents: 'none',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: '100vh',
        }}
      >
      <div
        ref={cardRef}
        style={{
          pointerEvents: 'auto',
          background: 'var(--color-surface-dark-elevated)',
          border: '1px solid rgba(250, 249, 245, 0.08)',
          borderRadius: 'var(--rounded-lg)',
          padding: 'var(--space-xl)',
          maxWidth: 400,
          width: '100%',
          boxShadow: '0 12px 40px rgba(0, 0, 0, 0.4)',
        }}
      >
        <h1
          style={{
            font: 'var(--text-display-sm)',
            color: 'var(--color-on-dark)',
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
                  color: 'var(--color-on-dark-soft)',
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
                color: 'var(--color-on-dark-soft)',
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
                color: 'var(--color-on-dark-soft)',
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
            color: 'var(--color-on-dark-soft)',
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
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '10px 14px',
  height: 40,
  border: '1px solid rgba(250, 249, 245, 0.12)',
  borderRadius: 'var(--rounded-md)',
  fontSize: 14,
  background: 'var(--color-surface-dark-soft)',
  color: 'var(--color-on-dark)',
};
