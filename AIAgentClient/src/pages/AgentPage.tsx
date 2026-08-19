import { useEffect } from 'react';
import { AgentSidebar } from '../components/AgentSidebar';
import { AgentConversationArea } from '../components/AgentConversationArea';
import { AgentModal } from '../components/AgentModal';
import { useAgentStore } from '../stores/agentStore';

export function AgentPage() {
  // 登录后/刷新后:已有记住的项目则自动加载其会话(历史对话直接可见,与 AI 分镜系统共享)
  useEffect(() => {
    const { projectId, conversations, loadConversations } = useAgentStore.getState();
    if (projectId && conversations.length === 0) void loadConversations();
  }, []);

  return (
    <div className="flex h-screen" style={{ background: 'var(--color-canvas)' }}>
      <AgentSidebar />
      <AgentConversationArea />
      <AgentModal />
    </div>
  );
}
