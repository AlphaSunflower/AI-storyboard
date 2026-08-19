import { useCallback, useEffect, useRef, useState } from 'react';
import { AppHeader } from '../components/layout/AppHeader';
import { AgentConversationList } from '../components/agent/AgentConversationList';
import { MessageBubble } from '../components/agent/MessageBubble';
import { HumanInputCard } from '../components/agent/HumanInputCard';
import { VideoPlanCard } from '../components/agent/VideoPlanCard';
import { ConfirmResultCard } from '../components/agent/ConfirmResultCard';
import { AgentAssetsModal } from '../components/agent/AgentAssetsPanel';
import TextType from '../components/TextType';
import { useAuthStore } from '../stores/authStore';
import { useProjectStore } from '../stores/projectStore';
import { useAgentStore } from '../stores/agentStore';

/** DeepSeek 设计 token（仿 deepseek-harness 浅色模式 design-platform.css） */
const DS = {
  brand: 'rgb(65, 118, 230)',          // --dsw-static-deepseek-500
  bubble: 'rgb(237, 243, 254)',        // --dsw-static-deepseek-50（用户气泡）
  ink: 'rgb(15, 17, 21)',              // --dsw-static-neutral-bluish-1000
  textSecondary: 'rgb(84, 85, 87)',    // --dsw-static-neutral-bluish-700
  textCaption: 'rgb(162, 164, 166)',   // --dsw-static-neutral-bluish-400
  border: 'rgba(0, 0, 0, 0.10)',       // --dsw-alias-border-l2
  hover: 'rgba(38, 49, 72, 0.06)',     // --dsw-alias-interactive-bg-hover
};

/**
 * 独立 AI 对话页（/chat）——仿 DeepSeek 桌面端聊天视觉：
 * 左会话栏（可拖宽）｜右对话区：头部 + 748px 居中消息列 + 780px 悬浮胶囊输入卡。
 * 复用 agentStore（双入口共用会话），消息气泡用 deepseek 变体。
 */
export function ChatPage() {
  const {
    messages, streaming, waitingHumanInput, waitingVideoPlan, streamError, workflowHint,
    refImageUrl, setRefImageUrl, uploadRefImage, sendMessage, clearMessages,
    confirmResult, pendingPicUrl, cancelRefine, assets, loadAssets,
    conversations, activeConversationId,
  } = useAgentStore();
  const [text, setText] = useState('');
  const [confirmClear, setConfirmClear] = useState(false);
  const [assetsOpen, setAssetsOpen] = useState(false);
  const [convWidth, setConvWidth] = useState(240);
  const scrollRef = useRef<HTMLDivElement>(null);
  const nearBottomRef = useRef(true);
  const fileRef = useRef<HTMLInputElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const loadedRef = useRef(false);

  // 登录守卫（与 EditorPage 一致）
  useEffect(() => {
    useAuthStore.getState().checkAuth();
  }, []);

  // 无项目上下文时自动选第一个项目（会话绑定项目是后端契约）
  useEffect(() => {
    if (loadedRef.current) return;
    loadedRef.current = true;
    const st = useProjectStore.getState();
    if (st.currentProject) return;
    st.loadProjects()
      .then(() => {
        const list = useProjectStore.getState().projects;
        if (list.length > 0) {
          useProjectStore.getState().loadProject(list[0].id);
          useAgentStore.getState().loadConversations().catch(() => { /* 静默 */ });
        }
      })
      .catch(() => { /* 静默：列表失败时用户可经顶栏项目下拉选择 */ });
  }, []);

  // 新消息自动滚底（近底才跟随）
  useEffect(() => {
    const el = scrollRef.current;
    if (el && nearBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [messages, streaming, waitingHumanInput, waitingVideoPlan]);

  // 切换会话清空草稿
  useEffect(() => { setText(''); }, [activeConversationId]);

  const handleSend = () => {
    const content = text.trim();
    if (!content || streaming || waitingHumanInput || waitingVideoPlan) return;
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

  const currentTitle = conversations.find((c) => c.id === activeConversationId)?.title ?? '未选择对话';

  // 会话栏宽度拖拽
  const handleConvDrag = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startW = convWidth;
    const onMouseMove = (ev: MouseEvent) => {
      setConvWidth(Math.min(360, Math.max(180, startW + ev.clientX - startX)));
    };
    const onMouseUp = () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
    };
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
  }, [convWidth]);

  const busy = streaming || !!waitingHumanInput || !!waitingVideoPlan;

  return (
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column', background: 'white' }}>
      <AppHeader />
      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        <AgentConversationList width={convWidth} />
        <div
          onMouseDown={handleConvDrag}
          style={{
            width: 4, cursor: 'col-resize', background: 'transparent',
            transition: 'background 0.15s', flexShrink: 0,
          }}
          onMouseEnter={(e) => { (e.target as HTMLElement).style.background = DS.brand; }}
          onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
        />
        {/* ── 对话区 ── */}
        <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
          {/* 头部：会话标题 + 操作 */}
          <div style={{
            padding: '14px 28px', borderBottom: `1px solid ${DS.border}`,
            display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0,
          }}>
            <span style={{ fontSize: 18, fontWeight: 600, color: DS.ink, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {currentTitle}
            </span>
            <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              <button
                onClick={() => { setAssetsOpen(true); void loadAssets(); }}
                disabled={!activeConversationId}
                style={headerBtn(activeConversationId ? 1 : 0.4)}
              >📁 产出素材{assets && assets.total > 0 ? ` (${assets.total})` : ''}</button>
              <button
                onClick={() => setConfirmClear(true)}
                disabled={busy || !activeConversationId || messages.length === 0}
                style={headerBtn(busy || !activeConversationId || messages.length === 0 ? 0.4 : 1)}
              >🧹 清除聊天记录</button>
            </div>
          </div>

          {/* 消息列：748px 居中（DeepSeek 风格） */}
          <div
            ref={scrollRef}
            onScroll={(e) => {
              const el = e.target as HTMLElement;
              nearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
            }}
            style={{ flex: 1, minHeight: 0, overflowY: 'auto', background: 'white' }}
          >
            <div style={{ maxWidth: 748, margin: '0 auto', padding: '24px 16px 16px', display: 'flex', flexDirection: 'column' }}>
              {messages.length === 0 && !streaming && (
                <TextType
                  as="p"
                  text="与 Moon 智能体对话，设计分镜、图片与视频方案"
                  typingSpeed={55}
                  initialDelay={200}
                  pauseDuration={4000}
                  loop={false}
                  showCursor
                  cursorCharacter="|"
                  style={{ textAlign: 'center', color: DS.textCaption, fontSize: 15, marginTop: 72 }}
                />
              )}
              {messages.map((m) => (
                <MessageBubble
                  key={m.id}
                  role={m.role}
                  content={m.content}
                  variant="deepseek"
                  streaming={streaming && m.role === 'assistant' && m.id === messages[messages.length - 1]?.id}
                />
              ))}
              {streaming && !waitingHumanInput && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, color: DS.textCaption, fontSize: 14, padding: '2px 4px' }}>
                  <span style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
                    {[0, 1, 2].map((i) => (
                      <span
                        key={i}
                        style={{
                          width: 6, height: 6, borderRadius: '50%', background: DS.brand, opacity: 0.35,
                          animation: 'dsDot 1.1s ease-in-out infinite', animationDelay: `${i * 0.18}s`,
                        }}
                      />
                    ))}
                  </span>
                  <span>{workflowHint || '正在生成'}</span>
                  <style>{`@keyframes dsDot { 0%, 60%, 100% { opacity: 0.25; } 30% { opacity: 1; } }`}</style>
                </div>
              )}
              {waitingHumanInput && <HumanInputCard info={waitingHumanInput} />}
              {waitingVideoPlan && <VideoPlanCard info={waitingVideoPlan} />}
              {confirmResult && <ConfirmResultCard />}
              {streamError && (
                <div style={{ color: 'rgb(217, 45, 32)', fontSize: 14, margin: '10px 6px' }}>⚠ {streamError}</div>
              )}
            </div>
          </div>

          {/* 输入卡：780px 悬浮胶囊（DeepSeek 风格） */}
          <div style={{ padding: '8px 16px 16px', background: 'white' }}>
            <div style={{
              maxWidth: 780, margin: '0 auto',
              border: `1px solid ${DS.border}`, borderRadius: 22,
              background: 'white', boxShadow: '0 2px 12px rgba(0, 0, 0, 0.06)',
              padding: '10px 14px 12px', display: 'flex', flexDirection: 'column', gap: 10,
            }}>
              {refImageUrl && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <img src={refImageUrl} style={{ width: 48, height: 48, objectFit: 'cover', borderRadius: 10 }} />
                  <span style={{ fontSize: 14, color: DS.textSecondary }}>参考图已附</span>
                  <button onClick={() => setRefImageUrl(null)} style={linkBtn(DS.textSecondary)}>移除</button>
                </div>
              )}
              {pendingPicUrl && (
                <div style={{
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                  padding: '8px 12px', borderRadius: 12, background: DS.hover, fontSize: 14, color: DS.textSecondary,
                }}>
                  <span>📎 已选当前图片作为参考，请输入你想完善的地方</span>
                  <button onClick={() => cancelRefine()} style={linkBtn(DS.textSecondary)}>✕ 取消</button>
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
          </div>
        </div>
      </div>

      {/* 清除聊天记录二次确认 */}
      {confirmClear && (
        <div
          onClick={() => setConfirmClear(false)}
          style={{ position: 'fixed', inset: 0, background: 'rgba(0, 0, 0, 0.24)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 }}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            style={{ background: 'white', borderRadius: 16, boxShadow: '0 8px 32px rgba(0, 0, 0, 0.16)', padding: 24, minWidth: 320, maxWidth: 440 }}
          >
            <h3 style={{ margin: '0 0 12px', fontSize: 16, color: DS.ink }}>清除聊天记录</h3>
            <p style={{ margin: '0 0 16px', fontSize: 14, color: DS.textSecondary, lineHeight: 1.6 }}>
              确定清除当前对话的聊天记录吗？AI 上下文将全部重置（可重新开始对话），此操作无法撤销。产出素材将保留。
            </p>
            <div style={{ textAlign: 'right' }}>
              <button
                onClick={() => setConfirmClear(false)}
                style={{ padding: '6px 18px', height: 32, border: `1px solid ${DS.border}`, borderRadius: 10, background: 'white', color: DS.textSecondary, fontSize: 14, cursor: 'pointer', marginRight: 8 }}
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
                style={{ padding: '6px 18px', height: 32, border: 'none', borderRadius: 10, background: 'rgb(217, 45, 32)', color: 'white', fontSize: 14, cursor: 'pointer' }}
              >清除</button>
            </div>
          </div>
        </div>
      )}
      <AgentAssetsModal open={assetsOpen} onClose={() => setAssetsOpen(false)} />
    </div>
  );
}

function headerBtn(opacity: number): React.CSSProperties {
  return {
    border: 'none', background: 'transparent', color: 'rgb(84, 85, 87)', fontSize: 14,
    cursor: opacity === 1 ? 'pointer' : 'not-allowed', padding: '6px 10px', borderRadius: 8, opacity,
  };
}

function linkBtn(color: string): React.CSSProperties {
  return { border: 'none', background: 'none', color, cursor: 'pointer', fontSize: 14 };
}
