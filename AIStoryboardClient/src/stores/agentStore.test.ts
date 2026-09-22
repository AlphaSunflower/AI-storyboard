import { afterEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentConversation } from '../api/agent';
import { useAgentStore } from './agentStore';
import { useProjectStore } from './projectStore';

describe('agentStore createConversation', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    useAgentStore.setState({
      activeConversationId: null,
      conversations: [],
      selectConversation: useAgentStore.getInitialState().selectConversation,
    });
    useProjectStore.setState({ currentProject: null });
  });

  it('does not default an omitted orchestration mode to LEGACY', async () => {
    const conversation: AgentConversation = {
      id: 'conversation-1',
      userId: 'user-1',
      projectId: 'project-1',
      title: '新对话',
      status: 'active',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    useProjectStore.setState({
      currentProject: { id: 'project-1', name: 'Project' } as never,
    });
    const createConversation = vi.spyOn(agentApi, 'createConversation')
      .mockResolvedValue({ data: { data: conversation } } as never);
    const selectConversation = vi.fn().mockResolvedValue(undefined);
    useAgentStore.setState({ selectConversation });

    await useAgentStore.getState().createConversation();

    expect(createConversation).toHaveBeenCalledWith('project-1', '新对话', undefined);
    expect(selectConversation).toHaveBeenCalledWith('conversation-1');
  });
});
