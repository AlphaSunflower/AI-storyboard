import { memo, useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ChevronDown, Layers } from 'lucide-react';
import type { ChannelDef, ModelOption } from '../canvas/modelRegistry';

/**
 * 通道▼|模型▼ 双下拉（对齐参考 ChannelModelSelector）。
 * 仅一个通道时显示为只读标签（不弹下拉）。下拉 portal 到 body，点击外部关闭。
 * 配色走当前深墨绿+acid 主题（不照抄参考暖色）。
 */

interface Props {
  channelId: string;
  modelId: string;
  channels: ChannelDef[];
  onChannelChange: (id: string) => void;
  onModelChange: (id: string) => void;
}

const DROPDOWN_STYLE: React.CSSProperties = {
  position: 'fixed',
  backgroundColor: '#1c2a25',
  border: '1px solid rgba(215,255,84,.16)',
  borderRadius: '8px',
  boxShadow: '0 4px 12px rgba(0,0,0,.5)',
  padding: '4px',
  zIndex: 999,
  minWidth: '140px',
};

const ITEM_STYLE = (active: boolean): React.CSSProperties => ({
  display: 'flex',
  width: '100%',
  padding: '6px 10px',
  border: 'none',
  borderRadius: '4px',
  backgroundColor: active ? 'rgba(215,255,84,.12)' : 'transparent',
  color: active ? 'var(--acid)' : 'rgba(238,240,223,.85)',
  fontSize: '12px',
  cursor: 'pointer',
  textAlign: 'left',
  alignItems: 'center',
  gap: '6px',
});

const Trigger = ({ open, onClick, label }: { open: boolean; onClick: () => void; label: string }) => (
  <span
    className="flex items-center gap-1 cursor-pointer nodrag"
    onClick={onClick}
    style={{ color: 'rgba(238,240,223,.75)', fontSize: '12px', fontWeight: 500, whiteSpace: 'nowrap' }}
  >
    {label}
    <ChevronDown size={11} style={{ transform: open ? 'rotate(180deg)' : 'none', transition: 'transform .15s' }} />
  </span>
);

export const ChannelModelSelector = memo(function ChannelModelSelector({
  channelId,
  modelId,
  channels,
  onChannelChange,
  onModelChange,
}: Props) {
  const [openMenu, setOpenMenu] = useState<'channel' | 'model' | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const channelTriggerRef = useRef<HTMLDivElement>(null);
  const modelTriggerRef = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ left: number; top: number }>({ left: 0, top: 0 });

  const currentChannel = channels.find((c) => c.id === channelId) ?? channels[0];
  const currentModel = currentChannel?.models.find((m) => m.id === modelId) ?? currentChannel?.models[0];

  const placeMenu = useCallback((el: HTMLElement | null) => {
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const spaceBelow = window.innerHeight - rect.bottom;
    // 空间不足则向上弹
    if (spaceBelow < 180 && rect.top > 200) setPos({ left: rect.left, top: rect.top - 4 });
    else setPos({ left: rect.left, top: rect.bottom + 4 });
  }, []);

  // 点击外部关闭
  useEffect(() => {
    const outside = (e: MouseEvent) => {
      const t = e.target as HTMLElement;
      if (t.closest?.('[data-portal-dropdown]')) return;
      if (containerRef.current && !containerRef.current.contains(t)) setOpenMenu(null);
    };
    document.addEventListener('mousedown', outside);
    return () => document.removeEventListener('mousedown', outside);
  }, []);

  // 只有一个通道 → 只读标签
  const singleChannel = channels.length <= 1;

  return (
    <div ref={containerRef} className="flex items-center gap-1.5 nodrag" style={{ position: 'relative', flexShrink: 0 }}>
      <div ref={channelTriggerRef} className="flex items-center gap-1">
        {singleChannel ? (
          <span style={{ color: 'rgba(238,240,223,.55)', fontSize: '12px' }} title={currentChannel?.label}>{currentChannel?.label}</span>
        ) : (
          <Trigger open={openMenu === 'channel'} onClick={() => { placeMenu(channelTriggerRef.current); setOpenMenu(openMenu === 'channel' ? null : 'channel'); }} label={currentChannel?.label ?? '通道'} />
        )}
      </div>
      <span style={{ color: 'rgba(215,255,84,.25)', fontSize: '12px' }}>|</span>
      <div ref={modelTriggerRef} className="flex items-center gap-1">
        <Trigger open={openMenu === 'model'} onClick={() => { placeMenu(modelTriggerRef.current); setOpenMenu(openMenu === 'model' ? null : 'model'); }} label={currentModel?.label ?? '模型'} />
      </div>

      {(openMenu && channels.length > 1) && createPortal(
        <div data-portal-dropdown style={{ ...DROPDOWN_STYLE, left: pos.left, top: pos.top }} onClick={(e) => e.stopPropagation()}>
          {openMenu === 'channel' && channels.map((c) => (
            <button key={c.id} style={ITEM_STYLE(c.id === channelId)} onClick={() => { onChannelChange(c.id); setOpenMenu(null); }}>
              {c.label}
            </button>
          ))}
          {openMenu === 'model' && (currentChannel?.models ?? []).map((m) => (
            <button key={m.id} style={ITEM_STYLE(m.id === modelId)} onClick={() => { onModelChange(m.id); setOpenMenu(null); }}>
              {m.label}
            </button>
          ))}
          {(currentChannel?.models ?? []).length === 0 && <div style={{ padding: '8px 10px', fontSize: '12px', color: 'rgba(238,240,223,.5)' }}>该通道暂无模型</div>}
        </div>,
        document.body
      )}
      <Layers size={11} style={{ color: 'rgba(238,240,223,.35)', flexShrink: 0 }} />
    </div>
  );
});

export type { ModelOption };
