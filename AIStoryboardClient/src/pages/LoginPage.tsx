import { useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { FileText, Image as ImageIcon, Film, Sparkles } from 'lucide-react';
import { useAuthStore } from '../stores/authStore';
import Particles from '../components/Particles';
import SplashCursor from '../components/SplashCursor';
import ScrollExpand from '../components/ScrollExpand';
import MoonLogo from '../components/agent/MoonLogo';

// 动画降级：尊重系统减弱动效偏好
function prefersReducedMotion() {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** 登录页 = 品牌 Hero 首屏：左侧产品主张（文案 + 特性点 + CTA），右侧登录卡片 */
export function LoginPage() {
  const [isRegister, setIsRegister] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const { login, register, isLoading, error } = useAuthStore();
  const navigate = useNavigate();
  const pageRef = useRef<HTMLDivElement>(null);
  const heroRef = useRef<HTMLDivElement>(null);
  const cardRef = useRef<HTMLDivElement>(null);

  // Hero 入场编排：标题逐字上浮 + 左侧元素 stagger + 卡片弹性浮入
  useGSAP(() => {
    if (prefersReducedMotion()) return;
    const ctx = gsap.context(() => {
      if (heroRef.current) {
        gsap.fromTo(
          heroRef.current.querySelectorAll('.hero-eyebrow, .hero-sub, .hero-features'),
          { y: 18, opacity: 0 },
          { y: 0, opacity: 1, duration: 0.55, stagger: 0.09, ease: 'power2.out', delay: 0.25 }
        );
      }
      if (cardRef.current) {
        gsap.fromTo(
          cardRef.current,
          { y: 28, opacity: 0, scale: 0.97 },
          { y: 0, opacity: 1, scale: 1, duration: 0.55, ease: 'back.out(1.4)', delay: 0.35 }
        );
      }
    }, pageRef);
    return () => ctx.revert();
  }, { scope: pageRef });

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
      ref={pageRef}
      style={{
        position: 'relative',
        minHeight: '100dvh',
        background: 'var(--color-surface-dark)',
      }}
    >
      {/* 背景粒子（珊瑚色） + 鼠标轨迹（粒子仅铺首屏，滚动区由媒体接管） */}
      <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: '100dvh' }}>
        <Particles
          particleColors={['#cc785c']}
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

      {/* 顶部导航：品牌标识 + 文档入口 */}
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
          justifyContent: 'space-between',
          padding: '0 var(--space-lg)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <MoonLogo size={26} />
          <span
            style={{
              color: 'var(--color-on-dark)',
              fontSize: 17,
              fontWeight: 600,
              letterSpacing: '-0.01em',
            }}
          >
            AlphaSunflower AI 分镜
          </span>
        </div>
        <button
          type="button"
          onClick={() => navigate('/docs')}
          style={{
            background: 'none',
            border: '1px solid rgba(250, 249, 245, 0.2)',
            color: 'var(--color-on-dark-soft)',
            padding: '6px 16px',
            height: 32,
            borderRadius: 'var(--rounded-md)',
            fontSize: 13,
            cursor: 'pointer',
          }}
        >
          使用文档
        </button>
      </nav>

      {/* Hero 主体：左文案 + 右登录卡片 */}
      <main
        style={{
          position: 'relative',
          zIndex: 2,
          minHeight: '100dvh',
          display: 'flex',
          alignItems: 'center',
          maxWidth: 1200,
          margin: '0 auto',
          padding: '96px var(--space-lg) 64px',
        }}
      >
        <div
          className="hero-grid"
          style={{
            display: 'grid',
            gridTemplateColumns: 'minmax(0, 1.15fr) minmax(0, 0.85fr)',
            gap: 'var(--space-xxl)',
            alignItems: 'center',
            width: '100%',
          }}
        >
          {/* 左：产品主张 */}
          <div ref={heroRef}>
            <p
              className="hero-eyebrow"
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                color: 'var(--color-primary)',
                fontSize: 13,
                fontWeight: 500,
                letterSpacing: '0.18em',
                marginBottom: 'var(--space-lg)',
              }}
            >
              <Sparkles size={15} strokeWidth={1.8} />
              AI 分镜创作平台
            </p>
            <h1
              style={{
                color: 'var(--color-on-dark)',
                fontSize: 'clamp(40px, 5.2vw, 60px)',
                fontWeight: 600,
                lineHeight: 1.08,
                letterSpacing: '-0.02em',
                marginBottom: 'var(--space-lg)',
                maxWidth: '12em',
              }}
            >
              让剧本，直接长成分镜
            </h1>
            <p
              className="hero-sub"
              style={{
                color: 'var(--color-on-dark-soft)',
                fontSize: 17,
                lineHeight: 1.7,
                maxWidth: '46ch',
                marginBottom: 'var(--space-xl)',
              }}
            >
              输入剧本，AI 自动拆分镜头、生成画面。Moon 智能体随叫随到，
              图改图、图生视频一站完成。
            </p>
            <ul
              className="hero-features"
              style={{
                listStyle: 'none',
                margin: 0,
                padding: 0,
                display: 'flex',
                flexDirection: 'column',
                gap: 14,
                marginBottom: 'var(--space-xl)',
              }}
            >
              {[
                { icon: <FileText size={16} strokeWidth={1.8} />, label: '剧本智能拆解，生成镜头脚本' },
                { icon: <ImageIcon size={16} strokeWidth={1.8} />, label: '文生图 / 图改图，产出分镜画面' },
                { icon: <Film size={16} strokeWidth={1.8} />, label: '图生视频，动态预演镜头' },
              ].map((item) => (
                <li
                  key={item.label}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    color: 'var(--color-on-dark)',
                    fontSize: 15,
                  }}
                >
                  <span
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      width: 28,
                      height: 28,
                      borderRadius: 'var(--rounded-md)',
                      background: 'rgba(204, 120, 92, 0.14)',
                      color: 'var(--color-primary)',
                      flexShrink: 0,
                    }}
                  >
                    {item.icon}
                  </span>
                  {item.label}
                </li>
              ))}
            </ul>
          </div>

          {/* 右：登录卡片 */}
          <div
            ref={cardRef}
            style={{
              background: 'var(--color-surface-dark-elevated)',
              border: '1px solid rgba(250, 249, 245, 0.08)',
              borderRadius: 'var(--rounded-lg)',
              padding: 'var(--space-xl)',
              maxWidth: 400,
              width: '100%',
              justifySelf: 'end',
              boxShadow: '0 12px 40px rgba(0, 0, 0, 0.4)',
            }}
          >
            <h2
              style={{
                color: 'var(--color-on-dark)',
                fontSize: 22,
                fontWeight: 600,
                letterSpacing: '-0.01em',
                marginBottom: 6,
              }}
            >
              {isRegister ? '创建账号' : '欢迎回来'}
            </h2>
            <p
              style={{
                color: 'var(--color-on-dark-soft)',
                fontSize: 13,
                marginBottom: 'var(--space-lg)',
              }}
            >
              {isRegister ? '注册后即可开始分镜创作' : '登录后继续你的分镜创作'}
            </p>
            <form onSubmit={handleSubmit}>
              {isRegister && (
                <div style={{ marginBottom: 'var(--space-md)' }}>
                  <label
                    style={{
                      display: 'block',
                      marginBottom: 4,
                      color: 'var(--color-on-dark-soft)',
                      fontSize: 13,
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
                    fontSize: 13,
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
                    fontSize: 13,
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
      </main>

      {/* 滚动展开转场（ScrollExpand）：静态分镜图随滚动展开；「剧本 → 分镜 → 成片」先浮在小窗上，
          全屏时同款大字淡入常驻，字始终可见 */}
      <section style={{ position: 'relative', background: 'var(--color-surface-dark)' }}>
        <ScrollExpand
          src="/se-assets/hero.jpg"
          title="剧本 → 分镜 → 成片"
          scrollHint="滚动展开"
          useWindowScroll
          startWidth={44}
          startHeight={60}
          startRadius={20}
          mediaZoom={1.3}
        >
          <div
            style={{
              color: '#fff',
              fontFamily: 'var(--font-sans)',
              textShadow: '0 2px 24px rgba(0,0,0,0.45)',
              padding: '0 6%',
            }}
          >
            <span
              style={{
                fontSize: 'clamp(28px, 4.5vw, 52px)',
                fontWeight: 700,
                letterSpacing: '-0.02em',
                lineHeight: 1.15,
              }}
            >
              极速提效，告别纯人工拆解
            </span>
          </div>
        </ScrollExpand>
      </section>

      {/* 移动端：登录卡片堆叠在 Hero 文案下方 */}
      <style>{`
        @media (max-width: 768px) {
          main {
            align-items: flex-start;
            padding-top: 88px;
          }
          .hero-grid {
            grid-template-columns: 1fr !important;
            gap: var(--space-xl) !important;
          }
          .hero-grid > div:last-child {
            justify-self: auto !important;
            max-width: 100% !important;
          }
        }
      `}</style>
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
