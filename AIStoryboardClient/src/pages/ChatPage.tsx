import { useCallback, useEffect, useRef, useState } from 'react';
import { AppHeader } from '../components/layout/AppHeader';
import { AgentConversationList } from '../components/agent/AgentConversationList';
import { AgentChatPanel } from '../components/agent/AgentChatPanel';
import { useAuthStore } from '../stores/authStore';
import { useProjectStore } from '../stores/projectStore';
import { useAgentStore } from '../stores/agentStore';

/**
 * 独立 AI 对话页（/chat）：仿 DeepSeek 桌面端聊天布局。
 * - 全屏两栏：左会话列表（可拖宽）+ 右对话区（消息列限宽居中）
 * - 复用抽屉同一套组件与 agentStore：双入口共用会话，能力一致（闲聊/分镜/图/视频）
 * - 项目上下文：agentStore 依赖 currentProject，页面挂载时无项目则自动选第一个
 */
export function ChatPage() {
  const [convWidth, setConvWidth] = useState(240);
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

  // 会话栏宽度拖拽（同抽屉交互）
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
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column', background: 'var(--color-canvas)' }}>
      <AppHeader />
      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        <AgentConversationList width={convWidth} />
        <div
          onMouseDown={handleConvDrag}
          style={{
            width: 4,
            cursor: 'col-resize',
            background: 'transparent',
            transition: 'background 0.15s',
            flexShrink: 0,
          }}
          onMouseEnter={(e) => { (e.target as HTMLElement).style.background = 'var(--color-primary)'; }}
          onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
        />
        {/* 对话区：整块限宽居中（DeepSeek 风格消息列；输入卡与其同宽，后续可细调 748/780 差） */}
        <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
          <div
            style={{
              width: '100%',
              maxWidth: 780,
              margin: '0 auto',
              height: '100%',
              display: 'flex',
              flexDirection: 'column',
              borderLeft: '1px solid var(--color-hairline)',
              borderRight: '1px solid var(--color-hairline)',
              background: 'white',
            }}
          >
            <AgentChatPanel />
          </div>
        </div>
      </div>
    </div>
  );
}
