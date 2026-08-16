import { useEffect, useState } from 'react';
import CountUp from '../CountUp';
import { userApi, type UserStats } from '../../api/user';

const overlayStyle: React.CSSProperties = {
  position: 'fixed', inset: 0, background: 'rgba(20, 20, 19, 0.4)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 300,
};
const inputStyle: React.CSSProperties = {
  width: '100%', padding: '8px 12px', border: '1px solid var(--color-hairline)',
  borderRadius: 'var(--rounded-md)', font: 'var(--text-body-sm)', color: 'var(--color-ink)',
  outline: 'none', boxSizing: 'border-box', background: 'white',
};
const primaryBtn: React.CSSProperties = {
  padding: '7px 16px', border: 'none', borderRadius: 'var(--rounded-md)',
  background: 'var(--color-primary)', color: 'white', font: 'var(--text-caption)', cursor: 'pointer',
};

function errMsg(e: unknown): string {
  return (e as { response?: { data?: { message?: string } } })?.response?.data?.message || '操作失败';
}

export function PersonalInfoModal({ onClose }: { onClose: () => void }) {
  const [stats, setStats] = useState<UserStats | null>(null);
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [oldPwd, setOldPwd] = useState('');
  const [newPwd, setNewPwd] = useState('');
  const [confirmPwd, setConfirmPwd] = useState('');
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);

  useEffect(() => {
    userApi.getProfile().then((r) => {
      setName(r.data.data.displayName || '');
      setEmail(r.data.data.email || '');
    }).catch(() => {});
    userApi.getStats().then((r) => setStats(r.data.data)).catch(() => {});
  }, []);

  const saveProfile = async () => {
    try {
      await userApi.updateProfile({ displayName: name, email });
      setMsg({ text: '基本信息已保存', ok: true });
    } catch (e) {
      setMsg({ text: errMsg(e), ok: false });
    }
  };

  const savePassword = async () => {
    if (newPwd !== confirmPwd) { setMsg({ text: '两次输入的新密码不一致', ok: false }); return; }
    try {
      await userApi.changePassword({ oldPassword: oldPwd, newPassword: newPwd });
      setMsg({ text: '密码已修改', ok: true });
      setOldPwd(''); setNewPwd(''); setConfirmPwd('');
    } catch (e) {
      setMsg({ text: errMsg(e), ok: false });
    }
  };

  const statCard = (value: number | undefined, label: string): React.ReactNode => (
    <div style={{ flex: 1, padding: '14px 10px', background: 'var(--color-surface-card)', borderRadius: 'var(--rounded-md)', textAlign: 'center' }}>
      <div style={{ fontSize: 28, fontWeight: 700, color: 'var(--color-primary)', lineHeight: 1.1 }}>
        <CountUp to={value ?? 0} duration={1.5} />
      </div>
      <div style={{ fontSize: 12, color: 'var(--color-muted)', marginTop: 6 }}>{label}</div>
    </div>
  );

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={{ width: 480, maxHeight: '86vh', overflowY: 'auto', background: 'var(--color-canvas)', borderRadius: 'var(--rounded-lg)', padding: 22, boxShadow: '0 12px 48px rgba(20,20,19,0.25)' }} onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 14 }}>
          <h2 style={{ margin: 0, font: 'var(--text-title-md)', color: 'var(--color-ink)', flex: 1 }}>个人信息</h2>
          <button style={{ background: 'none', border: 'none', fontSize: 16, cursor: 'pointer', color: 'var(--color-muted)' }} onClick={onClose}>✕</button>
        </div>

        {/* 统计 */}
        <div style={{ display: 'flex', gap: 10, marginBottom: 18 }}>
          {statCard(stats?.imageCount, '生成图片')}
          {statCard(stats?.videoCount, '生成视频')}
          {statCard(stats?.projectCount, '项目总数')}
        </div>

        {/* 基本信息 */}
        <div style={{ marginBottom: 16 }}>
          <h3 style={{ margin: '0 0 10px', font: 'var(--text-body)', color: 'var(--color-ink)', fontSize: 14 }}>基本信息</h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <div>
              <label style={{ fontSize: 11, color: 'var(--color-muted)' }}>名称</label>
              <input style={inputStyle} value={name} onChange={(e) => setName(e.target.value)} placeholder="显示名称" />
            </div>
            <div>
              <label style={{ fontSize: 11, color: 'var(--color-muted)' }}>邮箱</label>
              <input style={inputStyle} value={email} onChange={(e) => setEmail(e.target.value)} placeholder="登录邮箱" />
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button style={primaryBtn} onClick={saveProfile}>保存基本信息</button>
            </div>
          </div>
        </div>

        {/* 修改密码 */}
        <div>
          <h3 style={{ margin: '0 0 10px', font: 'var(--text-body)', color: 'var(--color-ink)', fontSize: 14 }}>修改密码</h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <input style={inputStyle} type="password" value={oldPwd} onChange={(e) => setOldPwd(e.target.value)} placeholder="旧密码" />
            <input style={inputStyle} type="password" value={newPwd} onChange={(e) => setNewPwd(e.target.value)} placeholder="新密码（至少 6 位）" />
            <input style={inputStyle} type="password" value={confirmPwd} onChange={(e) => setConfirmPwd(e.target.value)} placeholder="确认新密码" />
            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button style={primaryBtn} disabled={!oldPwd || !newPwd || !confirmPwd} onClick={savePassword}>修改密码</button>
            </div>
          </div>
        </div>

        {msg && (
          <div style={{ marginTop: 14, padding: '8px 12px', borderRadius: 'var(--rounded-md)', fontSize: 12, background: msg.ok ? 'rgba(204,120,92,0.12)' : 'rgba(200,80,60,0.12)', color: msg.ok ? 'var(--color-primary)' : 'var(--color-error)' }}>
            {msg.text}
          </div>
        )}
      </div>
    </div>
  );
}
