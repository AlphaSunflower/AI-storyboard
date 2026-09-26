import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useAgentStore } from '../../stores/agentStore';
import { ChatAppsProvider } from './apps/ChatAppsContext';
import { StoryboardResultActions } from './StoryboardResultActions';

describe('StoryboardResultActions', () => {
  afterEach(() => {
    cleanup();
    useAgentStore.setState({
      sendMessage: useAgentStore.getInitialState().sendMessage,
    });
    vi.restoreAllMocks();
  });

  it('opens the project storyboard editor in the chat child window', () => {
    const openApp = vi.fn();
    render(
      <MemoryRouter>
        <ChatAppsProvider open={openApp}>
          <StoryboardResultActions />
        </ChatAppsProvider>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: /查看分镜列表/ }));

    expect(openApp).toHaveBeenCalledWith('editor');
  });

  it('offers a one-click missing-image generation action', async () => {
    const sendMessage = vi.fn().mockResolvedValue(undefined);
    useAgentStore.setState({ sendMessage });
    render(
      <MemoryRouter>
        <StoryboardResultActions />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: /生成全部缺图/ }));

    expect(sendMessage).toHaveBeenCalledWith(
      '把当前项目所有缺图的分镜图片生成出来',
      { routingHint: 'intent-storyboard' },
    );
  });
});
