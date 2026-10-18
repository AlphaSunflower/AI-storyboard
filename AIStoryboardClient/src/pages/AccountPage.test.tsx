import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { AccountPage } from './AccountPage';
import { useAuthStore } from '../stores/authStore';
import { useProjectStore } from '../stores/projectStore';
import { userApi } from '../api/user';
import { memoryApi } from '../api/memory';

vi.mock('../api/user', () => ({
  userApi: {
    getProfile: vi.fn(),
    getStats: vi.fn(),
    updateProfile: vi.fn(),
    changePassword: vi.fn(),
  },
}));
vi.mock('../api/memory', () => ({
  memoryApi: { list: vi.fn(), update: vi.fn(), remove: vi.fn() },
}));

afterEach(cleanup);

describe('AccountPage 记忆管理', () => {
  beforeEach(() => {
    useAuthStore.setState({
      checkAuth: vi.fn().mockResolvedValue(true),
      logout: vi.fn(),
    } as never);
    useProjectStore.setState({
      projects: [{ id: 'p1', name: '测试项目' } as never],
      loadProjects: vi.fn().mockResolvedValue(undefined),
    });
    vi.mocked(userApi.getProfile).mockResolvedValue({
      data: { data: { userId: 'u1', displayName: '测试用户', email: 'u@test.local' } },
    } as never);
    vi.mocked(userApi.getStats).mockResolvedValue({
      data: { data: { imageCount: 1, videoCount: 2, projectCount: 3 } },
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
    vi.mocked(memoryApi.update).mockResolvedValue({
      data: {
        data: {
          id: 'm1', projectId: 'p1', kind: 'constraint',
          content: '新内容', source: 'agent', confidence: 0.9,
        },
      },
    } as never);
  });

  it('加载并展示项目记忆', async () => {
    render(<MemoryRouter><AccountPage /></MemoryRouter>);

    await waitFor(() => expect(screen.getByText('门洞必须 1.8×1.2m')).toBeTruthy());
    expect(screen.getByText('约束')).toBeTruthy();
    expect(screen.getByText('测试项目')).toBeTruthy();
  });

  it('编辑记忆后调用 update 并更新列表', async () => {
    render(<MemoryRouter><AccountPage /></MemoryRouter>);

    await waitFor(() => expect(screen.getByText('门洞必须 1.8×1.2m')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('编辑记忆'));
    const textarea = document.querySelector('textarea') as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: '新内容' } });
    fireEvent.click(screen.getByText('保存'));

    await waitFor(() => expect(memoryApi.update).toHaveBeenCalledWith(
      'm1',
      expect.objectContaining({ content: '新内容', kind: 'constraint' }),
    ));
    await waitFor(() => expect(screen.getByText('新内容')).toBeTruthy());
  });
});
