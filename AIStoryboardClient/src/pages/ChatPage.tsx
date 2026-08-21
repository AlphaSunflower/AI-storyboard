import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { AgentConversationList } from '../components/agent/AgentConversationList';
import { LiveOrb } from '../components/ui/live-orb';
import { MessageBubble } from '../components/agent/MessageBubble';
import { HumanInputCard } from '../components/agent/HumanInputCard';
import { VideoPlanCard } from '../components/agent/VideoPlanCard';
import { ConfirmResultCard } from '../components/agent/ConfirmResultCard';
import { AgentAssetsModal } from '../components/agent/AgentAssetsPanel';
import { ChatComposer, DS } from '../components/agent/ChatComposer';
import { ProjectDropdown } from '../components/layout/ProjectDropdown';
import { AssetLibraryPanel } from '../components/asset/AssetLibraryPanel';
import { PersonalInfoModal } from '../components/agent/PersonalInfoModal';
import { MobileHeader } from '../components/agent/MobileHeader';
import { MobileSidebarContent } from '../components/agent/MobileSidebarContent';
import { MobileBottomNav } from '../components/layout/MobileBottomNav';
import { useIsMobile } from '../hooks/useIsMobile';
import { useAuthStore } from '../stores/authStore';
import { useProjectStore } from '../stores/projectStore';
import { useAgentStore } from '../stores/agentStore';
import MoonLogo from '../components/agent/MoonLogo';

/**
 * 独立 AI 对话页（/chat）——仿 DeepSeek 桌面端：
 * 最左图标导航（项目/资源库/左下角设置）｜会话列表（可拖宽）｜主区：
 * 首次对话 = 名称居中 + hero 输入卡；有会话 = 头部名称 + 748px 消息列 + 底部输入卡。
 */
export function ChatPage() {
  const navigate = useNavigate();
  const isMobile = useIsMobile();
  const logout = useAuthStore((s) => s.logout);
  const currentProject = useProjectStore((s) => s.currentProject);
  const createConversation = useAgentStore((s) => s.createConversation);
  const {
    messages, streaming, waitingHumanInput, waitingVideoPlan, streamError, workflowHint,
    confirmResult, assets, loadAssets, conversations, activeConversationId,
  } = useAgentStore();
  const [assetsOpen, setAssetsOpen] = useState(false);       // 资源库（rail 🧩）
  const [assetsModalOpen, setAssetsModalOpen] = useState(false); // 产出素材（会话头部 📁）
  const [projectOpen, setProjectOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [convWidth, setConvWidth] = useState(240);
  const [collapsed, setCollapsed] = useState(false); // 侧栏收起：仅显示图标 rail
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false); // 手机端 overlay 侧栏
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
  const isEmpty = messages.length === 0 && !streaming && !waitingHumanInput && !waitingVideoPlan;

  // 手机端：选择会话后自动关闭侧栏
  const handleMobileSelectConversation = (id: string) => {
    useAgentStore.getState().selectConversation(id);
    setMobileSidebarOpen(false);
  };

  // 手机端：新建对话后自动关闭侧栏
  const handleMobileNewConversation = async () => {
    await createConversation();
    setMobileSidebarOpen(false);
  };

  /* ═══════════════ 手机端布局（≤768px）═══════════════ */
  if (isMobile) {
    return (
      <div style={{ height: '100vh', display: 'flex', flexDirection: 'column', background: 'white', overflow: 'hidden' }}>
        {/* 顶部导航栏 */}
        <MobileHeader
          title={currentTitle}
          onMenuClick={() => setMobileSidebarOpen(true)}
          onNewConversation={handleMobileNewConversation}
        />

        {/* 会话列表 overlay 抽屉 */}
        {mobileSidebarOpen && (
          <div
            onClick={() => setMobileSidebarOpen(false)}
            style={{
              position: 'fixed', inset: 0, zIndex: 100,
              background: 'rgba(0, 0, 0, 0.35)',
            }}
          >
            <div
              onClick={(e) => e.stopPropagation()}
              style={{
                position: 'absolute', top: 0, left: 0, bottom: 0,
                width: '80vw', maxWidth: 320,
                background: 'var(--color-surface-soft)',
                display: 'flex', flexDirection: 'column',
                boxShadow: '4px 0 24px rgba(0, 0, 0, 0.12)',
                animation: 'mobileSlideIn 0.25s ease-out',
              }}
            >
              {/* 侧栏内嵌会话列表（禁用宽度拖拽和收起按钮） */}
              <MobileSidebarContent
                onSelectConversation={handleMobileSelectConversation}
                onClose={() => setMobileSidebarOpen(false)}
                onOpenSettings={() => { setProfileOpen(true); setMobileSidebarOpen(false); }}
                onOpenAssets={() => { setAssetsOpen(true); setMobileSidebarOpen(false); }}
                onNavigate={(path) => { navigate(path); setMobileSidebarOpen(false); }}
                logout={logout}
              />
            </div>
            <style>{`@keyframes mobileSlideIn { from { transform: translateX(-100%); } to { transform: translateX(0); } }`}</style>
          </div>
        )}

        {/* 主对话区 */}
        {isEmpty ? (
          <div style={{
            flex: 1, display: 'flex', flexDirection: 'column',
            alignItems: 'center', justifyContent: 'center', gap: 20, padding: '0 16px',
          }}>
            <LiveOrb variant="webgl" colors={["#1D4E89", "#81C3D7", "#D9E8F5"]} size={90} />
            <div style={{ textAlign: 'center' }}>
              <div style={{ marginBottom: 6 }}><MoonLogo size={28} showText textColor={DS.ink} /></div>
              <div style={{ fontSize: 13, color: DS.textCaption }}>设计分镜、图片与视频方案</div>
            </div>
            <div style={{ width: '100%', maxWidth: 480 }}>
              <ChatComposer />
            </div>
          </div>
        ) : (
          <>
            {/* 消息列 */}
            <div
              ref={scrollRef}
              onScroll={(e) => {
                const el = e.target as HTMLElement;
                nearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
              }}
              style={{ flex: 1, minHeight: 0, overflowY: 'auto', background: 'white' }}
            >
              <div style={{ maxWidth: 720, margin: '0 auto', padding: '16px 12px', display: 'flex', flexDirection: 'column' }}>
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
                        <span key={i} style={{
                          width: 6, height: 6, borderRadius: '50%', background: DS.brand, opacity: 0.35,
                          animation: 'dsDot 1.1s ease-in-out infinite', animationDelay: `${i * 0.18}s`,
                        }} />
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
            <div style={{ padding: '8px 12px 12px 12px', background: 'white' }}>
              <ChatComposer />
            </div>
          </>
        )}

        <MobileBottomNav onOpenAssets={() => setAssetsOpen(true)} />

        {assetsOpen && <AssetLibraryPanel onClose={() => setAssetsOpen(false)} />}
        {profileOpen && <PersonalInfoModal onClose={() => setProfileOpen(false)} />}
        <AgentAssetsModal open={assetsModalOpen} onClose={() => setAssetsModalOpen(false)} />
      </div>
    );
  }

  /* ═══════════════ 桌面端布局（>768px）═══════════════ */

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
            >{/** 资源库 */}<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 3h7l2 3h9v13H3z"/></svg></button>
            <div style={{ position: 'relative', width: '100%', display: 'flex', justifyContent: 'center' }}>
              <button
                ref={projectBtnRef}
                onClick={() => { setProjectOpen(!projectOpen); setSettingsOpen(false); }}
                title={currentProject?.name ?? '选择项目'}
                style={railIconBtn}
              >{/** 项目 */}<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="4" width="20" height="16" rx="2"/><path d="M8 4v16M16 4v16"/></svg></button>
              <ProjectDropdown open={projectOpen} onClose={() => setProjectOpen(false)} anchor={projectBtnRef} />
            </div>
          </div>
          <div style={{ position: 'relative', width: '100%', display: 'flex', justifyContent: 'center' }}>
            <button
              ref={settingsBtnRef}
              onClick={() => { setSettingsOpen(!settingsOpen); setProjectOpen(false); }}
              title="设置"
              style={railIconBtn}
            >{/** 设置 */}<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="3"/><path d="M10 2l1.5 2.5L14 3l-.5 2.8 2.5 1.2-1.5 2.3 2.3 1.5-2.8.5.5 2.8-2.5-1.2L10 17l-.5-2.8-2.5 1.2 1.5-2.3-2.3-1.5 2.8-.5L8.5 8.7 11 9.9z"/></svg></button>
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
                  { label: '个人信息', icon: 'user', onClick: () => { setProfileOpen(true); setSettingsOpen(false); } },
                  { label: '使用文档', icon: 'docs', onClick: () => navigate('/docs') },
                  { label: '编辑器', icon: 'editor', onClick: () => navigate('/editor') },
                  { label: '退出登录', icon: 'logout', onClick: logout, color: '#d92d20' },
                ].map((it) => (
                  <button
                    key={it.label}
                    onClick={it.onClick}
                    style={{
                      width: '100%', textAlign: 'left', padding: '9px 12px', border: 'none',
                      background: 'transparent', borderRadius: 8, fontSize: 14, cursor: 'pointer',
                      color: it.color ?? DS.ink, display: 'flex', alignItems: 'center', gap: 8,
                    }}
                    onMouseEnter={(e) => { (e.target as HTMLElement).style.background = DS.hover; }}
                    onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
                  >{it.icon === 'user' && <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>}{it.icon === 'docs' && <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><path d="M14 2v6h6M8 13h8M8 17h5"/></svg>}{it.icon === 'editor' && <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M12 20h9M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z"/></svg>}{it.icon === 'logout' && <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9"/></svg>}{' '}{it.label}</button>
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
                >{/** 资源库 */}<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 3h7l2 3h9v13H3z"/></svg> 资源库</button>
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
                    <span style={{ fontSize: 15 }}>{/** 项目 */}<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="4" width="20" height="16" rx="2"/><path d="M8 4v16M16 4v16"/></svg></span>
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

        {/* 底部：设置（左下角） */}
        <div style={{
          display: 'flex', flexDirection: 'column',
          padding: '6px 10px', borderTop: '1px solid var(--color-hairline)',
        }}>
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
            >{/** 设置 */}<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="3"/><path d="M10 2l1.5 2.5L14 3l-.5 2.8 2.5 1.2-1.5 2.3 2.3 1.5-2.8.5.5 2.8-2.5-1.2L10 17l-.5-2.8-2.5 1.2 1.5-2.3-2.3-1.5 2.8-.5L8.5 8.7 11 9.9z"/></svg> 设置</button>
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
                  { label: '个人信息', icon: 'user', onClick: () => { setProfileOpen(true); setSettingsOpen(false); } },
                  { label: '使用文档', icon: 'docs', onClick: () => navigate('/docs') },
                  { label: '编辑器', icon: 'editor', onClick: () => navigate('/editor') },
                  { label: '退出登录', icon: 'logout', onClick: logout, color: '#d92d20' },
                ].map((it) => (
                  <button
                    key={it.label}
                    onClick={it.onClick}
                    style={{
                      width: '100%', textAlign: 'left', padding: '9px 12px', border: 'none',
                      background: 'transparent', borderRadius: 8, fontSize: 14, cursor: 'pointer',
                      color: it.color ?? DS.ink, display: 'flex', alignItems: 'center', gap: 8,
                    }}
                    onMouseEnter={(e) => { (e.target as HTMLElement).style.background = DS.hover; }}
                    onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
                  >{it.icon === 'user' && <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>}{it.icon === 'docs' && <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><path d="M14 2v6h6M8 13h8M8 17h5"/></svg>}{it.icon === 'editor' && <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M12 20h9M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z"/></svg>}{it.icon === 'logout' && <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9"/></svg>}{' '}{it.label}</button>
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
            <LiveOrb
              variant="webgl"
              colors={["#1D4E89", "#81C3D7", "#D9E8F5"]}
              size={120}
            />
            <div style={{ textAlign: 'center' }}>
              <div style={{ marginBottom: 8 }}><MoonLogo size={36} showText textColor={DS.ink} /></div>
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
