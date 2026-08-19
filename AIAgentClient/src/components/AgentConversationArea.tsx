import { useEffect, useRef, useState } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useAgentStore } from '../stores/agentStore';
import { MessageBubble } from './MessageBubble';
import { HumanInputCard } from './HumanInputCard';
import { VideoPlanCard } from './VideoPlanCard';
import { ConfirmResultCard } from './ConfirmResultCard';
import { AgentInputBox } from './AgentInputBox';
import { ArrowDown, Moon } from 'lucide-react';

export function AgentConversationArea() {
  const {
    messages, streaming, waitingHumanInput, waitingVideoPlan, streamError, workflowHint,
    conversations, activeConversationId,
  } = useAgentStore();

  const scrollRef = useRef<HTMLDivElement>(null);
  const nearBottomRef = useRef(true);
  const [nearBottom, setNearBottom] = useState(true);
  const emptyRef = useRef<HTMLDivElement>(null);

  const currentTitle = conversations.find((c) => c.id === activeConversationId)?.title ?? '未选择对话';

  useEffect(() => {
    const el = scrollRef.current;
    if (el && nearBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [messages, streaming, waitingHumanInput, waitingVideoPlan]);

  useGSAP(() => {
    if (!emptyRef.current || messages.length > 0) return;
    gsap.fromTo(emptyRef.current, { y: 12, opacity: 0 }, { y: 0, opacity: 1, duration: 0.35, ease: 'power2.out', delay: 0.1 });
  }, { dependencies: [messages.length], scope: emptyRef });

  return (
    <div className="flex-1 flex flex-col min-w-0 relative" style={{ background: 'var(--color-canvas)' }}>
      <header className="chat-header flex items-center justify-between">
        <span className="chat-header-title truncate">{currentTitle}</span>
        {streaming && <span className="chat-header-hint animate-pulse">{workflowHint || '生成中…'}</span>}
      </header>

      <div ref={scrollRef}
        onScroll={(e) => {
          const el = e.target as HTMLElement;
          const nb = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
          nearBottomRef.current = nb;
          setNearBottom(nb);
        }}
        className="chat-scroll">

        {messages.length === 0 && !streaming && !waitingHumanInput && !waitingVideoPlan ? (
          <div ref={emptyRef} className="flex flex-col items-center justify-center h-full py-16">
            <div className="w-24 h-24 rounded-[32px] flex items-center justify-center mb-10" style={{ background: 'var(--color-surface-card)' }}>
              <Moon size={42} style={{ color: 'var(--color-primary)' }} />
            </div>
            <h2 className="text-[30px] font-semibold mb-4 tracking-tight" style={{ color: 'var(--color-ink)' }}>Moon 智能体</h2>
            <p className="text-[17px] text-center max-w-sm leading-relaxed" style={{ color: 'var(--color-muted)' }}>
              与 AI Agent 对话，开始创作你的分镜故事
            </p>
            <div className="flex flex-wrap gap-4 mt-12 justify-center">
              {['帮我写分镜', '生成图片', '制作视频'].map((hint) => (
                <button key={hint} onClick={() => useAgentStore.getState().sendMessage(hint)}
                  className="px-7 py-3.5 text-[16px] rounded-full transition-all hover:scale-[1.02] active:scale-[0.98] hover:border-[var(--color-primary)]"
                  style={{ background: 'var(--color-surface-card)', color: 'var(--color-body)', border: '1px solid var(--color-border)' }}>
                  {hint}
                </button>
              ))}
            </div>
          </div>
        ) : (
          <div className="chat-column">
            {messages.map((m) => (
              <MessageBubble key={m.id} message={m}
                streaming={streaming && m.role === 'assistant' && m.id === messages[messages.length - 1]?.id} />
            ))}

            {streaming && !waitingHumanInput && !waitingVideoPlan && !messages.some((m) => m.role === 'assistant' && m.content) && (
              <div className="flex items-center gap-3 py-5">
                <div className="flex gap-1.5">
                  {[0, 1, 2].map((i) => (
                    <span key={i} className="rounded-full" style={{
                      width: 7, height: 7, background: 'var(--color-primary)',
                      animation: 'dot-bounce 1.2s ease-in-out infinite', animationDelay: `${i * 0.15}s`,
                    }} />
                  ))}
                </div>
                <span className="text-[15px]" style={{ color: 'var(--color-muted)' }}>{workflowHint || '正在思考…'}</span>
                <style>{`@keyframes dot-bounce { 0%,60%,100%{transform:translateY(0);opacity:.3} 30%{transform:translateY(-5px);opacity:1} }`}</style>
              </div>
            )}

            {waitingHumanInput && <HumanInputCard info={waitingHumanInput} />}
            {waitingVideoPlan && <VideoPlanCard info={waitingVideoPlan} />}
            <ConfirmResultCard />

            {streamError && (
              <div className="py-3 px-4 rounded-xl text-[15px] mt-2"
                style={{ background: 'rgba(198,69,69,0.06)', color: 'var(--color-error)', border: '1px solid rgba(198,69,69,0.1)' }}>
                {streamError}
              </div>
            )}
          </div>
        )}
      </div>

      {/* 离开底部后显示「回到底部」悬浮按钮(置于滚动容器外,避免被 overflow 裁剪) */}
      {!nearBottom && messages.length > 0 && (
        <button
          onClick={() => scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })}
          aria-label="回到底部"
          className="absolute right-8 flex items-center justify-center rounded-full transition-transform hover:scale-105 active:scale-95"
          style={{
            width: 40, height: 40, background: 'white', border: '1px solid var(--color-border)',
            boxShadow: '0 2px 10px rgba(20,20,19,0.12)', cursor: 'pointer', color: 'var(--color-muted)',
            bottom: 150, zIndex: 10,
          }}>
          <ArrowDown size={18} />
        </button>
      )}

      <AgentInputBox />
    </div>
  );
}
