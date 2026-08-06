import { useEffect, useRef, useState } from 'react';
import { useAgentStore } from '../../stores/agentStore';
import { agentApi } from '../../api/agent';
import { MessageBubble } from './MessageBubble';
import { HumanInputCard } from './HumanInputCard';
import { ConfirmResultCard } from './ConfirmResultCard';
import { AgentAssetsModal } from './AgentAssetsPanel';

export function AgentChatPanel() {
  const { messages, streaming, waitingHumanInput, streamError, refImageUrl, setRefImageUrl, uploadRefImage, sendMessage, clearMessages, confirmResult, pendingPicUrl, cancelRefine, assets, loadAssets, conversations, activeConversationId } = useAgentStore();
  const [text, setText] = useState('');
  const [confirmClear, setConfirmClear] = useState(false);
  // 提示词优化状态（组件本地）：优化中禁发送；完成自动覆盖输入框原文；失败保持原文轻提示
  const [optimizing, setOptimizing] = useState(false);
  const [optimizeError, setOptimizeError] = useState('');
  // 产出素材弹窗（文件夹图标入口，素材不再常驻底部）
  const [assetsOpen, setAssetsOpen] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  // M9：是否处于近底位置（距底部 <80px）；用户上翻查看历史时暂停自动滚底跟随
  const nearBottomRef = useRef(true);
  const fileRef = useRef<HTMLInputElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // 新消息自动滚底（M9：仅当用户处于近底位置时跟随）
  useEffect(() => {
    const el = scrollRef.current;
    if (el && nearBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [messages, streaming, waitingHumanInput]);

  // 切换会话清空草稿
  useEffect(() => { setText(''); }, [activeConversationId]);

  // 点击"继续完善"后：聚焦输入框，引导用户输入完善需求（不自动发送）
  useEffect(() => {
    if (pendingPicUrl) {
      inputRef.current?.focus();
    }
  }, [pendingPicUrl]);

  const handleSend = () => {
    const content = text.trim();
    if (!content || streaming || waitingHumanInput) return;
    setText('');
    sendMessage(content);
  };

  /**
   * 提示词优化：草稿 → LLM 优化 → 自动覆盖输入框原文。
   * 优化过程中 optimizing 置位：发送按钮与优化按钮同时禁用（用户明确要求）；
   * 失败保持原文，仅轻提示，不打断输入。
   */
  const handleOptimize = async () => {
    const content = text.trim();
    if (content.length < 6 || streaming || waitingHumanInput || optimizing) return;
    setOptimizing(true);
    setOptimizeError('');
    try {
      const res = await agentApi.optimizePrompt(content);
      const optimized = res.data.data?.optimized;
      if (optimized) {
        setText(optimized); // 优化完成自动覆盖输入框原文
      } else {
        setOptimizeError('优化结果为空，请重试');
      }
    } catch {
      setOptimizeError('优化失败，请重试');
    } finally {
      setOptimizing(false);
    }
  };

  // 当前会话标题：对话窗口顶部展示；无会话时占位
  const currentTitle = conversations.find((c) => c.id === activeConversationId)?.title ?? '未选择对话';

  // 底部输入栏高度（可拖拽上下伸缩，min 90 / max 40vh）
  const [inputAreaHeight, setInputAreaHeight] = useState(120);
  const dragStartYRef = useRef<number | null>(null);
  const dragStartHRef = useRef(0);

  // 拖拽把手：mousedown 记录起点 → mousemove 计算增量（向上拖高度增大）→ clamp 上下限
  const startInputDrag = (e: React.MouseEvent) => {
    e.preventDefault();
    dragStartYRef.current = e.clientY;
    dragStartHRef.current = inputAreaHeight;
    const onMove = (ev: MouseEvent) => {
      if (dragStartYRef.current === null) return;
      const next = dragStartHRef.current + (dragStartYRef.current - ev.clientY);
      const maxH = Math.round(window.innerHeight * 0.4); // 40vh 上限
      setInputAreaHeight(Math.max(90, Math.min(maxH, next))); // 上下限 clamp
    };
    const onUp = () => {
      dragStartYRef.current = null;
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
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
      <div style={{ padding: '10px 14px', flexShrink: 0, borderBottom: '1px solid var(--color-hairline)', background: 'white', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        {/* 当前会话标题（☾ Moon 智能体标题已迁移至会话栏顶部） */}
        <span
          title={currentTitle}
          style={{
            flex: 1, minWidth: 0, fontSize: 13, fontWeight: 600, color: 'var(--color-ink)',
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}
        >
          {currentTitle}
        </span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          {/* 文件夹图标：查看当前对话的产出素材（弹窗） */}
          <button
            onClick={() => { setAssetsOpen(true); void loadAssets(); }}
            disabled={!activeConversationId}
            title="查看当前对话的产出素材"
            style={{
              border: 'none', background: 'none', color: 'var(--color-muted)',
              fontSize: 12, cursor: activeConversationId ? 'pointer' : 'not-allowed',
              padding: '2px 6px', borderRadius: 4, opacity: activeConversationId ? 1 : 0.4,
            }}
          >
            📁 产出素材{assets && assets.total > 0 ? ` (${assets.total})` : ''}
          </button>
          <button
            onClick={() => setConfirmClear(true)}
            disabled={streaming || !!waitingHumanInput || !activeConversationId || messages.length === 0}
            title={streaming || waitingHumanInput ? '生成进行中，暂不可清除' : '清除当前对话的聊天记录（AI 上下文重置）'}
            style={{
              border: 'none', background: 'none', color: 'var(--color-muted)',
              fontSize: 11, cursor: 'pointer', padding: '2px 6px', borderRadius: 4,
              opacity: streaming || !!waitingHumanInput || !activeConversationId || messages.length === 0 ? 0.4 : 1,
            }}
          >🧹 清除聊天记录</button>
        </div>
      </div>

      {/* 消息流 */}
      <div
        ref={scrollRef}
        onScroll={(e) => {
          // M9：滚动时更新近底状态（距底部 <80px 视为近底）
          const el = e.target as HTMLElement;
          nearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
        }}
        style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: 14, background: 'var(--color-canvas)' }}
      >
        {messages.length === 0 && !streaming && (
          <p style={{ textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 12, marginTop: 40 }}>
            与 Moon 智能体对话，设计分镜、图片与视频方案
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
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--color-muted)', fontSize: 12, marginLeft: 4 }}>
            <span>正在生成</span>
            {/* C 组：思考中三点依次跳动 */}
            <span style={{ display: 'inline-flex', gap: 2 }}>
              {[0, 1, 2].map((i) => (
                <span
                  key={i}
                  style={{
                    width: 3, height: 3, borderRadius: '50%',
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
        {confirmResult && <ConfirmResultCard />}
        {streamError && (
          <div style={{ color: 'var(--color-error)', fontSize: 12, margin: '8px 4px' }}>
            ⚠ {streamError}
          </div>
        )}
      </div>

      {/* 输入区（可拖拽上下伸缩，min 90 / max 40vh） */}
      <div style={{ padding: '10px 10px 0', flexShrink: 0, borderTop: '1px solid var(--color-hairline)', background: 'white' }}>
        {/* 拖拽把手：上下伸缩 */}
        <div
          onMouseDown={startInputDrag}
          title="拖拽调整输入区高度"
          style={{ height: 4, margin: '-10px -10px 6px', cursor: 'row-resize', background: 'transparent' }}
          onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--color-primary)'; }}
          onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
        />
        <div style={{ height: inputAreaHeight, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        {optimizeError && (
          <p style={{ margin: '0 0 6px', fontSize: 11, color: 'var(--color-error)' }}>⚠ {optimizeError}</p>
        )}
        {refImageUrl && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
            <img src={refImageUrl} style={{ width: 44, height: 44, objectFit: 'cover', borderRadius: 8 }} />
            <span style={{ fontSize: 11, color: 'var(--color-muted)' }}>参考图已附</span>
            <button onClick={() => setRefImageUrl(null)} style={{ border: 'none', background: 'none', color: 'var(--color-error)', cursor: 'pointer', fontSize: 12 }}>移除</button>
          </div>
        )}
        {/* 继续完善提示条：点击"继续完善"后暂存参考图，等待用户输入完善需求 */}
        {pendingPicUrl && (
          <div
            style={{
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              padding: '6px 10px', marginBottom: 8, borderRadius: 'var(--rounded-md)',
              background: 'var(--color-primary-soft, #fdf1ec)', border: '1px solid var(--color-hairline)',
              fontSize: 12, color: 'var(--color-muted)',
            }}
          >
            <span>📎 已选当前图片作为参考，请输入你想完善的地方</span>
            <button
              onClick={() => cancelRefine()}
              style={{ border: 'none', background: 'none', color: 'var(--color-muted)', cursor: 'pointer', fontSize: 12, marginLeft: 8 }}
            >
              ✕ 取消
            </button>
          </div>
        )}
        <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end', flex: 1, minHeight: 0 }}>
          <button
            onClick={() => fileRef.current?.click()}
            title="上传参考图"
            style={{ width: 32, height: 32, border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)', background: 'var(--color-canvas)', cursor: 'pointer', fontSize: 14, flexShrink: 0 }}
          >📎</button>
          <input ref={fileRef} type="file" accept="image/*" hidden onChange={handleFile} />
          <textarea
            ref={inputRef}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              // I5：中文输入法组合期（选词中）按下 Enter 不发送
              if (e.nativeEvent.isComposing) return;
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); }
            }}
            placeholder={waitingHumanInput ? '请先完成上方确认' : streaming ? '智能体正在回复…' : pendingPicUrl ? '例如：把色调调暖一点、换成日系风格…' : '描述你的需求…'}
            disabled={streaming || !!waitingHumanInput}
            style={{
              flex: 1, minHeight: 0, padding: '8px 10px', border: '1px solid var(--color-hairline)',
              borderRadius: 'var(--rounded-md)', font: 'var(--text-body-sm)', color: 'var(--color-ink)',
              resize: 'none', outline: 'none', background: 'var(--color-canvas)', overflowY: 'auto',
            }}
          />
          {/* 右侧按钮组（纵向）：优化在上、发送在下 */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, flexShrink: 0 }}>
            <button
              onClick={handleOptimize}
              disabled={streaming || !!waitingHumanInput || optimizing || text.trim().length < 6}
              title={optimizing ? '正在优化…' : text.trim().length < 6 ? '至少输入 6 个字符才能优化' : '优化为专业的剧情/图片/视频提示词（自动覆盖输入框）'}
              style={{
                height: 32, padding: '0 12px', border: '1px solid var(--color-hairline)',
                borderRadius: 'var(--rounded-md)', background: 'var(--color-canvas)',
                color: 'var(--color-primary)', fontSize: 12, cursor: 'pointer',
                opacity: streaming || !!waitingHumanInput || optimizing || text.trim().length < 6 ? 0.45 : 1,
              }}
            >{optimizing ? '⏳ 优化中…' : '✨ 优化'}</button>
            <button
              onClick={handleSend}
              disabled={streaming || !!waitingHumanInput || optimizing || !text.trim()}
              style={{
                height: 32, padding: '0 16px', border: 'none', borderRadius: 'var(--rounded-md)',
                background: streaming || optimizing || !text.trim() ? 'var(--color-primary-disabled)' : 'var(--color-primary)',
                color: 'white', fontSize: 13, cursor: 'pointer',
              }}
            >发送</button>
          </div>
        </div>
        </div>
        <div style={{ height: 10 }} /> {/* 底部留白 */}
      </div>

      {/* 清除聊天记录二次确认模态 */}
      {confirmClear && (
        <div
          onClick={() => setConfirmClear(false)}
          style={{
            position: 'fixed', inset: 0, background: 'rgba(20, 20, 19, 0.35)',
            display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200,
          }}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            style={{
              background: 'white', borderRadius: 'var(--rounded-md)',
              boxShadow: '0 8px 32px rgba(20, 20, 19, 0.18)', padding: 24,
              minWidth: 320, maxWidth: 440,
            }}
          >
            <h3 style={{ margin: '0 0 12px', font: 'var(--text-body)', color: 'var(--color-ink)' }}>
              清除聊天记录
            </h3>
            <p style={{ margin: '0 0 16px', font: 'var(--text-body-sm)', color: 'var(--color-muted)' }}>
              确定清除当前对话的聊天记录吗？AI 上下文将全部重置（可重新开始对话），此操作无法撤销。产出素材将保留。
            </p>
            <div style={{ textAlign: 'right' }}>
              <button
                onClick={() => setConfirmClear(false)}
                style={{
                  padding: '6px 18px', height: 32,
                  border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)',
                  background: 'white', color: 'var(--color-muted)', font: 'var(--text-caption)',
                  cursor: 'pointer', marginRight: 8,
                }}
              >取消</button>
              <button
                onClick={async () => {
                  setConfirmClear(false);
                  try {
                    await clearMessages();
                  } catch {
                    alert('清除失败，请重试');
                  }
                }}
                style={{
                  padding: '6px 18px', height: 32,
                  border: 'none', borderRadius: 'var(--rounded-md)',
                  background: 'var(--color-error)', color: 'white', font: 'var(--text-caption)',
                  cursor: 'pointer',
                }}
              >清除</button>
            </div>
          </div>
        </div>
      )}

      {/* 产出素材弹窗（文件夹图标入口） */}
      <AgentAssetsModal open={assetsOpen} onClose={() => setAssetsOpen(false)} />
    </div>
  );
}
