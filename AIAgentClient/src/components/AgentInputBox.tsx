import { useState, useRef, useEffect } from 'react';
import { useAgentStore } from '../stores/agentStore';

export function AgentInputBox() {
  const { sendMessage, streaming, waitingHumanInput, activeConversationId } = useAgentStore();
  const [text, setText] = useState('');
  const inputRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => { setText(''); }, [activeConversationId]);

  const handleSend = () => {
    const content = text.trim();
    if (!content || streaming || waitingHumanInput) return;
    setText('');
    sendMessage(content);
  };

  return (
    <div
      className="px-4 py-3"
      style={{ borderTop: '1px solid var(--color-hairline)', background: 'white' }}
    >
      <div className="flex gap-2 items-end">
        <textarea
          ref={inputRef}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.nativeEvent.isComposing) return;
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); }
          }}
          placeholder={waitingHumanInput ? '请先完成上方确认' : streaming ? '智能体正在回复…' : '描述你的需求…'}
          disabled={streaming || !!waitingHumanInput}
          rows={2}
          className="flex-1 px-3 py-2 text-sm resize-none outline-none"
          style={{
            border: '1px solid var(--color-hairline)',
            borderRadius: 'var(--rounded-md)',
            color: 'var(--color-ink)',
            background: 'var(--color-canvas)',
          }}
        />
        <button
          onClick={handleSend}
          disabled={streaming || !!waitingHumanInput || !text.trim()}
          className="px-4 py-2 text-sm font-medium rounded-md transition-opacity"
          style={{
            background: 'var(--color-primary)',
            color: 'var(--color-on-primary)',
            opacity: streaming || !!waitingHumanInput || !text.trim() ? 0.5 : 1,
            cursor: streaming || !!waitingHumanInput || !text.trim() ? 'not-allowed' : 'pointer',
          }}
        >
          发送
        </button>
      </div>
    </div>
  );
}
