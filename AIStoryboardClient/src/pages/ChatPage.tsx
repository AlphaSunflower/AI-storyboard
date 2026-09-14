import { useCallback, useEffect, useRef, useState } from 'react';
import { AgentConversationList } from '../components/agent/AgentConversationList';
import { LiveOrb } from '../components/ui/live-orb';
import { MessageBubble } from '../components/agent/MessageBubble';


import { VideoPlanCard } from '../components/agent/VideoPlanCard';
import { ConfirmResultCard } from '../components/agent/ConfirmResultCard';
import { HitlPromptCard } from '../components/agent/HitlPromptCard';
import { isSceneReview } from '../components/agent/SceneReviewCard';
import { GenerationCenterPanel } from '../components/agent/GenerationCenterPanel';
import { EmbeddedAppWindow, type EmbeddedAppDescriptor } from '../components/agent/EmbeddedAppWindow';
import { GenerationRecordsModal } from '../components/agent/GenerationRecordsModal';
import { ChatComposer, DS } from '../components/agent/ChatComposer';
import { ProjectDropdown } from '../components/layout/ProjectDropdown';
import { AssetLibraryPanel } from '../components/asset/AssetLibraryPanel';
import { PersonalInfoModal } from '../components/agent/PersonalInfoModal';
import { MobileHeader } from '../components/agent/MobileHeader';
import { MobileSidebarContent } from '../components/agent/MobileSidebarContent';
import { ConversationSearchModal } from '../components/agent/ConversationSearchModal';
import { Moon } from 'lucide-react';
import { X, FolderKanban, PanelLeftClose, ChevronDown, Info, Frame, PenLine, LayoutGrid, User, LogOut, ArrowDown, FileText, Library, Search } from 'lucide-react';
import { MobileBottomNav } from '../components/layout/MobileBottomNav';
import { useIsMobile } from '../hooks/useIsMobile';
import { useAuthStore } from '../stores/authStore';
import { useProjectStore, getLastOpenedProjectId } from '../stores/projectStore';
import { useAgentStore } from '../stores/agentStore';
import {
  buildSceneReviewPrompt,
  isStoryboardReferenceMessage,
} from '../lib/embeddedAppMessages';
import MoonLogo from '../components/agent/MoonLogo';
import AmbientGlow from '../components/AmbientGlow';

/** 待选人工介入卡占位消息（metadata.kind='hitl' 且 pending）：过滤掉，避免与流内卡片重复渲染 formContent。 */
const isPendingHitl = (m: { metadata?: { kind?: string; hitl?: { pending?: boolean } } }) =>
  m.metadata?.kind === 'hitl' && !!m.metadata.hitl?.pending;

/**
 * 独立 AI 对话页（/chat）——仿 DeepSeek 桌面端：
 * 最左图标导航（项目/AI资产库/左下角设置）｜会话列表（可拖宽）｜主区：
 * 首次对话 = 名称居中 + hero 输入卡；有会话 = 头部名称 + 748px 消息列 + 底部输入卡。
 */
export function ChatPage() {
  const isMobile = useIsMobile();
  const logout = useAuthStore((s) => s.logout);
  const user = useAuthStore((s) => s.user);
  const userName = user?.displayName || '未登录';
  const currentProject = useProjectStore((s) => s.currentProject);
  const newConversation = useAgentStore((s) => s.newConversation);
  const {
    messages, streaming, waitingHumanInput, waitingVideoPlan, streamError, workflowHint,
    confirmResult, conversations, activeConversationId,
    renameConversation,
  } = useAgentStore();
  const [assetsOpen, setAssetsOpen] = useState(false);       // AI资产库（rail 🧩）
  const [recordsOpen, setRecordsOpen] = useState(false);         // 生成记录（右上角 History）
  const [searchOpen, setSearchOpen] = useState(false);           // 全文+标题搜索（会话栏搜索图标）
  const [projectOpen, setProjectOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [generationCenterOpen, setGenerationCenterOpen] = useState(false);
  const [embeddedAppTabs, setEmbeddedAppTabs] = useState<EmbeddedAppDescriptor[]>([]);
  const [activeEmbeddedAppKey, setActiveEmbeddedAppKey] = useState<EmbeddedAppDescriptor['key'] | null>(null);
  const [embeddedAppLayout, setEmbeddedAppLayout] = useState({ width: 420, height: 0, fullscreen: false });
  const [showScrollDown, setShowScrollDown] = useState(false);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState('');
  const titleSavingRef = useRef(false);
  const [convWidth, setConvWidth] = useState(240);
  const [collapsed, setCollapsed] = useState(false); // 侧栏收起：仅显示图标 rail
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false); // 手机端 overlay 侧栏
  const [embed, setEmbed] = useState(false);             // iframe 紧凑模式（画布执行服务小窗）
  const [embedConvOpen, setEmbedConvOpen] = useState(false); // 紧凑模式会话列表 overlay
  const scrollRef = useRef<HTMLDivElement>(null);
  const nearBottomRef = useRef(true);
  const loadedRef = useRef(false);
  const projectBtnRef = useRef<HTMLButtonElement>(null);

  // 回到最新消息（对话上翻较多时，消息中间出现向下按钮）
  const scrollToBottom = () => {
    const el = scrollRef.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  };

  const buildCanvasTab = useCallback((): EmbeddedAppDescriptor | null => {
    const token = localStorage.getItem('accessToken');
    if (!token) return null;
    let userId = '';
    let name = '';
    try {
      const userInfo = JSON.parse(localStorage.getItem('user') ?? '{}') as { userId?: string; displayName?: string };
      userId = userInfo.userId ?? '';
      name = userInfo.displayName ?? '';
    } catch { /* ignore */ }
    const base = (import.meta.env.VITE_CANVAS_URL as string | undefined)?.trim().replace(/\/$/, '') || 'http://localhost:3000';
    const params = new URLSearchParams({ token });
    if (userId) params.set('userId', userId);
    if (name) params.set('name', name);
    const projectId = currentProject?.id;
    const hash = projectId ? `#project=${encodeURIComponent(projectId)}` : '';
    return { key: 'canvas', title: '画布', url: `${base}?${params.toString()}${hash}`, projectId };
  }, [currentProject?.id]);

  const buildEditorTab = useCallback((): EmbeddedAppDescriptor | null => {
    if (!currentProject?.id) return null;
    const params = new URLSearchParams({ embedded: '1', projectId: currentProject.id });
    return {
      key: 'editor',
      title: currentProject.name ? `分镜编辑器 · ${currentProject.name}` : '分镜编辑器',
      url: `/editor?${params.toString()}`,
      projectId: currentProject.id,
    };
  }, [currentProject?.id, currentProject?.name]);

  // 应用子窗加载对应路径；画布通过 URL 注入 ai分镜 token，避免在 iframe 内再次登录。
  const openEmbeddedApp = (key: EmbeddedAppDescriptor['key']) => {
    let nextTab: EmbeddedAppDescriptor | null;
    if (key === 'canvas') nextTab = buildCanvasTab();
    else if (key === 'editor') nextTab = buildEditorTab();
    else nextTab = { key: 'docs', title: '使用文档', url: '/docs' };
    if (!nextTab) return;
    setEmbeddedAppTabs((tabs) => {
      const existing = tabs.findIndex((tab) => tab.key === key);
      if (existing < 0) return [...tabs, nextTab];
      return tabs.map((tab) => tab.key === key ? nextTab : tab);
    });
    setActiveEmbeddedAppKey(key);
  };

  // 切换项目时同步已打开的项目型子网站；文档标签不受影响。
  useEffect(() => {
    setEmbeddedAppTabs((tabs) => {
      if (tabs.length === 0) return tabs;
      let changed = false;
      const nextTabs = tabs.map((tab) => {
        if (tab.key === 'editor') {
          const next = buildEditorTab();
          if (next && next.url !== tab.url) {
            changed = true;
            return next;
          }
        } else if (tab.key === 'canvas') {
          const next = buildCanvasTab();
          if (next && next.url !== tab.url) {
            changed = true;
            return next;
          }
        }
        return tab;
      });
      return changed ? nextTabs : tabs;
    });
  }, [buildCanvasTab, buildEditorTab]);

  const activeEmbeddedApp = embeddedAppTabs.find((tab) => tab.key === activeEmbeddedAppKey) ?? null;
  const appWindowOpen = Boolean(activeEmbeddedApp && !embeddedAppLayout.fullscreen);
  const contentRightReserve = appWindowOpen
    ? Math.max(embeddedAppLayout.width + 32, generationCenterOpen ? 420 : 0)
    : (generationCenterOpen ? 420 : 0);

  const closeEmbeddedApp = useCallback(() => {
    setEmbeddedAppTabs([]);
    setActiveEmbeddedAppKey(null);
    setEmbeddedAppLayout((current) => ({ ...current, fullscreen: false }));
  }, []);

  const closeEmbeddedTab = (key: string) => {
    const closingIndex = embeddedAppTabs.findIndex((tab) => tab.key === key);
    if (closingIndex < 0) return;
    const nextTabs = embeddedAppTabs.filter((tab) => tab.key !== key);
    setEmbeddedAppTabs(nextTabs);
    if (activeEmbeddedAppKey === key) {
      setActiveEmbeddedAppKey(nextTabs[Math.min(closingIndex, nextTabs.length - 1)]?.key ?? null);
    }
    if (nextTabs.length === 0) {
      setEmbeddedAppLayout((current) => ({ ...current, fullscreen: false }));
    }
  };

  // 编辑器 iframe 提交分镜后，直接进入指定分镜审核并关闭应用窗口。
  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin || !isStoryboardReferenceMessage(event.data)) return;
      const sourceMatches = Array.from(document.querySelectorAll('iframe'))
        .some((frame) => frame.contentWindow === event.source);
      if (!sourceMatches) return;
      if (!currentProject?.id || event.data.projectId !== currentProject.id) return;
      void useAgentStore.getState().sendMessage(
        buildSceneReviewPrompt(event.data.scenes),
        { routingHint: 'intent-scene-review' },
      );
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [currentProject?.id]);

  // 登录守卫 + iframe 自登录（/chat?embed=1&token=&userId=&name=）：跨端口 iframe 靠 URL 注入 token
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const token = params.get('token');
    if (params.get('embed') === '1') setEmbed(true);
    if (token) {
      const refresh = params.get('refresh');
      const userId = params.get('userId');
      const name = params.get('name');
      localStorage.setItem('accessToken', token);
      if (refresh) localStorage.setItem('refreshToken', refresh);
      if (userId && name) localStorage.setItem('user', JSON.stringify({ userId, displayName: name }));
      window.history.replaceState({}, '', '/chat');
    }
    useAuthStore.getState().checkAuth();
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
          const lastId = getLastOpenedProjectId();
          const target = list.find((p) => p.id === lastId) ?? list[0];
          useProjectStore.getState().loadProject(target.id);
        }
      })
      .catch(() => { /* 静默 */ });
  }, []);

  // 项目切换（含首次确定）→ 清掉旧会话状态并加载该项目会话列表。
  // ProjectDropdown 选项目只更新 currentProject，不触发会话加载——这里统一兜底。
  const projectId = currentProject?.id;
  useEffect(() => {
    if (!projectId) return;
    useAgentStore.setState({ activeConversationId: null, messages: [], generating: {}, streaming: false, workflowHint: '' });
    useAgentStore.getState().loadConversations().catch(() => { /* 静默 */ });
    useAgentStore.getState().refetchStoryboardVideoQueue(projectId).catch(() => { /* 静默 */ });
    useAgentStore.getState().refetchStoryboardImageQueue(projectId).catch(() => { /* 静默 */ });
  }, [projectId]);

  // 新消息自动滚底（近底才跟随）
  useEffect(() => {
    const el = scrollRef.current;
    if (el && nearBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [messages, streaming, waitingHumanInput, waitingVideoPlan]);

  // 自动审查属于关键节点：即使上翻了历史消息，也把新展开的审查卡带入视口。
  useEffect(() => {
    if (!waitingHumanInput || !isSceneReview(waitingHumanInput)) return;
    const id = window.requestAnimationFrame(() => {
      const el = scrollRef.current;
      if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
    });
    return () => window.cancelAnimationFrame(id);
  }, [waitingHumanInput]);

  const currentTitle = conversations.find((c) => c.id === activeConversationId)?.title ?? '未选择对话';
  const isEmpty = messages.length === 0 && !streaming && !waitingHumanInput && !waitingVideoPlan;

  const startTitleEdit = () => {
    if (!activeConversationId) return;
    setTitleDraft(currentTitle);
    setEditingTitle(true);
  };
  const saveTitle = async () => {
    if (titleSavingRef.current) return;
    titleSavingRef.current = true;
    const t = titleDraft.trim();
    setEditingTitle(false);
    if (activeConversationId && t && t !== currentTitle) {
      await renameConversation(activeConversationId, t);
    }
    setTimeout(() => { titleSavingRef.current = false; }, 0);
  };
  const cancelTitleEdit = () => setEditingTitle(false);

  // embed（画布小窗 iframe）强制桌面布局：小窗宽度常 <768px，useIsMobile 会判成手机端，需锁定桌面紧凑模式
  const showMobile = isMobile && !embed;

  // 手机端：选择会话后自动关闭侧栏
  const handleMobileSelectConversation = (id: string) => {
    useAgentStore.getState().selectConversation(id);
    setMobileSidebarOpen(false);
  };

  // 手机端：新建对话后自动关闭侧栏
  const handleMobileNewConversation = async () => {
    newConversation();
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

  // 全局阻止浏览器默认打开文件；实际上传仅由 ChatComposer 接收。
  useEffect(() => {
    const block = (e: DragEvent) => e.preventDefault();
    window.addEventListener('dragover', block);
    window.addEventListener('drop', block);
    return () => {
      window.removeEventListener('dragover', block);
      window.removeEventListener('drop', block);
    };
  }, []);

  return (
    <>
    {/* 页面氛围光晕（fixed 覆盖手机/桌面两分支，pointer-events none） */}
    <AmbientGlow />
    {/* ═══════════════ 手机端布局（CSS 隐藏/显示，避免 hooks 协调错误）═══════════════ */}
      <div
      style={{ height: '100vh', display: showMobile ? 'flex' : 'none', flexDirection: 'column', background: 'white', overflow: 'hidden' }}
    >
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
                onOpenRecords={() => { setRecordsOpen(true); setMobileSidebarOpen(false); }}
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
                    useAgentStore.getState().sendMessage(t);
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
                setShowScrollDown(!nearBottomRef.current && messages.length > 0);
              }}
              style={{ flex: 1, minHeight: 0, overflowY: 'auto', background: 'white' }}
            >
              <div style={{ maxWidth: DS.maxContent, margin: '0 auto', padding: '16px 12px', display: 'flex', flexDirection: 'column' }}>
                {messages.filter((m) => !isPendingHitl(m)).map((m) => (
                  <div key={m.id} style={{ animation: 'chat-msg-in 0.35s ease-out' }}>
                    <MessageBubble
                      role={m.role}
                      content={m.content}
                      metadata={m.metadata}
                      variant="deepseek"
                      createdAt={m.createdAt}
                      streaming={streaming && m.role === 'assistant' && m.id === messages[messages.length - 1]?.id}
                    />
                  </div>
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
                
                {waitingHumanInput && <HitlPromptCard info={waitingHumanInput} />}
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
                    ><X size={13} strokeWidth={2} /></button>
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
      </div>
    {/* ═══════════════ 桌面端布局（CSS 隐藏/显示）═══════════════ */}
    <div
      style={{ height: '100vh', display: showMobile ? 'none' : 'flex', background: 'white', overflow: 'hidden', position: 'relative' }}
    >
      {/* ── 会话栏（可收起：展开=完整列表，收起=48px 图标 rail，仿 DeepSeek）── */}
      {!embed && (<>
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
              {...railHover}
            ><Moon size={18} strokeWidth={1.8} /></button>
            <div style={{ position: 'relative', width: '100%', display: 'flex', justifyContent: 'center' }}>
              <button
                ref={projectBtnRef}
                onClick={() => setProjectOpen(!projectOpen)}
                onMouseDown={(e) => e.stopPropagation()}
                title={currentProject?.name ?? '选择项目'}
                style={railIconBtn}
                {...railHover}
              >{/** 项目 */}<FolderKanban size={18} strokeWidth={1.8} /></button>
              <ProjectDropdown open={projectOpen} onClose={() => setProjectOpen(false)} anchor={projectBtnRef} />
            </div>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
            <button
              onClick={() => setProfileOpen(true)}
              title="个人信息"
              style={railIconBtn}
              {...railHover}
            >{/** 个人信息 */}<User size={18} strokeWidth={1.8} /></button>
            <button
              onClick={logout}
              title="退出登录"
              style={railIconBtn}
              {...railHover}
            >{/** 退出登录 */}<LogOut size={18} strokeWidth={1.8} /></button>
          </div>
        </div>
      ) : (
      <div style={{
        width: convWidth, flexShrink: 0, display: 'flex', flexDirection: 'column',
        background: DS.sidebarBg, transition: 'width 0.2s ease',
      }}>
        {/* 会话列表（toolbar 插槽注入 项目+AI资产库，位于新建对话上方；flex:1 内部滚动） */}
        <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
          <AgentConversationList
            width={convWidth}
            headerRight={
              <div style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}>
                <button
                  onClick={() => setSearchOpen(true)}
                  title="搜索对话（全文+标题）"
                  style={{
                    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                    width: 28, height: 28, border: 'none', background: 'transparent', cursor: 'pointer',
                    color: 'var(--color-muted)', borderRadius: 8, transition: 'background 0.15s, color 0.15s',
                  }}
                  onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; e.currentTarget.style.color = DS.brand; }}
                  onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'var(--color-muted)'; }}
                ><Search size={15} strokeWidth={1.8} /></button>
                <button
                  onClick={() => setCollapsed(true)}
                  title="收起侧边栏"
                  style={{
                    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                    width: 28, height: 28, border: 'none', background: 'transparent', cursor: 'pointer',
                    color: 'var(--color-muted)', borderRadius: 8, transition: 'background 0.15s, color 0.15s',
                  }}
                  onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; e.currentTarget.style.color = DS.brand; }}
                  onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'var(--color-muted)'; }}
                ><PanelLeftClose size={15} strokeWidth={1.8} /></button>
              </div>
            }
            toolbar={
              <>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: '8px 12px 12px' }}>
                {/* 项目选择（ghost 风格；弹层 portal 右对齐不被裁剪） */}
                <div style={{ position: 'relative', width: '100%' }}>
                  <button
                    ref={projectBtnRef}
                    onClick={() => setProjectOpen(!projectOpen)}
                    onMouseDown={(e) => e.stopPropagation()}
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
                    <span style={{ fontSize: 15 }}>{/** 项目 */}<FolderKanban size={15} strokeWidth={1.8} /></span>
                    <span style={{ flex: 1, textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {currentProject?.name ?? '选择项目'}
                    </span>
                    <span style={{ fontSize: 10, color: DS.textCaption }}><ChevronDown size={10} strokeWidth={2.5} /></span>
                  </button>
                  <ProjectDropdown open={projectOpen} onClose={() => setProjectOpen(false)} anchor={projectBtnRef} />
                </div>
              </div>
              </>
            }
          />
        </div>

        {/* 底部：用户资料 + 退出登录 */}
        <div style={{
          padding: '6px 10px', borderTop: '1px solid var(--color-hairline)',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <button
            onClick={() => setProfileOpen(true)}
            title="个人信息"
            style={{
              flex: 1, display: 'flex', alignItems: 'center', gap: 8, minWidth: 0,
              border: 'none', background: 'transparent', borderRadius: 10,
              padding: '6px 10px', fontSize: 15, color: DS.ink, cursor: 'pointer',
            }}
            onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
          >
            <User size={18} strokeWidth={1.8} />
            <span style={{ flex: 1, textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{userName}</span>
          </button>
          <button
            onClick={logout}
            title="退出登录"
            style={{
              display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
              width: 34, height: 34, border: 'none', background: 'transparent', borderRadius: 8, cursor: 'pointer', color: 'var(--color-muted)',
            }}
            onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(217, 45, 32, 0.08)'; e.currentTarget.style.color = '#d92d20'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'var(--color-muted)'; }}
          >
            <LogOut size={18} strokeWidth={1.8} />
          </button>
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
      </>)}

      {/* ── embed 紧凑模式（画布小窗 iframe）：会话切换按钮 + 会话列表 overlay ── */}
      {embed && embedConvOpen && (
            <div
              onClick={() => setEmbedConvOpen(false)}
              style={{ position: 'absolute', inset: 0, zIndex: 60, background: 'rgba(0,0,0,0.25)' }}
            >
              <div
                onClick={(e) => e.stopPropagation()}
                style={{
                  position: 'absolute', top: 0, left: 0, bottom: 0,
                  width: Math.min(convWidth, 280), background: DS.sidebarBg,
                  boxShadow: '6px 0 24px rgba(0,0,0,0.12)', display: 'flex',
                }}
              >
                <AgentConversationList
                  width={Math.min(convWidth, 280)}
                  hideHeader
                  toolbar={
                    <div style={{ display: 'flex', justifyContent: 'flex-end', padding: '6px 12px 0' }}>
                      <button
                        onClick={() => setEmbedConvOpen(false)}
                        title="收起"
                        style={{ border: 'none', background: 'transparent', cursor: 'pointer', fontSize: 13, color: 'var(--color-muted)', padding: '4px 8px', borderRadius: 8 }}
                      ><PanelLeftClose size={14} strokeWidth={2} /> 收起</button>
                    </div>
                  }
                  onSelectOverride={(id) => { useAgentStore.getState().selectConversation(id); setEmbedConvOpen(false); }}
                />
              </div>
            </div>
      )}

      {/* ── 主对话区 ── */}
      <div
        style={{
          flex: 1,
          minWidth: 0,
          display: 'flex',
          flexDirection: 'column',
          position: 'relative',
        }}
      >
        {isEmpty ? (
          /* 首次对话 hero：名称居中 + 输入卡居中（DeepSeek harness 空态） */
          <div style={{
            flex: 1, display: 'flex', flexDirection: 'column',
            alignItems: 'center', justifyContent: 'center', gap: 28,
            paddingLeft: 16,
            paddingRight: !embed ? contentRightReserve + 16 : 16,
            transition: 'padding-right 0.22s ease',
          }}>
            {embed && (
              <button
                onClick={() => setEmbedConvOpen(true)}
                title="会话"
                style={{ position: 'absolute', top: 8, left: 8, border: 'none', background: 'transparent', color: DS.textSecondary, fontSize: 12, cursor: 'pointer', padding: '4px 6px', borderRadius: 8, display: 'flex', alignItems: 'center', gap: 3 }}
              ><Moon size={12} strokeWidth={1.8} /> 会话</button>
            )}
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
                    useAgentStore.getState().sendMessage(t);
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
            {/* 头部：会话名称 + 操作（embed 小窗内收紧字号与留白） */}
            <div style={{
              padding: embed ? '8px 12px' : '14px 28px', borderBottom: `1px solid ${DS.border}`,
              display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0,
              paddingRight: !embed ? contentRightReserve + 16 : 12,
              transition: 'padding-right 0.22s ease',
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0, flex: 1 }}>
                {embed && (
                  <button
                    onClick={() => setEmbedConvOpen(true)}
                    title="会话"
                    style={{ border: 'none', background: 'transparent', color: DS.textSecondary, fontSize: 12, cursor: 'pointer', padding: '4px 6px', borderRadius: 8, display: 'flex', alignItems: 'center', gap: 3, flexShrink: 0 }}
                  ><Moon size={12} strokeWidth={1.8} /> 会话</button>
                )}
                {editingTitle ? (
                  <input
                    autoFocus
                    value={titleDraft}
                    onChange={(e) => setTitleDraft(e.target.value)}
                    onBlur={saveTitle}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') { e.preventDefault(); saveTitle(); }
                      else if (e.key === 'Escape') cancelTitleEdit();
                    }}
                    style={{
                      fontSize: embed ? 13 : 18, fontWeight: 600, color: DS.ink, minWidth: 0,
                      border: `1px solid ${DS.brand}`, borderRadius: 8, padding: '2px 8px',
                      outline: 'none', background: 'white', boxSizing: 'border-box', fontFamily: 'inherit',
                    }}
                  />
                ) : (
                  <button
                    onClick={startTitleEdit}
                    title="点击重命名"
                    style={{
                      display: 'inline-flex', alignItems: 'center', gap: 4, minWidth: 0,
                      maxWidth: '100%', border: 'none', background: 'transparent',
                      padding: embed ? '2px 6px' : '4px 8px', borderRadius: 8, cursor: 'pointer',
                      transition: 'background 0.15s',
                    }}
                    onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; }}
                    onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
                  >
                    <span style={{ fontSize: embed ? 13 : 18, fontWeight: 600, color: DS.ink, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {currentTitle}
                    </span>
                  </button>
                )}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                <AppSwitcher
                  onCanvas={() => openEmbeddedApp('canvas')}
                  onEditor={() => openEmbeddedApp('editor')}
                  onDocs={() => openEmbeddedApp('docs')}
                />
                <button
                  onClick={() => setAssetsOpen(true)}
                  title="AI资产库"
                  style={headerBtn(1, embed ? 12 : 14)}
                  onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; e.currentTarget.style.color = DS.brand; }}
                  onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = DS.textSecondary; }}
                ><Library size={16} strokeWidth={1.8} /> AI资产库</button>
              </div>
            </div>

            {/* 消息列：748px 居中 */}
            <div
              ref={scrollRef}
              onScroll={(e) => {
                const el = e.target as HTMLElement;
                nearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
                setShowScrollDown(!nearBottomRef.current && messages.length > 0);
              }}
              style={{
                flex: 1,
                minHeight: 0,
                overflowY: 'auto',
                background: 'white',
                paddingRight: !embed ? contentRightReserve : 0,
                transition: 'padding-right 0.22s ease',
              }}
            >
              <div style={{ maxWidth: DS.maxContent, margin: '0 auto', padding: '24px 16px 16px', display: 'flex', flexDirection: 'column' }}>
                {messages.filter((m) => !isPendingHitl(m)).map((m) => (
                  <div key={m.id} style={{ animation: 'chat-msg-in 0.35s ease-out' }}>
                    <MessageBubble
                      role={m.role}
                      content={m.content}
                      metadata={m.metadata}
                      variant="deepseek"
                      createdAt={m.createdAt}
                      streaming={streaming && m.role === 'assistant' && m.id === messages[messages.length - 1]?.id}
                    />
                  </div>
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
                
                {waitingHumanInput && <HitlPromptCard info={waitingHumanInput} />}
                {waitingVideoPlan && <VideoPlanCard info={waitingVideoPlan} />}
                {confirmResult && <ConfirmResultCard />}
                {streamError && (
                  <div style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    gap: 10, margin: '10px 6px', padding: '10px 14px', borderRadius: 10,
                    background: 'rgba(217, 45, 32, 0.06)', border: '1px solid rgba(217, 45, 32, 0.15)',
                  }}>
                    <span style={{ color: 'rgb(217, 45, 32)', fontSize: 14 }}>
                      <Info size={15} strokeWidth={2} style={{ marginRight: 6 }} />
                      {streamError}
                    </span>
                    <button
                      onClick={() => useAgentStore.setState({ streamError: null })}
                      style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'rgb(217, 45, 32)', fontSize: 18, padding: '0 4px', lineHeight: 1 }}
                    ><X size={14} strokeWidth={2} /></button>
                  </div>
                )}
              </div>
            </div>

            {/* 底部输入卡 */}
            <div style={{
              padding: '8px 16px 16px',
              paddingRight: !embed ? contentRightReserve + 16 : 16,
              background: 'white',
              transition: 'padding-right 0.22s ease',
            }}>
              <ChatComposer />
            </div>
          </>
        )}
        {showScrollDown && !isEmpty && (
          <button
            onClick={scrollToBottom}
            title="回到最新"
            aria-label="回到最新"
            style={{
              position: 'absolute', bottom: 176,
              left: !embed && contentRightReserve ? `calc(50% - ${contentRightReserve / 2}px)` : '50%',
              transform: 'translateX(-50%)', zIndex: 60,
              width: 34, height: 34, borderRadius: '50%',
              border: '1px solid var(--color-hairline)', background: 'white', color: 'var(--color-muted)',
              cursor: 'pointer', boxShadow: '0 4px 16px rgba(0,0,0,0.12)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              transition: 'color 0.15s, border-color 0.15s',
            }}
            onMouseEnter={(e) => { e.currentTarget.style.color = 'var(--color-primary)'; e.currentTarget.style.borderColor = 'var(--color-primary)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.color = 'var(--color-muted)'; e.currentTarget.style.borderColor = 'var(--color-hairline)'; }}
          ><ArrowDown size={16} strokeWidth={2} /></button>
        )}
      </div>

      {assetsOpen && <AssetLibraryPanel onClose={() => setAssetsOpen(false)} />}
      {profileOpen && <PersonalInfoModal onClose={() => setProfileOpen(false)} />}
      {!embed && !embeddedAppLayout.fullscreen && (
        <GenerationCenterPanel
          onOpenChange={setGenerationCenterOpen}
          bottom={appWindowOpen ? 20 : 104}
          onOpenRecords={() => setRecordsOpen(true)}
        />
      )}
      {!embed && embeddedAppTabs.length > 0 && activeEmbeddedAppKey && (
        <EmbeddedAppWindow
          tabs={embeddedAppTabs}
          activeKey={activeEmbeddedAppKey}
          onSelectTab={(key) => setActiveEmbeddedAppKey(key as EmbeddedAppDescriptor['key'])}
          onCloseTab={closeEmbeddedTab}
          onCloseWindow={closeEmbeddedApp}
          onLayoutChange={setEmbeddedAppLayout}
        />
      )}
    </div>
    <GenerationRecordsModal open={recordsOpen} onClose={() => setRecordsOpen(false)} />
    <ConversationSearchModal
      open={searchOpen}
      onClose={() => setSearchOpen(false)}
      onSelectConversation={(id) => { void useAgentStore.getState().selectConversation(id); }}
    />
    </>
  );
}

function headerBtn(opacity: number, fontSize = 14): React.CSSProperties {
  return {
    border: 'none', background: 'transparent', color: DS.textSecondary, fontSize,
    cursor: opacity === 1 ? 'pointer' : 'not-allowed', padding: '6px 10px', borderRadius: 8, opacity,
    display: 'flex', alignItems: 'center', gap: 4,
  };
}

/**
 * 应用入口：主界面右上角与会话标题使用同一行，菜单承载画布、分镜编辑器和使用文档。
 */
function AppSwitcher({
  onCanvas,
  onEditor,
  onDocs,
}: {
  onCanvas: () => void;
  onEditor: () => void;
  onDocs: () => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const items = [
    { label: '画布', icon: <Frame size={15} strokeWidth={1.8} />, action: onCanvas },
    { label: '分镜编辑器', icon: <PenLine size={15} strokeWidth={1.8} />, action: onEditor },
    { label: '使用文档', icon: <FileText size={15} strokeWidth={1.8} />, action: onDocs },
  ];

  return (
    <div ref={rootRef} style={{ position: 'relative' }}>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        title="应用"
        aria-haspopup="menu"
        aria-expanded={open}
        style={{
          ...headerBtn(1, 14),
          background: open ? DS.hover : 'transparent',
          color: open ? DS.brand : DS.textSecondary,
        }}
        onMouseEnter={(event) => {
          event.currentTarget.style.background = DS.hover;
          event.currentTarget.style.color = DS.brand;
        }}
        onMouseLeave={(event) => {
          event.currentTarget.style.background = open ? DS.hover : 'transparent';
          event.currentTarget.style.color = open ? DS.brand : DS.textSecondary;
        }}
      >
        <LayoutGrid size={16} strokeWidth={1.8} />
        <span>应用</span>
      </button>

      {open && (
        <div
          role="menu"
          aria-label="应用"
          style={{
            position: 'absolute',
            top: 'calc(100% + 6px)',
            right: 0,
            width: 176,
            padding: 8,
            display: 'flex',
            flexDirection: 'column',
            gap: 4,
            border: '1px solid var(--color-hairline)',
            borderRadius: 12,
            background: 'white',
            boxShadow: '0 12px 32px rgba(15, 17, 21, 0.14)',
            zIndex: 150,
          }}
        >
          {items.map((item) => (
            <button
              key={item.label}
              type="button"
              role="menuitem"
              onClick={() => {
                setOpen(false);
                item.action();
              }}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                width: '100%',
                padding: '9px 10px',
                border: 'none',
                borderRadius: 8,
                background: 'transparent',
                color: DS.ink,
                fontSize: 13,
                textAlign: 'left',
                cursor: 'pointer',
              }}
              onMouseEnter={(event) => {
                event.currentTarget.style.background = DS.hover;
                event.currentTarget.style.color = DS.brand;
              }}
              onMouseLeave={(event) => {
                event.currentTarget.style.background = 'transparent';
                event.currentTarget.style.color = DS.ink;
              }}
            >
              {item.icon}
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** 收起态 rail 图标按钮 */
const railIconBtn: React.CSSProperties = {
  width: 36, height: 36, borderRadius: 10, border: 'none',
  background: 'transparent', fontSize: 18, cursor: 'pointer',
  display: 'flex', alignItems: 'center', justifyContent: 'center',
};

/** 收起态 rail 图标按钮悬停高亮 */
const railHover = {
  onMouseEnter: (e: React.MouseEvent) => { const el = e.currentTarget as HTMLElement; el.style.background = DS.hover; el.style.color = DS.brand; },
  onMouseLeave: (e: React.MouseEvent) => { const el = e.currentTarget as HTMLElement; el.style.background = 'transparent'; el.style.color = ''; },
};
