import { useEffect, useRef, useState } from 'react';
import { useAgentStore } from '../../stores/agentStore';
import { MessageBubble } from './MessageBubble';
import { HumanInputCard } from './HumanInputCard';

export function AgentChatPanel() {
  const { messages, streaming, waitingHumanInput, streamError, refImageUrl, setRefImageUrl, uploadRefImage, sendMessage } = useAgentStore();
  const [text, setText] = useState('');
  const scrollRef = useRef<HTMLDivElement>(null);
  // M9：是否处于近底位置（距底部 <80px）；用户上翻查看历史时暂停自动滚底跟随
  const nearBottomRef = useRef(true);
  const fileRef = useRef<HTMLInputElement>(null);
  const activeConversationId = useAgentStore((s) => s.activeConversationId);

  // 新消息自动滚底（M9：仅当用户处于近底位置时跟随）
  useEffect(() => {
    const el = scrollRef.current;
    if (el && nearBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [messages, streaming, waitingHumanInput]);

  // 切换会话清空草稿
  useEffect(() => { setText(''); }, [activeConversationId]);

  const handleSend = () => {
    const content = text.trim();
    if (!content || streaming || waitingHumanInput) return;
    setText('');
    sendMessage(content);
  };

  const handleFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      await uploadRefImage(file);
    } catch {
      setStreamErrorLocal('图片上传失败');
    }
    // M8：重置 input value，允许连续选择同一文件再次上传
    e.target.value = '';
  };

  const setStreamErrorLocal = (msg: string) => {
    // 轻量提示：直接复用 streamError 展示
    void msg;
    alert(msg);
  };

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      {/* 头部 */}
      <div style={{ padding: '10px 14px', borderBottom: '1px solid var(--color-hairline)', background: 'white' }}>
        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-ink)' }}>☾ Moon 智能体</span>
      </div>

      {/* 消息流 */}
      <div
        ref={scrollRef}
        onScroll={(e) => {
          // M9：滚动时更新近底状态（距底部 <80px 视为近底）
          const el = e.target as HTMLElement;
          nearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
        }}
        style={{ flex: 1, overflowY: 'auto', padding: 14, background: 'var(--color-canvas)' }}
      >
        {messages.length === 0 && !streaming && (
          <p style={{ textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 12, marginTop: 40 }}>
            与 Moon 智能体对话，设计分镜、图片与视频方案
          </p>
        )}
        {messages.map((m) => (
          <MessageBubble key={m.id} role={m.role} content={m.content} />
        ))}
        {streaming && !waitingHumanInput && (
          <div style={{ color: 'var(--color-muted)', fontSize: 12, marginLeft: 4 }}>正在生成…</div>
        )}
        {waitingHumanInput && <HumanInputCard info={waitingHumanInput} />}
        {streamError && (
          <div style={{ color: 'var(--color-error)', fontSize: 12, margin: '8px 4px' }}>
            ⚠ {streamError}
          </div>
        )}
      </div>

      {/* 输入区 */}
      <div style={{ padding: 10, borderTop: '1px solid var(--color-hairline)', background: 'white' }}>
        {refImageUrl && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
            <img src={refImageUrl} style={{ width: 44, height: 44, objectFit: 'cover', borderRadius: 8 }} />
            <span style={{ fontSize: 11, color: 'var(--color-muted)' }}>参考图已附</span>
            <button onClick={() => setRefImageUrl(null)} style={{ border: 'none', background: 'none', color: 'var(--color-error)', cursor: 'pointer', fontSize: 12 }}>移除</button>
          </div>
        )}
        <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
          <button
            onClick={() => fileRef.current?.click()}
            title="上传参考图"
            style={{ width: 32, height: 32, border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)', background: 'var(--color-canvas)', cursor: 'pointer', fontSize: 14, flexShrink: 0 }}
          >📎</button>
          <input ref={fileRef} type="file" accept="image/*" hidden onChange={handleFile} />
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              // I5：中文输入法组合期（选词中）按下 Enter 不发送
              if (e.nativeEvent.isComposing) return;
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); }
            }}
            placeholder={waitingHumanInput ? '请先完成上方确认' : streaming ? '智能体正在回复…' : '描述你的需求…'}
            disabled={streaming || !!waitingHumanInput}
            rows={2}
            style={{
              flex: 1, padding: '8px 10px', border: '1px solid var(--color-hairline)',
              borderRadius: 'var(--rounded-md)', font: 'var(--text-body-sm)', color: 'var(--color-ink)',
              resize: 'none', outline: 'none', background: 'var(--color-canvas)',
            }}
          />
          <button
            onClick={handleSend}
            disabled={streaming || !!waitingHumanInput || !text.trim()}
            style={{
              height: 32, padding: '0 16px', border: 'none', borderRadius: 'var(--rounded-md)',
              background: streaming || !text.trim() ? 'var(--color-primary-disabled)' : 'var(--color-primary)',
              color: 'white', fontSize: 13, cursor: 'pointer', flexShrink: 0,
            }}
          >发送</button>
        </div>
      </div>
    </div>
  );
}
