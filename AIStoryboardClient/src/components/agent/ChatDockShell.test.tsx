import { useState } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ChatDockShell, type ChatDockPresentation } from './ChatDockShell';

function renderShell(presentation: ChatDockPresentation) {
  const onOpen = vi.fn();
  const onCollapse = vi.fn();
  const onHide = vi.fn();
  const onRestore = vi.fn();
  const onShowHistory = vi.fn();
  const onCollapseHistory = vi.fn();

  const view = render(
    <ChatDockShell
      active
      presentation={presentation}
      title="测试会话"
      streaming={false}
      onOpen={onOpen}
      onCollapse={onCollapse}
      onHide={onHide}
      onRestore={onRestore}
      onShowHistory={onShowHistory}
      onCollapseHistory={onCollapseHistory}
    >
      <div>会话正文</div>
    </ChatDockShell>,
  );

  return { ...view, onOpen, onCollapse, onHide, onRestore, onShowHistory, onCollapseHistory };
}

afterEach(cleanup);

describe('ChatDockShell', () => {
  it('opens the conversation from the pill', () => {
    const { onOpen } = renderShell('pill');

    fireEvent.click(screen.getByRole('button', { name: '打开 Moon 智能体：测试会话' }));

    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('collapses when the open panel backdrop is clicked', () => {
    const { onCollapse } = renderShell('open');

    fireEvent.click(screen.getByLabelText('关闭 Moon 浮窗'));

    expect(onCollapse).toHaveBeenCalledTimes(1);
  });

  it('collapses the open panel with Escape', () => {
    const { onCollapse } = renderShell('open');

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onCollapse).toHaveBeenCalledTimes(1);
  });

  it('shows the history toggle before the conversation history', () => {
    const { onShowHistory } = renderShell('open');

    fireEvent.click(screen.getByRole('button', { name: '查看聊天记录' }));

    expect(onShowHistory).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: '隐藏 Moon 智能体' })).toBeNull();
  });

  it('collapses the history back to the compact composer', () => {
    const { onCollapseHistory } = renderShell('history');

    fireEvent.click(screen.getByRole('button', { name: '收起聊天记录' }));

    expect(onCollapseHistory).toHaveBeenCalledTimes(1);
  });

  it('hides the dock from the handle below the pill', () => {
    const { onHide } = renderShell('pill');

    fireEvent.click(screen.getByRole('button', { name: '隐藏 Moon 智能体' }));

    expect(onHide).toHaveBeenCalledTimes(1);
  });

  it('reveals the bottom handle on hover and restores the pill', () => {
    const { onRestore } = renderShell('hidden');

    expect(screen.queryByRole('button', { name: '显示 Moon 智能体' })).toBeNull();
    fireEvent.mouseEnter(screen.getByTestId('chat-dock-hover-zone'));
    fireEvent.click(screen.getByRole('button', { name: '显示 Moon 智能体' }));

    expect(onRestore).toHaveBeenCalledTimes(1);
  });

  it('keeps the conversation mounted when presentation changes', () => {
    function Harness() {
      const [presentation, setPresentation] = useState<ChatDockPresentation>('open');
      return (
        <>
          <button onClick={() => setPresentation('pill')}>切到胶囊</button>
          <ChatDockShell
            active
            presentation={presentation}
            title="测试会话"
            streaming={false}
            onOpen={() => setPresentation('open')}
            onCollapse={() => setPresentation('pill')}
            onHide={() => setPresentation('hidden')}
            onRestore={() => setPresentation('pill')}
            onShowHistory={() => setPresentation('history')}
            onCollapseHistory={() => setPresentation('open')}
          >
            <DraftSurface />
          </ChatDockShell>
        </>
      );
    }

    function DraftSurface() {
      const [value, setValue] = useState('未发送草稿');
      return <input aria-label="会话草稿" value={value} onChange={(event) => setValue(event.target.value)} />;
    }

    render(<Harness />);
    fireEvent.change(screen.getByLabelText('会话草稿'), { target: { value: '保留这段草稿' } });
    fireEvent.click(screen.getByRole('button', { name: '切到胶囊' }));

    expect((screen.getByLabelText('会话草稿') as HTMLInputElement).value).toBe('保留这段草稿');
  });
});
