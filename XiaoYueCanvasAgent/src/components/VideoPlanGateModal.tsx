import { useState } from 'react';
import { CircleCheck, Clock, Zap } from 'lucide-react';

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

export function VideoPlanGateModal({ open, count, onClose, onConfirm }: {
  open: boolean;
  count: number;
  onClose: () => void;
  onConfirm: (mode: 'auto') => void;
}) {
  const [mode, setMode] = useState<'auto' | 'plan' | 'confirm'>('auto');
  if (!open) return null;

  return (
    <div className="video-gate-backdrop" role="presentation" onPointerDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <div className="video-gate" role="dialog" aria-modal="true" aria-labelledby="video-gate-title">
        <header className="video-gate__head">
          <strong id="video-gate-title">生成视频计划</strong>
          <small>已勾选 {count} 个分镜</small>
        </header>
        <p className="video-gate__hint">生成视频前需要一次人工介入，选择本次生成方式：</p>
        <div className="video-gate__options">
          {OPTIONS.map((option) => {
            const Icon = option.icon;
            const selected = mode === option.id;
            return (
              <button key={option.id} type="button" disabled={option.disabled}
                className={`video-gate__option ${selected ? 'is-selected' : ''} ${option.disabled ? 'is-disabled' : ''}`}
                onClick={() => setMode(option.id)}>
                <span className="video-gate__option-icon"><Icon size={18} /></span>
                <span className="video-gate__option-copy">
                  <strong>{option.title}</strong>
                  <small>{option.desc}</small>
                </span>
                {option.disabled && option.badge && <span className="video-gate__badge">{option.badge}</span>}
              </button>
            );
          })}
        </div>
        <footer className="video-gate__foot">
          <button type="button" onClick={onClose}>取消</button>
          <button type="button" className="is-primary" disabled={mode !== 'auto'} onClick={() => onConfirm('auto')}>
            开始生成视频
          </button>
        </footer>
      </div>
    </div>
  );
}
