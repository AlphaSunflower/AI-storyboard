import { useState, useRef, useEffect } from 'react';
import gsap from 'gsap';
import { useAgentStore } from '../stores/agentStore';
import { ArrowUp, Paperclip, Square, X } from 'lucide-react';

export function AgentInputBox() {
  const {
    sendMessage, streaming, waitingHumanInput, activeConversationId, stopGenerate,
    refImageUrl, setRefImageUrl, uploadRefImage, pendingPicUrl, cancelRefine,
  } = useAgentStore();
  const [text, setText] = useState('');
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => { setText(''); }, [activeConversationId]);
  useEffect(() => {
    const el = inputRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 180) + 'px';
  }, [text]);

  const handleSend = () => {
    const content = text.trim();
    if (!content || streaming || waitingHumanInput) return;
    if (btnRef.current) gsap.fromTo(btnRef.current, { scale: 0.88 }, { scale: 1, duration: 0.2, ease: 'back.out(2.5)' });
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
    // 重置 input value,允许连续选择同一文件再次上传
    e.target.value = '';
  };

  const disabled = streaming || !!waitingHumanInput;
  const empty = !text.trim();

  return (
    <div className="chat-input-wrap">
      {/* 继续完善提示条:pendingPicUrl 暂存时显示,✕ 取消 */}
      {pendingPicUrl && (
        <div className="flex items-center gap-2 mb-3 px-5 py-2.5 rounded-[12px]"
          style={{ background: 'var(--color-surface-soft)', border: '1px solid var(--color-border)' }}>
          <span className="text-[14px]" style={{ color: 'var(--color-body)' }}>已暂存参考图,输入完善需求后将按此图继续</span>
          <button onClick={cancelRefine} aria-label="取消继续完善"
            className="ml-auto p-1 rounded-lg hover:bg-white transition-colors"
            style={{ border: 'none', background: 'none', color: 'var(--color-muted)', cursor: 'pointer' }}>
            <X size={14} />
          </button>
        </div>
      )}
      <div className="chat-input-shell">
        {/* 参考图上传(图改图/图生视频入口) */}
        <button onClick={() => fileRef.current?.click()} disabled={disabled}
          title="上传参考图(图改图/图生视频)"
          style={{
            border: 'none', background: 'none', color: refImageUrl ? 'var(--color-primary)' : 'var(--color-muted)',
            cursor: disabled ? 'not-allowed' : 'pointer', padding: 8, flexShrink: 0, opacity: disabled ? 0.4 : 1,
          }}>
          <Paperclip size={20} strokeWidth={2} />
        </button>
        <input ref={fileRef} type="file" accept="image/*" onChange={handleFile} style={{ display: 'none' }} />
        {/* 参考图缩略图 + 移除 */}
        {refImageUrl && (
          <div className="relative flex-shrink-0" style={{ width: 40, height: 40 }}>
            <img src={refImageUrl} alt="参考图"
              style={{ width: 40, height: 40, borderRadius: 10, objectFit: 'cover', border: '1px solid var(--color-border)' }} />
            <button onClick={() => setRefImageUrl(null)} aria-label="移除参考图"
              className="absolute -top-1.5 -right-1.5 p-0.5 rounded-full"
              style={{ border: '1px solid var(--color-border)', background: 'white', color: 'var(--color-muted)', cursor: 'pointer', lineHeight: 1 }}>
              <X size={11} />
            </button>
          </div>
        )}
        <textarea ref={inputRef} value={text} onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.nativeEvent.isComposing) return;
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); }
          }}
          placeholder={waitingHumanInput ? '请先完成上方确认' : streaming ? '智能体正在回复…' : '描述你的需求…'}
          disabled={disabled} rows={1}
          className="flex-1 text-[18px] resize-none outline-none bg-transparent leading-[1.6]"
          style={{ color: 'var(--color-ink)', minHeight: 44, maxHeight: 180 }} />
        {/* 流式中:发送按钮切换为「停止生成」(墨色方块),点击中断 SSE 保留已收内容 */}
        {streaming ? (
          <button onClick={stopGenerate} title="停止生成" className="chat-input-stop">
            <Square size={16} fill="currentColor" strokeWidth={0} />
          </button>
        ) : (
          <button ref={btnRef} onClick={handleSend} disabled={disabled || empty}
            className="chat-input-send"
            style={{
              background: disabled || empty ? 'var(--color-primary-disabled)' : 'var(--color-primary)',
              color: 'var(--color-on-primary)',
              cursor: disabled || empty ? 'not-allowed' : 'pointer',
            }}>
            <ArrowUp size={20} strokeWidth={2.5} />
          </button>
        )}
      </div>
      <p className="chat-input-hint">Enter 发送，Shift+Enter 换行</p>
    </div>
  );
}
