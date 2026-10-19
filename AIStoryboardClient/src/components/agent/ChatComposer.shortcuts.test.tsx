// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { agentApi } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { useToastStore } from '../../stores/toastStore';
import { ChatComposer } from './ChatComposer';

function press(digit: number) {
  fireEvent.keyDown(window, {
    key: String(digit),
    code: `Digit${digit}`,
    ctrlKey: true,
    shiftKey: true,
  });
}

describe('ChatComposer keyboard shortcuts', () => {
  beforeEach(() => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      streaming: false,
      waitingHumanInput: null,
      waitingVideoPlan: null,
    });
    useToastStore.setState({ toasts: [] });
    vi.spyOn(agentApi, 'listConversationAssetGroups').mockResolvedValue({
      data: { code: 0, message: '', data: [] },
    } as never);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    useAgentStore.getState().newConversation();
  });

  it('Ctrl/Cmd + Shift + 2 打开状态列表', () => {
    render(<ChatComposer />);

    press(2);

    expect(screen.getByText('状态')).toBeTruthy();
  });

  it('隐藏分支的输入卡不响应快捷键，避免一次按键执行两遍', () => {
    render(<ChatComposer shortcutsEnabled={false} />);

    press(2);

    expect(screen.queryByText('状态')).toBeNull();
  });

  it('没有上下文时压缩快捷键只提示，不调用后端', () => {
    const compact = vi.spyOn(agentApi, 'compactContext');
    render(<ChatComposer />);

    press(3);

    expect(compact).not.toHaveBeenCalled();
    expect(useToastStore.getState().toasts.at(-1)?.title).toBe('新对话暂无可压缩的上下文');
  });

  it('录音快捷键在没有麦克风能力时给出可读提示，而不是静默失败', () => {
    render(<ChatComposer />);

    press(1);

    expect(useToastStore.getState().toasts.at(-1)?.title).toBe('当前浏览器不支持录音');
  });
});
