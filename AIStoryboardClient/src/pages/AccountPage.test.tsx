import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { AccountPage } from './AccountPage';
import { useAuthStore } from '../stores/authStore';
import { useProjectStore } from '../stores/projectStore';
import { useAccountSettingsStore, DEFAULT_ACCOUNT_SETTINGS } from '../stores/accountSettingsStore';
import { useAgentStore } from '../stores/agentStore';
import { userApi } from '../api/user';
import { memoryApi } from '../api/memory';
import { applyThemePreference } from '../lib/theme';

vi.mock('../api/user', () => ({
  userApi: {
    getProfile: vi.fn(),
    getStats: vi.fn(),
    updateProfile: vi.fn(),
    changePassword: vi.fn(),
    getSettings: vi.fn(),
    updateSettings: vi.fn(),
    submitFeedback: vi.fn(),
    getFeedbackInbox: vi.fn(),
    updateFeedbackStatus: vi.fn(),
  },
}));
vi.mock('../api/memory', () => ({
  memoryApi: {
    list: vi.fn(),
    settings: vi.fn(),
    updateSettings: vi.fn(),
    saveGlobal: vi.fn(),
    clearGlobal: vi.fn(),
    remove: vi.fn(),
  },
}));

afterEach(() => {
  cleanup();
  applyThemePreference('light');
});

describe('AccountPage 菜单与记忆管理', () => {
  beforeEach(() => {
    useAuthStore.setState({
      checkAuth: vi.fn().mockResolvedValue(true),
      logout: vi.fn(),
    } as never);
    useProjectStore.setState({
      projects: [{ id: 'p1', name: '测试项目' } as never],
      loadProjects: vi.fn().mockResolvedValue(undefined),
    });
    useAccountSettingsStore.setState({
      settings: DEFAULT_ACCOUNT_SETTINGS,
      loaded: true,
      loading: false,
    });
    vi.mocked(userApi.getProfile).mockResolvedValue({
      data: { data: { userId: 'u1', displayName: '测试用户', email: 'u@test.local', role: 'member' } },
    } as never);
    vi.mocked(userApi.getStats).mockResolvedValue({
      data: { data: { imageCount: 1, videoCount: 2, projectCount: 3 } },
    } as never);
    vi.mocked(userApi.getSettings).mockResolvedValue({
      data: { data: { ...DEFAULT_ACCOUNT_SETTINGS } },
    } as never);
    vi.mocked(userApi.updateSettings).mockImplementation(async (patch) => ({
      data: { data: { ...DEFAULT_ACCOUNT_SETTINGS, ...patch } },
    } as never));
    vi.mocked(userApi.submitFeedback).mockResolvedValue({
      data: { data: { id: 'f1', status: 'open' } },
    } as never);
    vi.mocked(userApi.getFeedbackInbox).mockResolvedValue({
      data: { data: [] },
    } as never);
    vi.mocked(memoryApi.list).mockResolvedValue({
      data: {
        data: [{
          id: 'm1',
          projectId: 'p1',
          kind: 'constraint',
          content: '门洞必须 1.8×1.2m',
          source: 'agent',
          confidence: 0.95,
        }],
      },
    } as never);
    vi.mocked(memoryApi.settings).mockResolvedValue({
      data: {
        data: {
          enabled: true,
          global: { content: '所有项目都用中文回复' },
          limits: { project: 200, user: 500, contentChars: 4000 },
        },
      },
    } as never);
    vi.mocked(memoryApi.updateSettings).mockImplementation(async (enabled: boolean) => ({
      data: {
        data: {
          enabled,
          global: { content: '所有项目都用中文回复' },
          limits: { project: 200, user: 500, contentChars: 4000 },
        },
      },
    } as never));
    vi.mocked(memoryApi.saveGlobal).mockResolvedValue({
      data: { data: { content: '不要使用霓虹色', updatedAt: '2026-10-20T10:00:00+08:00' } },
    } as never);
    vi.mocked(memoryApi.clearGlobal).mockResolvedValue({ data: { data: true } } as never);
    vi.mocked(memoryApi.remove).mockResolvedValue({ data: { data: true } } as never);
  });

  async function openMemories() {
    render(<MemoryRouter><AccountPage /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText('测试用户')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /记忆管理/ }));
  }

  it('默认展示个人信息分区，并可切换到其他菜单', async () => {
    render(<MemoryRouter><AccountPage /></MemoryRouter>);

    await waitFor(() => expect(screen.getByText('测试用户')).toBeTruthy());
    expect(screen.getByText('账号余额')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /反馈收件箱/ })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /余额明细/ }));
    expect(screen.getByText('变动记录')).toBeTruthy();
    expect(screen.getByText('暂无余额变动')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /通用设置/ }));
    expect(screen.getByText('外观')).toBeTruthy();
    expect(screen.getByText('全局权限')).toBeTruthy();
    expect(screen.getByText('繁忙时的发送行为')).toBeTruthy();
    expect(screen.getByText('工作步骤展示')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /帮助与反馈/ }));
    expect(screen.getByText(/遇到问题或有功能建议/)).toBeTruthy();
  });

  it('切换繁忙发送行为会持久化到账号设置', async () => {
    render(<MemoryRouter><AccountPage /></MemoryRouter>);

    await waitFor(() => expect(screen.getByText('测试用户')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /通用设置/ }));
    fireEvent.click(screen.getByRole('radio', { name: /排队发送/ }));

    await waitFor(() => expect(userApi.updateSettings).toHaveBeenCalledWith({
      busySendBehavior: 'queue',
    }));
    await waitFor(() => expect(
      useAccountSettingsStore.getState().settings.busySendBehavior,
    ).toBe('queue'));
  });

  it('选择深色外观会立即切换主题并持久化', async () => {
    render(<MemoryRouter><AccountPage /></MemoryRouter>);

    await waitFor(() => expect(screen.getByText('测试用户')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /通用设置/ }));
    fireEvent.click(screen.getByRole('radio', { name: '深色' }));

    await waitFor(() => expect(userApi.updateSettings).toHaveBeenCalledWith({
      themePreference: 'dark',
    }));
    await waitFor(() => expect(
      useAccountSettingsStore.getState().settings.themePreference,
    ).toBe('dark'));
    expect(document.documentElement.dataset.theme).toBe('dark');
  });

  it('选择完全权限后，新会话草稿沿用完全访问', async () => {
    render(<MemoryRouter><AccountPage /></MemoryRouter>);

    await waitFor(() => expect(screen.getByText('测试用户')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /通用设置/ }));
    fireEvent.click(screen.getByRole('radio', { name: /完全权限/ }));
    fireEvent.click(screen.getByRole('button', { name: '确认开启' }));

    await waitFor(() => expect(userApi.updateSettings).toHaveBeenCalledWith({
      defaultApprovalMode: 'FULL_ACCESS',
    }));
    await waitFor(() => expect(
      useAccountSettingsStore.getState().settings.defaultApprovalMode,
    ).toBe('FULL_ACCESS'));

    useAgentStore.getState().newConversation();
    expect(useAgentStore.getState().pendingApprovalMode).toBe('FULL_ACCESS');
  });

  it('提交反馈会带上问题描述', async () => {
    render(<MemoryRouter><AccountPage /></MemoryRouter>);

    await waitFor(() => expect(screen.getByText('测试用户')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /帮助与反馈/ }));
    fireEvent.change(screen.getByPlaceholderText(/例如：生成视频时/), {
      target: { value: '生成视频时一直停在准备中，刷新后任务消失。' },
    });
    fireEvent.click(screen.getByRole('button', { name: '提交反馈' }));

    await waitFor(() => expect(userApi.submitFeedback).toHaveBeenCalledWith({
      content: '生成视频时一直停在准备中，刷新后任务消失。',
      imageUrls: [],
    }));
  });

  it('管理员能在反馈收件箱查看并标记反馈', async () => {
    vi.mocked(userApi.getProfile).mockResolvedValue({
      data: { data: { userId: 'u1', displayName: '管理员', email: 'admin@test.local', role: 'admin' } },
    } as never);
    vi.mocked(userApi.getFeedbackInbox).mockResolvedValue({
      data: {
        data: [{
          id: 'f1',
          userId: 'u2',
          userDisplayName: '反馈用户',
          userEmail: 'user@test.local',
          content: '生成视频一直停在准备中。',
          imageUrls: [],
          status: 'open',
          createdAt: '2026-10-18T21:00:00+08:00',
        }],
      },
    } as never);
    vi.mocked(userApi.updateFeedbackStatus).mockResolvedValue({ data: { data: null } } as never);

    render(<MemoryRouter><AccountPage /></MemoryRouter>);

    await waitFor(() => expect(screen.getByText('管理员')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /反馈收件箱/ }));
    await waitFor(() => expect(screen.getByText('生成视频一直停在准备中。')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: '标记已处理' }));

    await waitFor(() => expect(userApi.updateFeedbackStatus).toHaveBeenCalledWith('f1', 'resolved'));
    await waitFor(() => expect(screen.getByText('已处理')).toBeTruthy());
  });

  it('展示全局记忆与记忆管理开关', async () => {
    await openMemories();

    await waitFor(() => expect(screen.getByText('门洞必须 1.8×1.2m')).toBeTruthy());
    expect(screen.getByText('全局记忆')).toBeTruthy();
    expect(screen.getByDisplayValue('所有项目都用中文回复')).toBeTruthy();
    expect(screen.getByRole('switch', { name: '记忆管理开关' }).getAttribute('aria-checked'))
      .toBe('true');
    expect(screen.getByText(/每个项目最多 200 条/)).toBeTruthy();
  });

  it('关闭记忆管理会持久化并禁用全局记忆编辑', async () => {
    await openMemories();

    await waitFor(() => expect(screen.getByText('门洞必须 1.8×1.2m')).toBeTruthy());
    fireEvent.click(screen.getByRole('switch', { name: '记忆管理开关' }));

    await waitFor(() => expect(memoryApi.updateSettings).toHaveBeenCalledWith(false));
    await waitFor(() => expect(
      screen.getByRole('switch', { name: '记忆管理开关' }).getAttribute('aria-checked'),
    ).toBe('false'));
    expect((screen.getByDisplayValue('所有项目都用中文回复') as HTMLTextAreaElement).disabled)
      .toBe(true);
    expect(screen.getByText(/记忆管理已关闭/)).toBeTruthy();
  });

  it('可以保存全局记忆', async () => {
    await openMemories();

    await waitFor(() => expect(screen.getByText('门洞必须 1.8×1.2m')).toBeTruthy());
    fireEvent.change(screen.getByDisplayValue('所有项目都用中文回复'), {
      target: { value: '不要使用霓虹色' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => expect(memoryApi.saveGlobal).toHaveBeenCalledWith('不要使用霓虹色'));
    expect(screen.getByDisplayValue('不要使用霓虹色')).toBeTruthy();
  });

  it('删除项目记忆会调用 remove 并从列表移除', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    await openMemories();

    await waitFor(() => expect(screen.getByText('门洞必须 1.8×1.2m')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('删除记忆'));

    await waitFor(() => expect(memoryApi.remove).toHaveBeenCalledWith('m1'));
    await waitFor(() => expect(screen.queryByText('门洞必须 1.8×1.2m')).toBeNull());
    confirmSpy.mockRestore();
  });
});
