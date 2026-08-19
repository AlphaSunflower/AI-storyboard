import { useEffect, useRef, useState } from 'react';
import { useAgentStore } from '../../stores/agentStore';
import { MessageBubble } from './MessageBubble';
import { HumanInputCard } from './HumanInputCard';
import { ConfirmResultCard } from './ConfirmResultCard';
import { VideoPlanCard } from './VideoPlanCard';

export function AgentConversationArea() {
  const {
    messages, streaming, waitingHumanInput, waitingVideoPlan, confirmResult,
    conversations, activeConversationId, renameConversation, workflowHint, streamError,
  } = useAgentStore();

  const [editing, setEditing] = useState(false);
  const [editTitle, setEditTitle] = useState('');
  const scrollRef = useRef<HTMLDivElement>(null);
  const nearBottomRef = useRef(true);

  const currentTitle = conversations.find((c) => c.id === activeConversationId)?.title ?? '未选择对话';

  // 新消息自动滚底（仅近底时跟随）
  useEffect(() => {
    const el = scrollRef.current;
    if (el && nearBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [messages, streaming, waitingHumanInput, waitingVideoPlan]);

  const startRename = () => {
    if (!activeConversationId) return;
    setEditTitle(currentTitle);
    setEditing(true);
  };

  const commitRename = () => {
    const trimmed = editTitle.trim();
    if (trimmed && trimmed !== currentTitle && activeConversationId) {
      renameConversation(activeConversationId, trimmed);
    }
    setEditing(false);
  };

  return (
    <div className="flex flex-col flex-1 min-h-0">
      {/* 标题栏 */}
      <div className="flex items-center justify-between px-4 py-2.5 border-b border-[var(--color-hairline)] bg-white">
        {editing ? (
          <input
            autoFocus
            value={editTitle}
            onChange={(e) => setEditTitle(e.target.value)}
            onBlur={commitRename}
            onKeyDown={(e) => { if (e.key === 'Enter') commitRename(); if (e.key === 'Escape') setEditing(false); }}
            className="flex-1 min-w-0 text-sm font-semibold text-[var(--color-ink)] bg-[var(--color-canvas)] border border-[var(--color-hairline)] rounded px-1.5 py-0.5 outline-none"
          />
        ) : (
          <button
            onClick={startRename}
            title="点击重命名"
            className="flex-1 min-w-0 text-left text-sm font-semibold text-[var(--color-ink)] truncate bg-transparent border-none cursor-pointer p-0 hover:opacity-80"
          >
            {currentTitle}
          </button>
        )}
        <span className="ml-2 text-[var(--color-muted)] text-xs select-none">▾</span>
      </div>

      {/* 消息滚动区 */}
      <div
        ref={scrollRef}
        onScroll={(e) => {
          const el = e.target as HTMLElement;
          nearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
        }}
        className="flex-1 min-h-0 overflow-y-auto p-4 bg-[var(--color-canvas)]"
      >
        <div className="max-w-3xl mx-auto">
          {/* 空状态 */}
          {messages.length === 0 && !streaming && (
            <p className="text-center text-[var(--color-muted)] text-sm mt-10 opacity-60">
              你好，有什么可以帮你的？
            </p>
          )}

          {/* 消息列表 */}
          {messages.map((m) => (
            <MessageBubble
              key={m.id}
              role={m.role}
              content={m.content}
              streaming={streaming && m.role === 'assistant' && m.id === messages[messages.length - 1]?.id}
            />
          ))}

          {/* 流式进度提示 */}
          {streaming && !waitingHumanInput && (
            <div className="flex items-center gap-1.5 text-[var(--color-muted)] text-xs ml-1">
              <span>{workflowHint || '正在生成'}</span>
              <span className="inline-flex gap-0.5">
                {[0, 1, 2].map((i) => (
                  <span
                    key={i}
                    className="w-[3px] h-[3px] rounded-full bg-[var(--color-muted)] animate-[thinkingDot_1.2s_ease-in-out_infinite]"
                    style={{ animationDelay: `${i * 0.18}s` }}
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

          {/* 卡片区域 */}
          {waitingHumanInput && <HumanInputCard info={waitingHumanInput} />}
          {waitingVideoPlan && <VideoPlanCard info={waitingVideoPlan} />}
          {confirmResult && <ConfirmResultCard />}

          {/* 错误提示 */}
          {streamError && (
            <div className="text-[var(--color-error)] text-xs mt-2 ml-1">⚠ {streamError}</div>
          )}
        </div>
      </div>
    </div>
  );
}
