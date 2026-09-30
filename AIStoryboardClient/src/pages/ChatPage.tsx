import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AgentConversationList } from '../components/agent/AgentConversationList';
import { LiveOrb } from '../components/ui/live-orb';
import { MessageBubble } from '../components/agent/MessageBubble';
import { AgentTaskCompletionNotices } from '../components/agent/AgentTaskCompletionNotices';
import { AgentTaskDrawer } from '../components/agent/AgentTaskDrawer';


import { VideoPlanCard } from '../components/agent/VideoPlanCard';
import { ConfirmResultCard } from '../components/agent/ConfirmResultCard';
import { HitlPromptCard } from '../components/agent/HitlPromptCard';
import { GenerationCenterPanel, type GenerationCenterTab } from '../components/agent/GenerationCenterPanel';
import { ChatAppActions } from '../components/agent/apps/ChatAppActions';
import { ChatAppsHost } from '../components/agent/apps/ChatAppsHost';
import { ChatAppsProvider } from '../components/agent/apps/ChatAppsContext';
import { useChatApps } from '../components/agent/apps/useChatApps';
import { GenerationRecordsModal } from '../components/agent/GenerationRecordsModal';
import { ChatComposer, DS } from '../components/agent/ChatComposer';
import { ChatDockShell, type ChatDockPresentation } from '../components/agent/ChatDockShell';
import {
  resolveChatDockMode,
  resolveChatSidebarCollapseWidth,
  resolveChatSplitMaxWidth,
  shouldRestoreAutoCollapsedSidebar,
  type ChatDockMode,
} from '../components/agent/chatDockLayout';
import { ProjectDropdown } from '../components/layout/ProjectDropdown';
import { PersonalInfoModal } from '../components/agent/PersonalInfoModal';
import { MobileHeader } from '../components/agent/MobileHeader';
import { MobileSidebarContent } from '../components/agent/MobileSidebarContent';
import { ConversationSearchModal } from '../components/agent/ConversationSearchModal';
import { Moon } from 'lucide-react';
import { X, FolderKanban, PanelLeftClose, ChevronDown, Info, User, LogOut, ArrowDown, Library, Search } from 'lucide-react';
import { MobileBottomNav } from '../components/layout/MobileBottomNav';
import { useIsMobile } from '../hooks/useIsMobile';
import { usePresence } from '../hooks/usePresence';
import { useAuthStore } from '../stores/authStore';
import { useProjectStore, getLastOpenedProjectId } from '../stores/projectStore';
import { useAgentStore } from '../stores/agentStore';
import {
  buildCanvasRequestContext,
  loadCanvasEditContext,
  parseCanvasEditContext,
  rebindCanvasEditContext,
  resolveCanvasModePhase,
} from '../lib/canvasEditMode';
import type { CanvasModeReadyMessage, CanvasModeStatusMessage } from '../lib/embeddedAppMessages';
import { canvasIdFromUrl } from '../components/agent/apps/chatAppDefinitions';
import MoonLogo from '../components/agent/MoonLogo';
import AmbientGlow from '../components/AmbientGlow';
import { Frame } from 'lucide-react';
import '../styles/chat-interactions.css';
import '../styles/canvas-tech-chat.css';

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
  const navigate = useNavigate();
  const logout = useAuthStore((s) => s.logout);
  const user = useAuthStore((s) => s.user);
  const userName = user?.displayName || '未登录';
  const currentProject = useProjectStore((s) => s.currentProject);
  const canvasEditContext = useAgentStore((s) => s.canvasEditContext);
  const canvasBinding = useAgentStore((s) => s.canvasBinding);
  const canvasSession = useAgentStore((s) => s.canvasSession);
  const canvasExitPending = useAgentStore((s) => s.canvasExitPending);
  const resumeCanvasBinding = useAgentStore((s) => s.resumeCanvasBinding);
  const readyCanvasSession = useAgentStore((s) => s.readyCanvasSession);
  const exitCanvasModeNow = useAgentStore((s) => s.exitCanvasModeNow);
  const cancelCanvasBinding = useAgentStore((s) => s.cancelCanvasBinding);
  const leaveCanvasEditMode = useAgentStore((s) => s.leaveCanvasEditMode);
  const setCanvasBindingStatus = useAgentStore((s) => s.setCanvasBindingStatus);
  const newConversation = useAgentStore((s) => s.newConversation);
  const {
    messages, streaming, waitingHumanInput, waitingVideoPlan, streamError, workflowHint,
    confirmResult, conversations, activeConversationId,
    renameConversation, editAndResend,
  } = useAgentStore();
  const lastUserMessageId = [...messages].reverse().find((message) => message.role === 'user')?.id;
  const activeConversation = conversations.find((conversation) => conversation.id === activeConversationId);
  const conversationReadOnly = activeConversation?.status === 'archived'
    || activeConversation?.orchestrationMode === 'LEGACY';
  const canEditLastTurn = !conversationReadOnly && !streaming && !waitingHumanInput && !waitingVideoPlan
    && !canvasBinding && !canvasExitPending;
  const chatApps = useChatApps(currentProject);
  const [recordsOpen, setRecordsOpen] = useState(false);         // 生成记录（右上角 History）
  const [searchOpen, setSearchOpen] = useState(false);           // 全文+标题搜索（会话栏搜索图标）
  const [projectOpen, setProjectOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [generationCenterOpen, setGenerationCenterOpen] = useState(false);
  const [generationCenterTab, setGenerationCenterTab] = useState<GenerationCenterTab>('active');
  const [showScrollDown, setShowScrollDown] = useState(false);
  const [editingTitle, setEditingTitle] = useState(false);
  const openedCanvasSessionRef = useRef<string | null>(null);
  const [titleDraft, setTitleDraft] = useState('');
  const titleSavingRef = useRef(false);
  const [convWidth, setConvWidth] = useState(240);
  const [convResizing, setConvResizing] = useState(false);
  const [collapsed, setCollapsed] = useState(false); // 侧栏收起：仅显示图标 rail
  const [viewportWidth, setViewportWidth] = useState(() => window.innerWidth);
  const [dockMode, setDockMode] = useState<ChatDockMode>('split');
  const [dockPresentation, setDockPresentation] = useState<ChatDockPresentation>('pill');
  const [dockRequested, setDockRequested] = useState(false);
  const autoCollapsedSidebarRef = useRef(false);
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false); // 手机端 overlay 侧栏
  const [embed, setEmbed] = useState(() =>
    new URLSearchParams(window.location.search).get('embed') === '1'); // iframe 紧凑模式（画布执行服务小窗）
  const [embedConvOpen, setEmbedConvOpen] = useState(false); // 紧凑模式会话列表 overlay
  const mobileScrollRef = useRef<HTMLDivElement>(null);
  const desktopScrollRef = useRef<HTMLDivElement>(null);
  /** 手机/桌面两套布局同时挂载（display 切换），滚动容器必须按当前可见分支取——
   *  共用一个 ref 只指向最后挂载的桌面容器，手机端自动滚底/回到最新会全部失效 */
  const activeScrollEl = () => (isMobile && !embed ? mobileScrollRef : desktopScrollRef).current;
  const chatMainRef = useRef<HTMLDivElement>(null);
  const nearBottomRef = useRef(true);
  const loadedRef = useRef(false);
  const railProjectBtnRef = useRef<HTMLButtonElement>(null);
  const desktopProjectBtnRef = useRef<HTMLButtonElement>(null);
  const requestedCanvasContextRef = useRef(parseCanvasEditContext(window.location.search));

  useEffect(() => {
    useAgentStore.getState().startTaskStream();
    return () => useAgentStore.getState().stopTaskStream();
  }, []);

  useEffect(() => {
    const updateViewportWidth = () => setViewportWidth(window.innerWidth);
    window.addEventListener('resize', updateViewportWidth);
    return () => window.removeEventListener('resize', updateViewportWidth);
  }, []);

  // 回到最新消息（对话上翻较多时，消息中间出现向下按钮）
  const scrollToBottom = () => {
    const el = activeScrollEl();
    setShowScrollDown(false);
    nearBottomRef.current = true;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  };

  const handleThreadWheel = (e: React.WheelEvent<HTMLDivElement>) => {
    const el = e.currentTarget;
    const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    setShowScrollDown((current) => {
      if (e.deltaY > 1) return distanceToBottom >= 220;
      if (e.deltaY < -1) return false;
      return current;
    });
  };

  const handleThreadScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const el = e.currentTarget;
    const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    nearBottomRef.current = distanceToBottom < 80;
    if (distanceToBottom < 220) setShowScrollDown(false);
  };

  const appWindowOpen = !embed && chatApps.windowOpen;
  const {
    open: openChatApp,
    tabs: chatAppTabs,
    bindCanvasMode,
    releaseCanvasMode,
  } = chatApps;
  const canvasPhase = resolveCanvasModePhase(canvasEditContext, canvasBinding, canvasExitPending);
  const canvasTechActive = canvasPhase !== 'natural';
  const handleCanvasModeStatus = useCallback((message: CanvasModeStatusMessage) => {
    if (message.conversationId !== useAgentStore.getState().activeConversationId) return;
    if (message.state === 'bridge-connecting') setCanvasBindingStatus('正在连接画布服务…');
    else if (message.state === 'bridge-error') setCanvasBindingStatus(message.message || '画布服务未就绪，正在重试…');
    else setCanvasBindingStatus('请选择要编辑的画布');
  }, [setCanvasBindingStatus]);
  const handleCanvasModeReady = useCallback((message: CanvasModeReadyMessage) => {
    const session = useAgentStore.getState().canvasSession;
    if (session && session.conversationId === message.conversationId
      && session.projectId === message.projectId) {
      useAgentStore.getState().beginCanvasSessionOpen(message.canvasId, false);
      void readyCanvasSession();
      return;
    }
    const binding = useAgentStore.getState().canvasBinding;
    const currentContext = useAgentStore.getState().canvasEditContext;
    const rebound = rebindCanvasEditContext(currentContext, message.projectId, message.canvasId);
    if (rebound) useAgentStore.getState().setCanvasEditContext(rebound);
    bindCanvasMode(message.canvasId);
    if (!binding || message.conversationId !== binding.conversationId || message.projectId !== binding.projectId) return;
    const base = {
      mode: 'canvas-edit' as const,
      projectId: message.projectId,
      canvasId: message.canvasId,
      mediaRefs: [],
    };
    const context = buildCanvasRequestContext(base, {
      picUrls: binding.picUrls,
      assetIds: binding.assetIds,
      mediaRefs: binding.mediaRefs,
    }) ?? base;
    void resumeCanvasBinding(context);
  }, [bindCanvasMode, readyCanvasSession, resumeCanvasBinding]);
  const handleCancelCanvasBinding = useCallback(() => {
    cancelCanvasBinding();
    releaseCanvasMode();
  }, [cancelCanvasBinding, releaseCanvasMode]);

  useEffect(() => {
    if (!canvasSession) openedCanvasSessionRef.current = null;
  }, [canvasSession]);

  useEffect(() => {
    if (!canvasBinding?.conversationId || !canvasBinding.projectId) return;
    const existing = chatAppTabs.find((tab) => tab.key === 'canvas');
    if (existing?.canvasMode?.conversationId === canvasBinding.conversationId
      && existing.projectId === canvasBinding.projectId) return;
    openChatApp('canvas', {
      canvasMode: {
        conversationId: canvasBinding.conversationId,
        projectId: canvasBinding.projectId,
        canvasId: canvasBinding.canvasId
          ?? (existing?.projectId === canvasBinding.projectId ? canvasIdFromUrl(existing.url) : undefined),
        parentOrigin: window.location.origin,
      },
    });
  }, [canvasBinding, openChatApp, chatAppTabs]);

  useEffect(() => {
    if (!canvasSession || canvasSession.status !== 'opening') return;
    if (openedCanvasSessionRef.current === canvasSession.sessionId) return;
    openedCanvasSessionRef.current = canvasSession.sessionId;
    const existing = chatAppTabs.find((tab) => tab.key === 'canvas');
    openChatApp('canvas', {
      canvasMode: {
        conversationId: canvasSession.conversationId,
        projectId: canvasSession.projectId,
        canvasId: canvasSession.canvasId,
        parentOrigin: window.location.origin,
      },
    });
    if (existing?.canvasMode?.canvasId === canvasSession.canvasId) {
      void readyCanvasSession();
    }
  }, [canvasSession, openChatApp, chatAppTabs, readyCanvasSession]);

  useEffect(() => {
    if (!canvasEditContext || canvasBinding) return;
    const existing = chatAppTabs.find((tab) => tab.key === 'canvas');
    const matches = existing?.canvasMode?.conversationId === activeConversationId
      && existing.canvasMode.projectId === canvasEditContext.projectId
      && existing.canvasMode.canvasId === canvasEditContext.canvasId;
    if (matches) return;
    openChatApp('canvas', {
      canvasMode: {
        conversationId: activeConversationId ?? '',
        projectId: canvasEditContext.projectId,
        canvasId: canvasEditContext.canvasId,
        parentOrigin: window.location.origin,
      },
    });
  }, [activeConversationId, canvasBinding, canvasEditContext, openChatApp, chatAppTabs]);

  useEffect(() => {
    if (!canvasExitPending || streaming || waitingHumanInput || waitingVideoPlan) return;
    exitCanvasModeNow();
    releaseCanvasMode();
  }, [canvasExitPending, releaseCanvasMode, exitCanvasModeNow, streaming, waitingHumanInput, waitingVideoPlan]);

  useEffect(() => {
    if (canvasPhase !== 'natural' && projectOpen) setProjectOpen(false);
  }, [canvasPhase, projectOpen]);

  useEffect(() => {
    if (canvasPhase !== 'natural') return;
    const canvasTab = chatAppTabs.find((tab) => tab.key === 'canvas');
    if (canvasTab?.canvasMode) releaseCanvasMode();
  }, [canvasPhase, releaseCanvasMode, chatAppTabs]);
  const contentRightReserve = appWindowOpen
    ? Math.max(chatApps.layout.width + 32, generationCenterOpen ? 420 : 0)
    : (generationCenterOpen ? 420 : 0);

  // 登录守卫 + iframe 自登录（/chat?embed=1&token=&userId=&name=）：跨端口 iframe 靠 URL 注入 token
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get('embed') === '1') setEmbed(true);
    const requestedCanvasContext = requestedCanvasContextRef.current;
    if (requestedCanvasContext) {
      useAgentStore.getState().setCanvasEditContext(requestedCanvasContext);
    } else if (window.self !== window.top) {
      const persisted = loadCanvasEditContext();
      if (persisted) useAgentStore.getState().setCanvasEditContext(persisted);
    }
    void useAuthStore.getState().checkAuth();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 无项目上下文时自动选第一个项目（会话绑定项目是后端契约）
  useEffect(() => {
    if (loadedRef.current) return;
    loadedRef.current = true;
    const st = useProjectStore.getState();
    const targetProjectId = requestedCanvasContextRef.current?.projectId;
    if (st.currentProject?.id && (!targetProjectId || st.currentProject.id === targetProjectId)) return;
    st.loadProjects()
      .then(() => {
        const list = useProjectStore.getState().projects;
        if (targetProjectId && list.some((project) => project.id === targetProjectId)) {
          return useProjectStore.getState().loadProject(targetProjectId);
        }
        if (list.length > 0) {
          const lastId = getLastOpenedProjectId();
          const target = list.find((p) => p.id === lastId) ?? list[0];
          return useProjectStore.getState().loadProject(target.id);
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

  useEffect(() => {
    if (!embed || !activeConversationId || canvasEditContext) return;
    void useAgentStore.getState().restoreCanvasMode(activeConversationId);
  }, [embed, activeConversationId, canvasEditContext]);

  // 新消息自动滚底（近底才跟随）
  useEffect(() => {
    const el = activeScrollEl();
    if (el && nearBottomRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages, streaming, waitingHumanInput, waitingVideoPlan]);

  const currentTitle = conversations.find((c) => c.id === activeConversationId)?.title ?? '未选择对话';
  const isEmpty = messages.length === 0 && !streaming && !waitingHumanInput && !waitingVideoPlan;
  const scrollDownPresence = usePresence(showScrollDown && !isEmpty, 180);

  useEffect(() => {
    setShowScrollDown(false);
  }, [activeConversationId]);

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
  const referenceSidebarWidth = collapsed ? 48 : convWidth;
  const nextDockMode = resolveChatDockMode({
    previousMode: dockMode,
    embedded: embed,
    appOpen: chatApps.hasTabs,
    mobile: showMobile,
    fullscreen: chatApps.layout.fullscreen,
    viewportWidth,
    appWidth: chatApps.layout.width,
    sidebarWidth: referenceSidebarWidth,
    requestedDock: dockRequested,
  });
  const splitMaxWidth = resolveChatSplitMaxWidth({
    viewportWidth,
    sidebarWidth: collapsed ? 48 : convWidth,
  });
  const expandedSplitMaxWidth = resolveChatSplitMaxWidth({
    viewportWidth,
    sidebarWidth: 48,
  });
  const sidebarCollapseWidth = resolveChatSidebarCollapseWidth({
    viewportWidth,
    sidebarWidth: convWidth,
  });
  const restoreAutoCollapsedSidebar = shouldRestoreAutoCollapsedSidebar({
    autoCollapsed: autoCollapsedSidebarRef.current,
    appOpen: chatApps.hasTabs && !showMobile,
    docked: dockMode === 'docked',
    appWidth: chatApps.layout.width,
    collapseAtWidth: sidebarCollapseWidth,
  });

  const handleAutoCollapseSidebar = useCallback(() => {
    if (collapsed) return;
    autoCollapsedSidebarRef.current = true;
    setCollapsed(true);
  }, [collapsed]);

  const handleManualExpandSidebar = useCallback(() => {
    autoCollapsedSidebarRef.current = false;
    setCollapsed(false);
  }, []);

  const handleManualCollapseSidebar = useCallback(() => {
    autoCollapsedSidebarRef.current = false;
    setCollapsed(true);
  }, []);

  const openGenerationCenter = useCallback((tab: GenerationCenterTab = 'active') => {
    setGenerationCenterTab(tab);
    setGenerationCenterOpen(true);
  }, []);

  useLayoutEffect(() => {
    if (embed || !chatApps.hasTabs || showMobile || collapsed) return;
    if (chatApps.layout.width >= sidebarCollapseWidth) {
      autoCollapsedSidebarRef.current = true;
      setCollapsed(true);
    }
  }, [chatApps.hasTabs, chatApps.layout.width, collapsed, showMobile, sidebarCollapseWidth]);

  useLayoutEffect(() => {
    if (!restoreAutoCollapsedSidebar) return;
    autoCollapsedSidebarRef.current = false;
    setCollapsed(false);
  }, [restoreAutoCollapsedSidebar]);

  useLayoutEffect(() => {
    if (nextDockMode === dockMode) return;

    if (nextDockMode === 'docked') {
      setDockPresentation('pill');
    } else {
      setDockRequested(false);
      setDockPresentation('pill');
    }

    setDockMode(nextDockMode);
  }, [dockMode, nextDockMode]);

  const handleRequestDock = useCallback(() => {
    setDockRequested(true);
  }, []);

  const desktopRightReserve = dockMode === 'docked' ? 0 : contentRightReserve;

  // 手机端：选择会话后自动关闭侧栏
  const handleMobileSelectConversation = (id: string) => {
    if (canvasBinding || canvasExitPending) return;
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
    setConvResizing(true);
    const startX = e.clientX;
    const startW = convWidth;
    const onMouseMove = (ev: MouseEvent) => {
      setConvWidth(Math.min(360, Math.max(180, startW + ev.clientX - startX)));
    };
    const onMouseUp = () => {
      setConvResizing(false);
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
    <ChatAppsProvider open={chatApps.open}>
    <>
    {/* 页面氛围光晕（fixed 覆盖手机/桌面两分支，pointer-events none） */}
    {!canvasTechActive && <AmbientGlow />}
    {/* ═══════════════ 手机端布局（CSS 隐藏/显示，避免 hooks 协调错误）═══════════════ */}
      <div
      className={`chat-page-shell${canvasTechActive ? ' is-canvas-tech' : ''}`}
      data-canvas-mode={canvasTechActive ? 'true' : undefined}
      style={{ height: '100dvh', display: showMobile ? 'flex' : 'none', flexDirection: 'column', background: 'white', overflow: 'hidden' }}
    >
        <CanvasTechAtmosphere active={canvasTechActive} />
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
            canvasLocked={canvasPhase !== 'natural'}
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
              ref={mobileScrollRef}
              onScroll={handleThreadScroll}
              onWheel={handleThreadWheel}
              className="chat-thread-scroll"
              style={{ flex: 1, minHeight: 0, overflowY: 'auto', background: 'white' }}
            >
              <div
                key={activeConversationId ?? 'new-conversation'}
                className="chat-thread-stage"
                style={{ maxWidth: DS.maxContent, margin: '0 auto', padding: '16px 12px', display: 'flex', flexDirection: 'column' }}
              >
                {messages.filter((m) => !isPendingHitl(m)).map((m) => (
                  <div key={m.id} style={{ animation: 'chat-msg-in 0.35s ease-out' }}>
                    <MessageBubble
                      messageId={m.id}
                      conversationId={m.conversationId}
                      role={m.role}
                      content={m.content}
                      metadata={m.metadata}
                      variant="deepseek"
                      createdAt={m.createdAt}
                      editable={canEditLastTurn && m.id === lastUserMessageId}
                      onEditMessage={(messageId, nextContent) => editAndResend(nextContent, messageId)}
                      streaming={streaming && m.role === 'assistant' && m.id === messages[messages.length - 1]?.id}
                      onOpenGenerationCenter={() => openGenerationCenter('image')}
                    />
                  </div>
                ))}
                <AgentTaskCompletionNotices conversationId={activeConversationId} />
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
                {waitingVideoPlan && (
                  <VideoPlanCard info={waitingVideoPlan} archived={activeConversation?.status === 'archived'} />
                )}
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
            <div className="chat-composer-dock" style={{ padding: '8px 12px 12px 12px', background: 'white' }}>
              <ChatComposer />
            </div>
          </>
        )}

        <MobileBottomNav onOpenAssets={() => navigate('/library')} />
        <PersonalInfoModal open={profileOpen} onClose={() => setProfileOpen(false)} />
      </div>
    {/* ═══════════════ 桌面端布局（CSS 隐藏/显示）═══════════════ */}
    <div
      className={`chat-page-shell${canvasTechActive ? ' is-canvas-tech' : ''}`}
      data-canvas-mode={canvasTechActive ? 'true' : undefined}
      style={{ height: '100dvh', display: showMobile ? 'none' : 'flex', background: 'white', overflow: 'hidden', position: 'relative' }}
    >
      <CanvasTechAtmosphere active={canvasTechActive} />
      {/* ── 会话栏（可收起：展开=完整列表，收起=48px 图标 rail，仿 DeepSeek）── */}
      {!embed && (
        <>
          <div
            className={`chat-sidebar-shell${collapsed ? ' is-collapsed' : ''}${convResizing ? ' is-resizing' : ''}`}
            style={{ width: collapsed ? 48 : convWidth }}
          >
            <div
              className={`chat-sidebar-rail${collapsed ? ' is-visible' : ''}`}
              aria-hidden={!collapsed}
              inert={!collapsed}
            >
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
                <button
                  onClick={handleManualExpandSidebar}
                  title="展开侧边栏"
                  style={railIconBtn}
                  className="chat-icon-button"
                  {...railHover}
                ><Moon size={18} strokeWidth={1.8} /></button>
                <div style={{ position: 'relative', width: '100%', display: 'flex', justifyContent: 'center' }}>
                  <button
                    ref={railProjectBtnRef}
                    onClick={() => { if (canvasPhase === 'natural') setProjectOpen((value) => !value); }}
                    onMouseDown={(e) => e.stopPropagation()}
                    title={canvasPhase === 'natural' ? (currentProject?.name ?? '选择项目') : '请先离开画布编辑模式'}
                    style={{ ...railIconBtn, opacity: canvasPhase === 'natural' ? 1 : 0.45, cursor: canvasPhase === 'natural' ? 'pointer' : 'not-allowed' }}
                    className="chat-icon-button"
                    {...railHover}
                  ><FolderKanban size={18} strokeWidth={1.8} /></button>
                </div>
                <button
                  onClick={() => chatApps.open('library')}
                  title="项目库"
                  style={railIconBtn}
                  className="chat-icon-button"
                  {...railHover}
                ><Library size={18} strokeWidth={1.8} /></button>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
                <button
                  onClick={() => setProfileOpen(true)}
                  title="个人信息"
                  style={railIconBtn}
                  className="chat-icon-button"
                  {...railHover}
                ><User size={18} strokeWidth={1.8} /></button>
                <button
                  onClick={logout}
                  title="退出登录"
                  style={railIconBtn}
                  className="chat-icon-button"
                  {...railHover}
                ><LogOut size={18} strokeWidth={1.8} /></button>
              </div>
            </div>

            <div
              className="chat-sidebar-panel"
              style={{ width: convWidth }}
              aria-hidden={collapsed}
              inert={collapsed}
            >
              <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
                <AgentConversationList
                  width={convWidth}
                  onSelectOverride={(id) => {
                    if (canvasBinding || canvasExitPending) return;
                    void useAgentStore.getState().selectConversation(id);
                  }}
                  headerRight={
                    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}>
                      <button
                        onClick={() => setSearchOpen((value) => !value)}
                        title={searchOpen ? '关闭搜索' : '搜索对话（全文+标题）'}
                        aria-expanded={searchOpen}
                        className="chat-icon-button"
                        style={{
                          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                          width: 28, height: 28, border: 'none', background: 'transparent', cursor: 'pointer',
                          color: 'var(--color-muted)', borderRadius: 8,
                        }}
                        onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; e.currentTarget.style.color = DS.brand; }}
                        onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'var(--color-muted)'; }}
                      >
                        <span className={`chat-search-morph${searchOpen ? ' is-open' : ''}`}>
                          <Search className="chat-search-open" size={15} strokeWidth={1.8} />
                          <X className="chat-search-close" size={15} strokeWidth={1.8} />
                        </span>
                      </button>
                      <button
                        onClick={handleManualCollapseSidebar}
                        title="收起侧边栏"
                        aria-expanded={!collapsed}
                        className="chat-icon-button"
                        style={{
                          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                          width: 28, height: 28, border: 'none', background: 'transparent', cursor: 'pointer',
                          color: 'var(--color-muted)', borderRadius: 8,
                        }}
                        onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; e.currentTarget.style.color = DS.brand; }}
                        onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'var(--color-muted)'; }}
                      >
                        <span className="chat-icon-motion chat-icon-motion--collapse">
                          <PanelLeftClose size={15} strokeWidth={1.8} />
                        </span>
                      </button>
                    </div>
                  }
                  toolbar={
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: '8px 12px 12px' }}>
                      <div style={{ position: 'relative', width: '100%' }}>
                        <button
                          ref={desktopProjectBtnRef}
                          onClick={() => { if (canvasPhase === 'natural') setProjectOpen((value) => !value); }}
                          onMouseDown={(e) => e.stopPropagation()}
                          title={canvasPhase === 'natural' ? '项目选择' : '请先离开画布编辑模式'}
                          style={{
                            width: '100%', display: 'flex', alignItems: 'center', gap: 6,
                            border: 'none', borderRadius: 10, background: 'transparent',
                            padding: '0 12px', height: 40, fontSize: 15,
                            color: DS.ink, cursor: canvasPhase === 'natural' ? 'pointer' : 'not-allowed',
                            opacity: canvasPhase === 'natural' ? 1 : 0.5,
                          }}
                          onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; }}
                          onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
                        >
                          <span style={{ fontSize: 15 }}><FolderKanban size={15} strokeWidth={1.8} /></span>
                          <span style={{ flex: 1, textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {currentProject?.name ?? '选择项目'}
                          </span>
                          <span style={{ fontSize: 10, color: DS.textCaption }}><ChevronDown size={10} strokeWidth={2.5} /></span>
                        </button>
                      </div>
                    </div>
                  }
                />
              </div>

              <div style={{ padding: '6px 10px', borderTop: '1px solid var(--color-hairline)' }}>
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
          </div>

          {!collapsed && (
            <div
              onMouseDown={handleConvDrag}
              style={{
                width: 6, cursor: 'col-resize', background: 'transparent', flexShrink: 0,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                transition: 'background 0.15s',
              }}
              onMouseEnter={(e) => { e.currentTarget.style.background = DS.brand; }}
              onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
            >
              <div style={{ width: 2, height: 20, borderRadius: 1, background: 'rgba(0,0,0,0.12)' }} />
            </div>
          )}

          <ProjectDropdown
            open={projectOpen}
            onClose={() => setProjectOpen(false)}
            anchor={collapsed ? railProjectBtnRef : desktopProjectBtnRef}
          />
        </>
      )}

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
                  onSelectOverride={(id) => {
                    if (canvasBinding || canvasExitPending) return;
                    useAgentStore.getState().selectConversation(id);
                    setEmbedConvOpen(false);
                  }}
                />
              </div>
            </div>
      )}

      {/* ── 主对话区 ── */}
      <div
        ref={chatMainRef}
        style={{
          flex: 1,
          minWidth: 0,
          display: 'flex',
          flexDirection: 'column',
          position: 'relative',
        }}
      >
        <ChatDockShell
          active={dockMode === 'docked' && !embed}
          presentation={dockPresentation}
          title={currentTitle}
          streaming={streaming}
          onOpen={() => setDockPresentation('open')}
          onCollapse={() => setDockPresentation('pill')}
          onHide={() => setDockPresentation('hidden')}
          onRestore={() => setDockPresentation('pill')}
          onShowHistory={() => setDockPresentation('history')}
          onCollapseHistory={() => setDockPresentation('open')}
        >
        {isEmpty ? (
          /* 首次对话 hero：名称居中 + 输入卡居中（DeepSeek harness 空态） */
          <div style={{
            flex: 1, display: 'flex', flexDirection: 'column',
            alignItems: 'center', justifyContent: dockMode === 'docked' ? 'flex-end' : 'center', gap: 28,
            paddingLeft: 16,
            paddingRight: !embed ? desktopRightReserve + 16 : 16,
            transition: 'padding-right 420ms cubic-bezier(0.22, 1, 0.36, 1)',
          }}>
            {embed && (
              <button
                onClick={() => setEmbedConvOpen(true)}
                title="会话"
                style={{ position: 'absolute', top: 8, left: 8, border: 'none', background: 'transparent', color: DS.textSecondary, fontSize: 12, cursor: 'pointer', padding: '4px 6px', borderRadius: 8, display: 'flex', alignItems: 'center', gap: 3 }}
              ><Moon size={12} strokeWidth={1.8} /> 会话</button>
            )}
            {embed && canvasEditContext && (
              <div style={{ position: 'absolute', top: 8, right: 8 }}>
                <CanvasModeExitButton />
              </div>
            )}
            {dockMode !== 'docked' && (
              <>
                <LiveOrb
                  variant="webgl"
                  colors={["#1D4E89", "#81C3D7", "#D9E8F5"]}
                  size={120}
                />
                <div style={{ textAlign: 'center' }}>
                  <div style={{ marginBottom: 8 }}><MoonLogo size={36} showText textColor={DS.ink} /></div>
                  <div style={{ fontSize: 14, color: DS.textCaption }}>设计分镜、图片与视频方案</div>
                </div>
              </>
            )}
            <ChatComposer />
            {/* 快捷提示 chips */}
            {dockMode !== 'docked' && (
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
            )}
          </div>
        ) : (
          <>
            {/* 头部：会话名称 + 操作（embed 小窗内收紧字号与留白） */}
            <div style={{
              padding: embed ? '8px 12px' : '14px 28px', borderBottom: `1px solid ${DS.border}`,
              display: dockMode === 'docked' ? 'none' : 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0,
              paddingRight: !embed ? desktopRightReserve + 16 : 12,
              transition: 'padding-right 420ms cubic-bezier(0.22, 1, 0.36, 1)',
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
                {embed && canvasEditContext && <CanvasModeExitButton />}
                {!embed && dockMode !== 'docked' && (
                  <ChatAppActions
                    onOpen={chatApps.open}
                    canvasPhase={canvasPhase}
                    onExitCanvas={leaveCanvasEditMode}
                    onCancelCanvas={handleCancelCanvasBinding}
                  />
                )}
              </div>
            </div>

            {/* 消息列：748px 居中 */}
            <div
              ref={desktopScrollRef}
              onScroll={handleThreadScroll}
              onWheel={handleThreadWheel}
              className="chat-thread-scroll"
              style={{
                flex: 1,
                minHeight: 0,
                display: dockMode === 'docked' && dockPresentation === 'open' ? 'none' : 'block',
                overflowY: 'auto',
                overflowX: 'hidden',
                background: 'white',
                paddingRight: !embed ? desktopRightReserve : 0,
                transition: 'padding-right 420ms cubic-bezier(0.22, 1, 0.36, 1)',
              }}
            >
              <div
                key={activeConversationId ?? 'new-conversation'}
                className="chat-thread-stage"
                style={{ maxWidth: DS.maxContent, margin: '0 auto', padding: '24px 16px 16px', display: 'flex', flexDirection: 'column' }}
              >
                {messages.filter((m) => !isPendingHitl(m)).map((m) => (
                  <div key={m.id} style={{ animation: 'chat-msg-in 0.35s ease-out' }}>
                    <MessageBubble
                      messageId={m.id}
                      conversationId={m.conversationId}
                      role={m.role}
                      content={m.content}
                      metadata={m.metadata}
                      variant="deepseek"
                      createdAt={m.createdAt}
                      editable={canEditLastTurn && m.id === lastUserMessageId}
                      onEditMessage={(messageId, nextContent) => editAndResend(nextContent, messageId)}
                      streaming={streaming && m.role === 'assistant' && m.id === messages[messages.length - 1]?.id}
                      onOpenGenerationCenter={() => openGenerationCenter('image')}
                    />
                  </div>
                ))}
                <AgentTaskCompletionNotices conversationId={activeConversationId} />
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
                {waitingVideoPlan && (
                  <VideoPlanCard info={waitingVideoPlan} archived={activeConversation?.status === 'archived'} />
                )}
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
            <div className="chat-composer-dock" style={{
              padding: '8px 16px 16px',
              paddingRight: !embed ? desktopRightReserve + 16 : 16,
              background: 'white',
              transition: 'padding-right 420ms cubic-bezier(0.22, 1, 0.36, 1)',
            }}>
              <ChatComposer />
            </div>
          </>
        )}
        {scrollDownPresence.mounted && !isEmpty && !(dockMode === 'docked' && dockPresentation === 'open') && (
          <button
            onClick={scrollToBottom}
            title="跳到最后一个消息"
            aria-label="跳到最后一个消息"
            className={`chat-scroll-down${scrollDownPresence.visible ? ' is-open' : ''}${scrollDownPresence.closing ? ' is-closing' : ''}`}
            style={{
              position: 'absolute', bottom: 176,
              left: !embed && desktopRightReserve
                ? `calc(50% - ${desktopRightReserve / 2}px - 17px)`
                : 'calc(50% - 17px)',
              zIndex: 60,
              width: 34, height: 34, borderRadius: '50%',
              border: '1px solid var(--color-hairline)', background: 'white', color: 'var(--color-muted)',
              cursor: 'pointer', boxShadow: '0 4px 16px rgba(0,0,0,0.12)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}
            onMouseEnter={(e) => { e.currentTarget.style.color = 'var(--color-primary)'; e.currentTarget.style.borderColor = 'var(--color-primary)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.color = 'var(--color-muted)'; e.currentTarget.style.borderColor = 'var(--color-hairline)'; }}
          ><ArrowDown size={16} strokeWidth={2} /></button>
        )}
        </ChatDockShell>
      </div>

      <PersonalInfoModal open={profileOpen} onClose={() => setProfileOpen(false)} />
      {!embed && !chatApps.layout.fullscreen && (
        <GenerationCenterPanel
          open={generationCenterOpen}
          defaultTab={generationCenterTab}
          onOpenChange={setGenerationCenterOpen}
          bottom={appWindowOpen ? 20 : 104}
          onOpenRecords={() => setRecordsOpen(true)}
          taskConversationId={activeConversationId}
        />
      )}
      {!embed && (
        <ChatAppsHost
          apps={chatApps}
          currentProjectId={currentProject?.id}
          docked={dockMode === 'docked'}
          splitMaxWidth={splitMaxWidth}
          expandedSplitMaxWidth={expandedSplitMaxWidth}
          sidebarCollapsed={collapsed}
          sidebarCollapseWidth={sidebarCollapseWidth}
          onCollapseSidebar={handleAutoCollapseSidebar}
          onRequestDock={handleRequestDock}
          onCanvasModeStatus={handleCanvasModeStatus}
          onCanvasModeReady={handleCanvasModeReady}
          canvasInteractionLocked={canvasPhase === 'exit_pending' || streaming}
        />
      )}
    </div>
    <GenerationRecordsModal open={recordsOpen} onClose={() => setRecordsOpen(false)} />
    <AgentTaskDrawer />
    <ConversationSearchModal
      open={searchOpen}
      onClose={() => setSearchOpen(false)}
      onSelectConversation={(id) => { void useAgentStore.getState().selectConversation(id); }}
    />
    </>
    </ChatAppsProvider>
  );
}

function CanvasModeExitButton() {
  const leaveCanvasEditMode = useAgentStore((s) => s.leaveCanvasEditMode);
  const canvasExitPending = useAgentStore((s) => s.canvasExitPending);
  return (
    <button
      type="button"
      className="chat-canvas-exit-button"
      onClick={leaveCanvasEditMode}
      disabled={canvasExitPending}
      title={canvasExitPending ? '本轮结束后离开' : '离开画布编辑模式'}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 5,
        border: '1px solid rgba(65,118,230,0.25)', background: 'rgba(65,118,230,0.08)',
        color: DS.brand, borderRadius: 8, padding: '5px 9px', fontSize: 12,
        fontWeight: 600, cursor: canvasExitPending ? 'not-allowed' : 'pointer',
        whiteSpace: 'nowrap', opacity: canvasExitPending ? 0.55 : 1,
      }}
    >
      <Frame size={13} strokeWidth={2} />
      {canvasExitPending ? '本轮结束后离开' : '离开画布编辑模式'}
    </button>
  );
}

function CanvasTechAtmosphere({ active }: { active: boolean }) {
  return (
    <div
      aria-hidden="true"
      className={`chat-canvas-tech-atmosphere${active ? ' is-active' : ''}`}
    >
      <span className="chat-canvas-tech-atmosphere__grid" />
      <span className="chat-canvas-tech-atmosphere__beam" />
      <span className="chat-canvas-tech-atmosphere__line" />
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
