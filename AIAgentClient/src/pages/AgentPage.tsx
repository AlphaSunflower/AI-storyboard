import { AgentSidebar } from '../components/AgentSidebar';
import { AgentConversationArea } from '../components/AgentConversationArea';
import { AgentModal } from '../components/AgentModal';

export function AgentPage() {
  return (
    <div className="flex h-screen" style={{ background: 'var(--color-canvas)' }}>
      <AgentSidebar />
      <AgentConversationArea />
      <AgentModal />
    </div>
  );
}
