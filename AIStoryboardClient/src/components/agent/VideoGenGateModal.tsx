import { useState } from 'react';
import { Clock, CircleCheck, Zap } from 'lucide-react';

interface GateOption {
  id: 'auto' | 'plan' | 'confirm';
  title: string;
  desc: string;
  icon: typeof Zap;
  disabled?: boolean;
  badge?: string;
}

const OPTIONS: GateOption[] = [
  { id: 'auto', title: '全自动生成视频', desc: '直接进入生视频队列，AI 不介入', icon: Zap },
  { id: 'plan', title: 'AI 智能规划分镜', desc: '由智能体拆解镜头并规划运镜后自动生成', icon: Clock, disabled: true, badge: '待开发' },
  { id: 'confirm', title: '逐镜人工确认', desc: '生成视频计划后逐个镜头确认再生成', icon: CircleCheck, disabled: true, badge: '待开发' },
];

/**
 * 生成视频计划之前的人工介入门禁。
 * 选项一「全自动生成视频」可用并默认选中；其余选项置灰标「待开发」。
 */
export function VideoGenGateModal({ open, count, onClose, onConfirm }: {
  open: boolean;
  count: number;
  onClose: () => void;
  onConfirm: (mode: 'auto') => void;
}) {
  const [mode, setMode] = useState<'auto' | 'plan' | 'confirm'>('auto');
  if (!open) return null;

  return (
    <div
      style={{
        position: 'fixed', inset: 0, zIndex: 120, display: 'flex',
        alignItems: 'center', justifyContent: 'center',
        background: 'rgba(15, 17, 21, 0.4)', padding: 16,
      }}
      onPointerDown={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="video-gate-title"
        style={{
          width: '100%', maxWidth: 520, background: 'white', borderRadius: 16,
          boxShadow: '0 16px 48px rgba(15, 17, 21, 0.24)', padding: 20,
          display: 'flex', flexDirection: 'column', gap: 14,
        }}
      >
        <header style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between' }}>
          <strong id="video-gate-title" style={{ fontSize: 15, color: 'rgb(15, 17, 21)' }}>生成视频计划</strong>
          <small style={{ fontSize: 12, color: 'rgb(152, 158, 168)' }}>已勾选 {count} 个分镜</small>
        </header>
        <p style={{ margin: 0, fontSize: 13, color: 'rgb(96, 100, 108)', lineHeight: 1.7 }}>
          生成视频前需要一次人工介入，选择本次生成方式：
        </p>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {OPTIONS.map((option) => {
            const Icon = option.icon;
            const selected = mode === option.id;
            return (
              <button
                key={option.id}
                type="button"
                disabled={option.disabled}
                onClick={() => setMode(option.id)}
                style={{
                  width: '100%', display: 'flex', alignItems: 'center', gap: 12,
                  padding: '12px 14px', borderRadius: 12, textAlign: 'left',
                  border: selected
                    ? '1.5px solid rgb(65, 118, 230)'
                    : '1px solid rgba(0, 0, 0, 0.08)',
                  background: selected ? 'rgba(65, 118, 230, 0.05)' : 'white',
                  cursor: option.disabled ? 'not-allowed' : 'pointer',
                  opacity: option.disabled ? 0.55 : 1,
                  color: 'rgb(15, 17, 21)',
                }}
              >
                <span style={{ display: 'inline-flex', alignItems: 'center', color: option.disabled ? 'rgb(152, 158, 168)' : selected ? 'rgb(65, 118, 230)' : 'rgb(96, 100, 108)' }}>
                  <Icon size={18} />
                </span>
                <span style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 2 }}>
                  <strong style={{ fontSize: 14 }}>{option.title}</strong>
                  <small style={{ fontSize: 12, color: 'rgb(152, 158, 168)' }}>{option.desc}</small>
                </span>
                {option.disabled && option.badge && (
                  <span style={{
                    fontSize: 11, color: 'rgb(152, 158, 168)', background: 'rgba(0, 0, 0, 0.05)',
                    padding: '2px 8px', borderRadius: 8, whiteSpace: 'nowrap',
                  }}>{option.badge}</span>
                )}
                {selected && !option.disabled && <CircleCheck size={16} style={{ color: 'rgb(65, 118, 230)' }} />}
              </button>
            );
          })}
        </div>
        <footer style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button
            type="button"
            onClick={onClose}
            style={{
              padding: '9px 18px', borderRadius: 10, border: '1px solid rgba(0, 0, 0, 0.08)',
              background: 'transparent', color: 'rgb(96, 100, 108)', fontSize: 14, cursor: 'pointer',
            }}
          >取消</button>
          <button
            type="button"
            disabled={mode !== 'auto'}
            onClick={() => onConfirm('auto')}
            style={{
              padding: '9px 18px', borderRadius: 10, border: 'none', background: 'rgb(65, 118, 230)',
              color: 'white', fontSize: 14, cursor: mode === 'auto' ? 'pointer' : 'not-allowed',
              opacity: mode === 'auto' ? 1 : 0.5,
            }}
          >开始生成视频</button>
        </footer>
      </div>
    </div>
  );
}
