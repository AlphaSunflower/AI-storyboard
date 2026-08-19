import { useRef, useState, useEffect } from 'react';
import { useAgentStore } from '../../stores/agentStore';

/** DeepSeek 设计 token（与 ChatPage 一致） */
export const DS = {
  brand: 'rgb(65, 118, 230)',
  bubble: 'rgb(237, 243, 254)',
  ink: 'rgb(15, 17, 21)',
  textSecondary: 'rgb(84, 85, 87)',
  textCaption: 'rgb(162, 164, 166)',
  border: 'rgba(0, 0, 0, 0.10)',
  hover: 'rgba(38, 49, 72, 0.06)',
};

/**
 * DeepSeek 风格输入卡（780px 悬浮胶囊）：/chat 页 hero 居中与底部输入共用。
 * 逻辑复用 agentStore（参考图/pendingPicUrl/发送/流式禁用）。
 */
export function ChatComposer() {
  const {
    streaming, waitingHumanInput, waitingVideoPlan,
    refImageUrl, setRefImageUrl, uploadRefImage, sendMessage,
    pendingPicUrl, cancelRefine,
  } = useAgentStore();
  const [text, setText] = useState('');
  const fileRef = useRef<HTMLInputElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // 点击"继续完善"后聚焦输入框
  useEffect(() => {
    if (pendingPicUrl) inputRef.current?.focus();
  }, [pendingPicUrl]);

  const busy = streaming || !!waitingHumanInput || !!waitingVideoPlan;

  const handleSend = () => {
    const content = text.trim();
    if (!content || busy) return;
    setText('');
    sendMessage(content);
  };

  const handleFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      await uploadRefImage(file);
    } catch {
      alert('图片上传失败');
    }
    e.target.value = '';
  };

  return (
    <div style={{
      width: '100%', maxWidth: 780, margin: '0 auto',
      border: `1px solid ${DS.border}`, borderRadius: 22,
      background: 'white', boxShadow: '0 2px 12px rgba(0, 0, 0, 0.06)',
      padding: '10px 14px 12px', display: 'flex', flexDirection: 'column', gap: 10,
      boxSizing: 'border-box',
    }}>
      {refImageUrl && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <img src={refImageUrl} style={{ width: 48, height: 48, objectFit: 'cover', borderRadius: 10 }} />
          <span style={{ fontSize: 14, color: DS.textSecondary }}>参考图已附</span>
          <button onClick={() => setRefImageUrl(null)} style={{ border: 'none', background: 'none', color: DS.textSecondary, cursor: 'pointer', fontSize: 14 }}>移除</button>
        </div>
      )}
      {pendingPicUrl && (
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '8px 12px', borderRadius: 12, background: DS.hover, fontSize: 14, color: DS.textSecondary,
        }}>
          <span>📎 已选当前图片作为参考，请输入你想完善的地方</span>
          <button onClick={() => cancelRefine()} style={{ border: 'none', background: 'none', color: DS.textSecondary, cursor: 'pointer', fontSize: 14 }}>✕ 取消</button>
        </div>
      )}
      <textarea
        ref={inputRef}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          // I5：中文输入法组合期（选词中）按下 Enter 不发送
          if (e.nativeEvent.isComposing) return;
          if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); }
        }}
        placeholder={waitingHumanInput || waitingVideoPlan ? '请先完成上方确认' : streaming ? '智能体正在回复…' : pendingPicUrl ? '例如：把色调调暖一点、换成日系风格…' : '描述你的需求…'}
        disabled={busy}
        rows={2}
        style={{
          border: 'none', outline: 'none', resize: 'none', background: 'transparent',
          fontSize: 16, lineHeight: 1.6, color: DS.ink, fontFamily: 'inherit',
        }}
      />
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <button
          onClick={() => fileRef.current?.click()}
          title="上传参考图"
          style={{
            width: 36, height: 36, borderRadius: 10, border: 'none', background: 'transparent',
            fontSize: 17, cursor: 'pointer', color: DS.textSecondary, flexShrink: 0,
          }}
        >📎</button>
        <input ref={fileRef} type="file" accept="image/*" hidden onChange={handleFile} />
        <button
          onClick={handleSend}
          disabled={busy || !text.trim()}
          style={{
            height: 38, padding: '0 22px', borderRadius: 12, border: 'none',
            background: DS.brand, color: 'white', fontSize: 15, fontWeight: 500,
            cursor: busy || !text.trim() ? 'not-allowed' : 'pointer', opacity: busy || !text.trim() ? 0.45 : 1,
            transition: 'background 0.15s',
          }}
          onMouseEnter={(e) => { if (!busy && text.trim()) (e.target as HTMLElement).style.background = 'rgb(86, 134, 254)'; }}
          onMouseLeave={(e) => { (e.target as HTMLElement).style.background = DS.brand; }}
        >发送</button>
      </div>
    </div>
  );
}
