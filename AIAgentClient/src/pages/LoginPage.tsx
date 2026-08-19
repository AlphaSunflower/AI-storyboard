import { useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { authApi, persistLogin } from '../api/auth';
import { Moon } from 'lucide-react';
import SplashCursor from '../components/reactbits/SplashCursor';
import Particles from '../components/reactbits/Particles';

/** Moon 智能体登录页:与 AI 分镜系统共享同一账号体系(主后端 /api/auth),浅色设计语言 */
export function LoginPage() {
  const [isRegister, setIsRegister] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();
  const cardRef = useRef<HTMLDivElement>(null);

  useGSAP(() => {
    if (!cardRef.current) return;
    gsap.fromTo(cardRef.current, { y: 24, opacity: 0 }, { y: 0, opacity: 1, duration: 0.5, ease: 'power2.out' });
  }, { scope: cardRef });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isLoading) return;
    setIsLoading(true);
    setError(null);
    try {
      const res = isRegister
        ? await authApi.register(email, password, displayName)
        : await authApi.login(email, password);
      persistLogin(res.data.data);
      navigate('/');
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败，请重试');
    } finally {
      setIsLoading(false);
    }
  };

  const inputStyle: React.CSSProperties = {
    width: '100%', padding: '13px 16px', height: 50,
    border: '1px solid var(--color-border)', borderRadius: 12,
    fontSize: 17, background: 'white', color: 'var(--color-ink)',
    outline: 'none', boxSizing: 'border-box',
  };

  return (
    <div className="relative flex items-center justify-center min-h-screen p-8 overflow-hidden" style={{ background: 'var(--color-canvas)' }}>
      {/* React Bits:珊瑚斑点粒子(登录背景)+ 光标尾迹 */}
      <div className="absolute inset-0 pointer-events-none">
        <Particles
          particleColors={['#cc785c']}
          particleCount={220}
          particleSpread={12}
          speed={0.12}
          particleBaseSize={120}
          moveParticlesOnHover={true}
          alphaParticles={false}
          disableRotation={false}
        />
      </div>
      <div className="absolute inset-0 pointer-events-none" style={{ opacity: 0.9 }}>
        <SplashCursor RAINBOW_MODE={false} COLOR="#cc785c" />
      </div>
      <div ref={cardRef} className="relative z-10" style={{
        background: 'white', border: '1px solid var(--color-border)',
        borderRadius: 20, padding: 48, maxWidth: 440, width: '100%',
        boxShadow: '0 4px 24px rgba(20,20,19,0.08)',
      }}>
        <div className="flex flex-col items-center mb-10">
          <div className="flex items-center justify-center mb-5" style={{
            width: 72, height: 72, borderRadius: 20, background: 'var(--color-surface-card)',
          }}>
            <Moon size={34} style={{ color: 'var(--color-primary)' }} />
          </div>
          <h1 className="text-[26px] font-semibold mb-2" style={{ color: 'var(--color-ink)' }}>
            Moon 智能体
          </h1>
          <p className="text-[16px]" style={{ color: 'var(--color-muted)' }}>
            {isRegister ? '创建账号，开始创作分镜' : '登录后继续你的分镜创作'}
          </p>
        </div>

        <form onSubmit={handleSubmit}>
          {isRegister && (
            <div className="mb-5">
              <label className="block mb-2 text-[15px] font-medium" style={{ color: 'var(--color-ink)' }}>用户名</label>
              <input type="text" value={displayName} required onChange={(e) => setDisplayName(e.target.value)} style={inputStyle} />
            </div>
          )}
          <div className="mb-5">
            <label className="block mb-2 text-[15px] font-medium" style={{ color: 'var(--color-ink)' }}>邮箱</label>
            <input type="email" value={email} required onChange={(e) => setEmail(e.target.value)} style={inputStyle} />
          </div>
          <div className="mb-7">
            <label className="block mb-2 text-[15px] font-medium" style={{ color: 'var(--color-ink)' }}>密码</label>
            <input type="password" value={password} required minLength={6} onChange={(e) => setPassword(e.target.value)} style={inputStyle} />
          </div>
          {error && (
            <p className="mb-4 text-[15px]" style={{ color: 'var(--color-error)' }}>{error}</p>
          )}
          <button type="submit" disabled={isLoading}
            className="w-full py-4 rounded-[12px] text-[17px] font-medium transition-all hover:brightness-110 active:scale-[0.98]"
            style={{ background: 'var(--color-primary)', color: 'var(--color-on-primary)', cursor: isLoading ? 'not-allowed' : 'pointer', opacity: isLoading ? 0.7 : 1 }}>
            {isLoading ? '请稍候…' : isRegister ? '注册' : '登录'}
          </button>
        </form>

        <p className="text-center mt-6 text-[15px]" style={{ color: 'var(--color-muted)' }}>
          {isRegister ? '已有账号？' : '没有账号？'}
          <button type="button" onClick={() => { setIsRegister(!isRegister); setError(null); }}
            className="ml-1.5 text-[15px] underline"
            style={{ background: 'none', border: 'none', color: 'var(--color-primary)', cursor: 'pointer' }}>
            {isRegister ? '去登录' : '去注册'}
          </button>
        </p>
      </div>
    </div>
  );
}
