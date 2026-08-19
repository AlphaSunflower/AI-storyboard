import { useEffect, useRef } from 'react';
import { useAgentStore } from '../stores/agentStore';
import { MessageBubble } from './MessageBubble';
import { HumanInputCard } from './HumanInputCard';
import { AgentInputBox } from './AgentInputBox';

export function AgentConversationArea() {
  const {
    messages, streaming, waitingHumanInput, streamError, workflowHint,
    conversations, activeConversationId,
  } = useAgentStore();

  const scrollRef = useRef<HTMLDivElement>(null);
  const nearBottomRef = useRef(true);

  const currentTitle = conversations.find((c) => c.id === activeConversationId)?.title ?? '未选择对话';

  useEffect(() => {
    const el = scrollRef.current;
    if (el && nearBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [messages, streaming, waitingHumanInput]);

  return (
    <div className="flex-1 flex flex-col min-w-0">
      {/* 头部 */}
      <div
        className="px-4 py-3 flex items-center justify-between"
        style={{ borderBottom: '1px solid var(--color-hairline)', background: 'white' }}
      >
        <span
          title={currentTitle}
          className="text-sm font-semibold truncate"
          style={{ color: 'var(--color-ink)' }}
        >
          {currentTitle}
        </span>
      </div>

      {/* 消息流 */}
      <div
        ref={scrollRef}
        onScroll={(e) => {
          const el = e.target as HTMLElement;
          nearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
        }}
        className="flex-1 overflow-y-auto p-4"
        style={{ background: 'var(--color-canvas)' }}
      >
        {messages.length === 0 && !streaming && (
          <p className="text-center text-sm mt-10" style={{ color: 'var(--color-muted)' }}>
            与 AI Agent 对话，开始创作
          </p>
        )}
        {messages.map((m) => (
          <MessageBubble
            key={m.id}
            role={m.role}
            content={m.content}
            streaming={streaming && m.role === 'assistant' && m.id === messages[messages.length - 1]?.id}
          />
        ))}
        {streaming && !waitingHumanInput && (
          <div className="flex items-center gap-1.5 text-xs" style={{ color: 'var(--color-muted)' }}>
            <span>{workflowHint || '正在生成'}</span>
            <span className="inline-flex gap-0.5">
              {[0, 1, 2].map((i) => (
                <span
                  key={i}
                  className="rounded-full"
                  style={{
                    width: 4, height: 4,
                    background: 'var(--color-muted)',
                    animation: 'thinkingDot 1.2s ease-in-out infinite',
                    animationDelay: `${i * 0.18}s`,
                  }}
                />
              ))}
            </span>
            <style>{`
              @keyframes thinkingDot {
                0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
                30% { transform: translateY(-4px); opacity: 1; }
              }
            `}</style>
          </div>
        )}
        {waitingHumanInput && <HumanInputCard info={waitingHumanInput} />}
        {streamError && (
          <div className="text-xs mt-2" style={{ color: '#c0392b' }}>
            ⚠ {streamError}
          </div>
        )}
      </div>

      {/* 输入区 */}
      <AgentInputBox />
    </div>
  );
}
