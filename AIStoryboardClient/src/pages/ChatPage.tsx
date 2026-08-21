import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AgentConversationList } from '../components/agent/AgentConversationList';
import { LiveOrb } from '../components/ui/live-orb';
import { MessageBubble } from '../components/agent/MessageBubble';
import { HumanInputCard } from '../components/agent/HumanInputCard';
import { VideoPlanCard } from '../components/agent/VideoPlanCard';
import { ConfirmResultCard } from '../components/agent/ConfirmResultCard';
import { AgentAssetsModal } from '../components/agent/AgentAssetsPanel';
import { ChatComposer, DS } from '../components/agent/ChatComposer';
import { SettingsPopover } from '../components/agent/SettingsPopover';
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

  // 会话栏宽度拖拽（桌面端）
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
    <>
    {/* ═══════════════ 手机端布局（CSS 隐藏/显示，避免 hooks 协调错误）═══════════════ */}
      <div style={{ height: '100vh', display: isMobile ? 'flex' : 'none', flexDirection: 'column', background: 'white', overflow: 'hidden' }}>
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
                background: DS.sidebarBg,
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
            {/* 快捷提示 chips（手机端） */}
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, justifyContent: 'center', padding: '0 8px' }}>
              {['帮我设计一个科幻分镜', '生成一张概念海报'].map((t) => (
                <button
                  key={t}
                  onClick={() => {
                    const st = useAgentStore.getState();
                    if (!st.activeConversationId) {
                      st.createConversation().then(() => {
                        setTimeout(() => useAgentStore.getState().sendMessage(t), 200);
                      });
                    } else {
                      st.sendMessage(t);
                    }
                  }}
                  style={{
                    padding: '6px 14px', borderRadius: 16, border: `1px solid ${DS.border}`,
                    background: 'white', fontSize: 12, color: DS.textSecondary, cursor: 'pointer',
                  }}
                >{t}</button>
              ))}
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
              <div style={{ maxWidth: DS.maxContent, margin: '0 auto', padding: '16px 12px', display: 'flex', flexDirection: 'column' }}>
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
                  <div style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    gap: 8, margin: '10px 6px', padding: '8px 12px', borderRadius: 8,
                    background: 'rgba(217, 45, 32, 0.06)', border: '1px solid rgba(217, 45, 32, 0.15)',
                  }}>
                    <span style={{ color: 'rgb(217, 45, 32)', fontSize: 13 }}>{streamError}</span>
                    <button
                      onClick={() => useAgentStore.setState({ streamError: null })}
                      style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'rgb(217, 45, 32)', fontSize: 16, padding: '0 2px', lineHeight: 1 }}
                    ><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 6L6 18M6 6l12 12"/></svg></button>
                  </div>
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
    {/* ═══════════════ 桌面端布局（CSS 隐藏/显示）═══════════════ */}
    <div style={{ height: '100vh', display: isMobile ? 'none' : 'flex', background: 'white', overflow: 'hidden' }}>
      {/* ── 会话栏（可收起：展开=完整列表，收起=48px 图标 rail，仿 DeepSeek）── */}
      {collapsed ? (
        /* ── 收起态：图标 rail ── */
        <div style={{
          width: 48, flexShrink: 0, background: DS.sidebarBg,
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
            {settingsOpen && (
              <SettingsPopover
                anchorRef={settingsBtnRef}
                onProfile={() => { setProfileOpen(true); setSettingsOpen(false); }}
                onDocs={() => { navigate('/docs'); setSettingsOpen(false); }}
                onEditor={() => { navigate('/editor'); setSettingsOpen(false); }}
                onLogout={() => { logout(); setSettingsOpen(false); }}
              />
            )}
          </div>
        </div>
      ) : (
      <div style={{
        width: convWidth, flexShrink: 0, display: 'flex', flexDirection: 'column',
        background: DS.sidebarBg, transition: 'width 0.2s ease',
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
                  ><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M15 18l-6-6 6-6"/></svg> 收起</button>
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
                    <span style={{ fontSize: 10, color: DS.textCaption }}><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="M6 9l6 6 6-6"/></svg></span>
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
                padding: '0 12px', height: 42, fontSize: 15, color: DS.textSecondary, cursor: 'pointer',
                display: 'flex', alignItems: 'center', gap: 6,
              }}
            >{/** 设置 */}<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="3"/><path d="M10 2l1.5 2.5L14 3l-.5 2.8 2.5 1.2-1.5 2.3 2.3 1.5-2.8.5.5 2.8-2.5-1.2L10 17l-.5-2.8-2.5 1.2 1.5-2.3-2.3-1.5 2.8-.5L8.5 8.7 11 9.9z"/></svg> 设置</button>
            {settingsOpen && (
              <SettingsPopover
                anchorRef={settingsBtnRef}
                onProfile={() => { setProfileOpen(true); setSettingsOpen(false); }}
                onDocs={() => { navigate('/docs'); setSettingsOpen(false); }}
                onEditor={() => { navigate('/editor'); setSettingsOpen(false); }}
                onLogout={() => { logout(); setSettingsOpen(false); }}
              />
            )}
          </div>
        </div>
      </div>
      )}

      {/* 拖拽把手（6px 宽 + 竖线纹理提示可拖拽） */}
      <div
        onMouseDown={handleConvDrag}
        style={{
          width: 6, cursor: 'col-resize', background: 'transparent', flexShrink: 0,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          transition: 'background 0.15s',
        }}
        onMouseEnter={(e) => { (e.target as HTMLElement).style.background = DS.brand; }}
        onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
      >
        <div style={{ width: 2, height: 20, borderRadius: 1, background: 'rgba(0,0,0,0.12)' }} />
      </div>
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
            {/* 快捷提示 chips */}
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, justifyContent: 'center', maxWidth: DS.maxContent, padding: '0 16px' }}>
              {['帮我设计一个科幻分镜', '生成一张概念海报', '优化现有分镜脚本', '制作短视频方案'].map((t) => (
                <button
                  key={t}
                  onClick={() => {
                    const st = useAgentStore.getState();
                    if (!st.activeConversationId) {
                      st.createConversation().then(() => {
                        setTimeout(() => useAgentStore.getState().sendMessage(t), 200);
                      });
                    } else {
                      st.sendMessage(t);
                    }
                  }}
                  style={{
                    padding: '8px 16px', borderRadius: 20, border: `1px solid ${DS.border}`,
                    background: 'white', fontSize: 13, color: DS.textSecondary, cursor: 'pointer',
                    transition: 'border-color 0.15s, color 0.15s',
                  }}
                  onMouseEnter={(e) => { const el = e.currentTarget; el.style.borderColor = DS.brand; el.style.color = DS.brand; }}
                  onMouseLeave={(e) => { const el = e.currentTarget; el.style.borderColor = DS.border; el.style.color = DS.textSecondary; }}
                >{t}</button>
              ))}
            </div>
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
                ><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 3h7l2 3h9v13H3z"/></svg> 产出素材{assets && assets.total > 0 ? ` (${assets.total})` : ''}</button>
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
              <div style={{ maxWidth: DS.maxContent, margin: '0 auto', padding: '24px 16px 16px', display: 'flex', flexDirection: 'column' }}>
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
                  <div style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    gap: 10, margin: '10px 6px', padding: '10px 14px', borderRadius: 10,
                    background: 'rgba(217, 45, 32, 0.06)', border: '1px solid rgba(217, 45, 32, 0.15)',
                  }}>
                    <span style={{ color: 'rgb(217, 45, 32)', fontSize: 14 }}>
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ verticalAlign: -2, marginRight: 6 }}><circle cx="12" cy="12" r="10"/><path d="M12 8v4M12 16h.01"/></svg>
                      {streamError}
                    </span>
                    <button
                      onClick={() => useAgentStore.setState({ streamError: null })}
                      style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'rgb(217, 45, 32)', fontSize: 18, padding: '0 4px', lineHeight: 1 }}
                    ><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 6L6 18M6 6l12 12"/></svg></button>
                  </div>
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
    </>
  );
}

function headerBtn(opacity: number): React.CSSProperties {
  return {
    border: 'none', background: 'transparent', color: DS.textSecondary, fontSize: 14,
    cursor: opacity === 1 ? 'pointer' : 'not-allowed', padding: '6px 10px', borderRadius: 8, opacity,
    display: 'flex', alignItems: 'center', gap: 4,
  };
}

/** 收起态 rail 图标按钮 */
const railIconBtn: React.CSSProperties = {
  width: 36, height: 36, borderRadius: 10, border: 'none',
  background: 'transparent', fontSize: 18, cursor: 'pointer',
  display: 'flex', alignItems: 'center', justifyContent: 'center',
};
