import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { AgentConversationList } from '../components/agent/AgentConversationList';
import { MessageBubble } from '../components/agent/MessageBubble';
import { HumanInputCard } from '../components/agent/HumanInputCard';
import { VideoPlanCard } from '../components/agent/VideoPlanCard';
import { ConfirmResultCard } from '../components/agent/ConfirmResultCard';
import { AgentAssetsModal } from '../components/agent/AgentAssetsPanel';
import { ChatComposer, DS } from '../components/agent/ChatComposer';
import { ProjectDropdown } from '../components/layout/ProjectDropdown';
import { AssetLibraryPanel } from '../components/asset/AssetLibraryPanel';
import { PersonalInfoModal } from '../components/agent/PersonalInfoModal';
import { useAuthStore } from '../stores/authStore';
import { useProjectStore } from '../stores/projectStore';
import { useAgentStore } from '../stores/agentStore';

/**
 * 独立 AI 对话页（/chat）——仿 DeepSeek 桌面端：
 * 最左图标导航（项目/资源库/左下角设置）｜会话列表（可拖宽）｜主区：
 * 首次对话 = 名称居中 + hero 输入卡；有会话 = 头部名称 + 748px 消息列 + 底部输入卡。
 */
export function ChatPage() {
  const navigate = useNavigate();
  const logout = useAuthStore((s) => s.logout);
  const currentProject = useProjectStore((s) => s.currentProject);
  const {
    messages, streaming, waitingHumanInput, waitingVideoPlan, streamError, workflowHint,
    clearMessages, confirmResult, assets, loadAssets, conversations, activeConversationId,
  } = useAgentStore();
  const [confirmClear, setConfirmClear] = useState(false);
  const [assetsOpen, setAssetsOpen] = useState(false);       // 资源库（rail 🧩）
  const [assetsModalOpen, setAssetsModalOpen] = useState(false); // 产出素材（会话头部 📁）
  const [projectOpen, setProjectOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [convWidth, setConvWidth] = useState(240);
  const [collapsed, setCollapsed] = useState(false); // 侧栏收起：仅显示图标 rail
  const scrollRef = useRef<HTMLDivElement>(null);
  const nearBottomRef = useRef(true);
  const loadedRef = useRef(false);
  const projectBtnRef = useRef<HTMLButtonElement>(null);
  const settingsBtnRef = useRef<HTMLButtonElement>(null);

  // 登录守卫
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
        }
      })
      .catch(() => { /* 静默 */ });
  }, []);

  // 项目切换（含首次确定）→ 清掉旧会话状态并加载该项目会话列表。
  // ProjectDropdown 选项目只更新 currentProject，不触发会话加载——这里统一兜底。
  const projectId = currentProject?.id;
  useEffect(() => {
    if (!projectId) return;
    useAgentStore.setState({ activeConversationId: null, messages: [] });
    useAgentStore.getState().loadConversations().catch(() => { /* 静默 */ });
  }, [projectId]);

  // 新消息自动滚底（近底才跟随）
  useEffect(() => {
    const el = scrollRef.current;
    if (el && nearBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [messages, streaming, waitingHumanInput, waitingVideoPlan]);

  const currentTitle = conversations.find((c) => c.id === activeConversationId)?.title ?? '未选择对话';
  const busy = streaming || !!waitingHumanInput || !!waitingVideoPlan;
  const isEmpty = messages.length === 0 && !streaming && !waitingHumanInput && !waitingVideoPlan;

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

  return (
    <div style={{ height: '100vh', display: 'flex', background: 'white', overflow: 'hidden' }}>
      {/* ── 会话栏（可收起：展开=完整列表，收起=48px 图标 rail，仿 DeepSeek）── */}
      {collapsed ? (
        /* ── 收起态：图标 rail ── */
        <div style={{
          width: 48, flexShrink: 0, background: 'var(--color-surface-soft)',
          borderRight: '1px solid var(--color-hairline)',
          display: 'flex', flexDirection: 'column', justifyContent: 'space-between',
          alignItems: 'center', padding: '10px 0', zIndex: 50,
        }}>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
            <button
              onClick={() => setCollapsed(false)}
              title="展开侧边栏"
              style={railIconBtn}
            >☾</button>
            <button
              onClick={() => setAssetsOpen(true)}
              title="资源库"
              style={railIconBtn}
            >🧩</button>
            <div style={{ position: 'relative', width: '100%', display: 'flex', justifyContent: 'center' }}>
              <button
                ref={projectBtnRef}
                onClick={() => { setProjectOpen(!projectOpen); setSettingsOpen(false); }}
                title={currentProject?.name ?? '选择项目'}
                style={railIconBtn}
              >🗂️</button>
              <ProjectDropdown open={projectOpen} onClose={() => setProjectOpen(false)} anchor={projectBtnRef} />
            </div>
          </div>
          <div style={{ position: 'relative', width: '100%', display: 'flex', justifyContent: 'center' }}>
            <button
              onClick={() => navigate('/editor')}
              title="AI 分镜"
              style={railIconBtn}
            >🎬</button>
          </div>
          <div style={{ position: 'relative', width: '100%', display: 'flex', justifyContent: 'center' }}>
            <button
              ref={settingsBtnRef}
              onClick={() => { setSettingsOpen(!settingsOpen); setProjectOpen(false); }}
              title="设置"
              style={railIconBtn}
            >⚙️</button>
            {settingsOpen && settingsBtnRef.current && createPortal(
              <div style={{
                position: 'fixed',
                top: Math.max(8, settingsBtnRef.current.getBoundingClientRect().top - 170),
                left: settingsBtnRef.current.getBoundingClientRect().right + 8,
                width: 170,
                background: 'white', border: `1px solid ${DS.border}`, borderRadius: 12,
                boxShadow: '0 8px 24px rgba(0, 0, 0, 0.12)', padding: 6, zIndex: 2000,
              }}>
                {[
                  { label: '👤 个人信息', onClick: () => { setProfileOpen(true); setSettingsOpen(false); } },
                  { label: '📄 使用文档', onClick: () => navigate('/docs') },
                  { label: '✏️ 编辑器', onClick: () => navigate('/editor') },
                  { label: '🚪 退出登录', onClick: logout, color: '#d92d20' },
                ].map((it) => (
                  <button
                    key={it.label}
                    onClick={it.onClick}
                    style={{
                      width: '100%', textAlign: 'left', padding: '9px 12px', border: 'none',
                      background: 'transparent', borderRadius: 8, fontSize: 14, cursor: 'pointer',
                      color: it.color ?? DS.ink,
                    }}
                    onMouseEnter={(e) => { (e.target as HTMLElement).style.background = DS.hover; }}
                    onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
                  >{it.label}</button>
                ))}
              </div>,
              document.body,
            )}
          </div>
        </div>
      ) : (
      <div style={{
        width: convWidth, flexShrink: 0, display: 'flex', flexDirection: 'column',
        background: 'var(--color-surface-soft)', transition: 'width 0.2s ease',
      }}>
        {/* 会话列表（toolbar 插槽注入 项目+资源库，位于新建对话上方；flex:1 内部滚动） */}
        <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
          <AgentConversationList
            width={convWidth}
            toolbar={
              <>
                {/* 收起侧栏按钮（标题下方） */}
                <div style={{ display: 'flex', justifyContent: 'flex-end', padding: '6px 12px 0' }}>
                  <button
                    onClick={() => setCollapsed(true)}
                    title="收起侧边栏"
                    style={{
                      border: 'none', background: 'transparent', cursor: 'pointer',
                      fontSize: 13, color: 'var(--color-muted)', padding: '4px 8px', borderRadius: 8,
                    }}
                  >◀ 收起</button>
                </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: '8px 12px 12px' }}>
                {/* 资源库（独立一行，DeepSeek ghost 风格，与新建按钮同高 40/字体 15） */}
                <button
                  onClick={() => setAssetsOpen(true)}
                  title="资源库"
                  style={{
                    width: '100%', display: 'flex', alignItems: 'center', gap: 8,
                    border: 'none', borderRadius: 10, background: 'transparent',
                    padding: '0 12px', height: 40, fontSize: 15,
                    color: DS.ink, cursor: 'pointer', textAlign: 'left',
                  }}
                  onMouseEnter={(e) => { (e.target as HTMLElement).style.background = DS.hover; }}
                  onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
                >🧩 资源库</button>
                {/* 项目选择（独立一行，资源库下方，ghost 风格；弹层 portal 右对齐不被裁剪） */}
                <div style={{ position: 'relative', width: '100%' }}>
                  <button
                    ref={projectBtnRef}
                    onClick={() => { setProjectOpen(!projectOpen); setSettingsOpen(false); }}
                    title="项目选择"
                    style={{
                      width: '100%', display: 'flex', alignItems: 'center', gap: 6,
                      border: 'none', borderRadius: 10, background: 'transparent',
                      padding: '0 12px', height: 40, fontSize: 15,
                      color: DS.ink, cursor: 'pointer',
                    }}
                    onMouseEnter={(e) => { (e.target as HTMLElement).style.background = DS.hover; }}
                    onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
                  >
                    <span style={{ fontSize: 15 }}>🗂️</span>
                    <span style={{ flex: 1, textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {currentProject?.name ?? '选择项目'}
                    </span>
                    <span style={{ fontSize: 10, color: DS.textCaption }}>▼</span>
                  </button>
                  <ProjectDropdown open={projectOpen} onClose={() => setProjectOpen(false)} anchor={projectBtnRef} />
                </div>
              </div>
              </>
            }
          />
        </div>

        {/* 底部：AI 分镜（上）+ 设置（左下角） */}
        <div style={{
          display: 'flex', flexDirection: 'column',
          padding: '6px 10px', borderTop: '1px solid var(--color-hairline)',
        }}>
          {/* AI 分镜：路由到编辑器（分镜编辑主界面） */}
          <button
            onClick={() => navigate('/editor')}
            title="AI 分镜"
            style={{
              border: 'none', background: 'transparent', borderRadius: 10,
              padding: '0 12px', height: 42, fontSize: 15, color: 'var(--color-muted)', cursor: 'pointer',
              display: 'flex', alignItems: 'center', gap: 6, textAlign: 'left',
            }}
            onMouseEnter={(e) => { (e.target as HTMLElement).style.background = DS.hover; }}
            onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
          >🎬 AI 分镜</button>
          <div style={{ position: 'relative' }}>
            <button
              ref={settingsBtnRef}
              onClick={() => { setSettingsOpen(!settingsOpen); setProjectOpen(false); }}
              title="设置"
              style={{
                border: 'none', background: 'transparent', borderRadius: 10,
                padding: '0 12px', height: 42, fontSize: 15, color: 'var(--color-muted)', cursor: 'pointer',
                display: 'flex', alignItems: 'center', gap: 6,
              }}
            >⚙️ 设置</button>
            {settingsOpen && settingsBtnRef.current && createPortal(
              <div style={{
                position: 'fixed',
                top: Math.max(8, settingsBtnRef.current.getBoundingClientRect().top - 170),
                left: settingsBtnRef.current.getBoundingClientRect().right + 8,
                width: 170,
                background: 'white', border: `1px solid ${DS.border}`, borderRadius: 12,
                boxShadow: '0 8px 24px rgba(0, 0, 0, 0.12)', padding: 6, zIndex: 2000,
              }}>
                {[
                  { label: '👤 个人信息', onClick: () => { setProfileOpen(true); setSettingsOpen(false); } },
                  { label: '📄 使用文档', onClick: () => navigate('/docs') },
                  { label: '✏️ 编辑器', onClick: () => navigate('/editor') },
                  { label: '🚪 退出登录', onClick: logout, color: '#d92d20' },
                ].map((it) => (
                  <button
                    key={it.label}
                    onClick={it.onClick}
                    style={{
                      width: '100%', textAlign: 'left', padding: '9px 12px', border: 'none',
                      background: 'transparent', borderRadius: 8, fontSize: 14, cursor: 'pointer',
                      color: it.color ?? DS.ink,
                    }}
                    onMouseEnter={(e) => { (e.target as HTMLElement).style.background = DS.hover; }}
                    onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
                  >{it.label}</button>
                ))}
              </div>,
              document.body,
            )}
          </div>
        </div>
      </div>
      )}

      {/* 拖拽把手 */}
      <div
        onMouseDown={handleConvDrag}
        style={{
          width: 4, cursor: 'col-resize', background: 'transparent',
          transition: 'background 0.15s', flexShrink: 0,
        }}
        onMouseEnter={(e) => { (e.target as HTMLElement).style.background = DS.brand; }}
        onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
      />
      {collapsed && <div style={{ width: 4, flexShrink: 0 }} />}

      {/* ── 主对话区 ── */}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        {isEmpty ? (
          /* 首次对话 hero：名称居中 + 输入卡居中（DeepSeek harness 空态） */
          <div style={{
            flex: 1, display: 'flex', flexDirection: 'column',
            alignItems: 'center', justifyContent: 'center', gap: 28, padding: '0 16px',
          }}>
            <div style={{ textAlign: 'center' }}>
              <div style={{ fontSize: 28, fontWeight: 700, color: DS.ink, marginBottom: 8 }}>Moon 智能体</div>
              <div style={{ fontSize: 14, color: DS.textCaption }}>设计分镜、图片与视频方案</div>
            </div>
            <ChatComposer />
          </div>
        ) : (
          <>
            {/* 头部：会话名称 + 操作 */}
            <div style={{
              padding: '14px 28px', borderBottom: `1px solid ${DS.border}`,
              display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0,
            }}>
              <span style={{ fontSize: 18, fontWeight: 600, color: DS.ink, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {currentTitle}
              </span>
              <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                <button
                  onClick={() => { setAssetsModalOpen(true); void loadAssets(); }}
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

            {/* 消息列：748px 居中 */}
            <div
              ref={scrollRef}
              onScroll={(e) => {
                const el = e.target as HTMLElement;
                nearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
              }}
              style={{ flex: 1, minHeight: 0, overflowY: 'auto', background: 'white' }}
            >
              <div style={{ maxWidth: 900, margin: '0 auto', padding: '24px 16px 16px', display: 'flex', flexDirection: 'column' }}>
                {messages.map((m) => (
                  <MessageBubble
                    key={m.id}
                    role={m.role}
                    content={m.content}
                    variant="deepseek"
                    createdAt={m.createdAt}
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

            {/* 底部输入卡 */}
            <div style={{ padding: '8px 16px 16px', background: 'white' }}>
              <ChatComposer />
            </div>
          </>
        )}
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
      {assetsOpen && <AssetLibraryPanel onClose={() => setAssetsOpen(false)} />}
      {profileOpen && <PersonalInfoModal onClose={() => setProfileOpen(false)} />}
      <AgentAssetsModal open={assetsModalOpen} onClose={() => setAssetsModalOpen(false)} />
    </div>
  );
}

function headerBtn(opacity: number): React.CSSProperties {
  return {
    border: 'none', background: 'transparent', color: 'rgb(84, 85, 87)', fontSize: 14,
    cursor: opacity === 1 ? 'pointer' : 'not-allowed', padding: '6px 10px', borderRadius: 8, opacity,
  };
}

/** 收起态 rail 图标按钮 */
const railIconBtn: React.CSSProperties = {
  width: 36, height: 36, borderRadius: 10, border: 'none',
  background: 'transparent', fontSize: 18, cursor: 'pointer',
  display: 'flex', alignItems: 'center', justifyContent: 'center',
};
