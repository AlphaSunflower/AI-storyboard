import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { userApi } from '../../api/user';
import { PersonalInfoModal } from './PersonalInfoModal';
import { useToastStore } from '../../stores/toastStore';

class IntersectionObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() { return []; }
}

beforeEach(() => {
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
    matches: false,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
  vi.stubGlobal('IntersectionObserver', IntersectionObserverMock);
  useToastStore.setState({ toasts: [] });
  vi.spyOn(userApi, 'getProfile').mockResolvedValue({
    data: { data: { displayName: '小月', email: 'moon@example.com' } },
  } as never);
  vi.spyOn(userApi, 'getStats').mockResolvedValue({
    data: { data: { imageCount: 12, videoCount: 3, projectCount: 2 } },
  } as never);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  useToastStore.setState({ toasts: [] });
});

describe('PersonalInfoModal 通知', () => {
  it('confirms saving the profile', async () => {
    const updateProfile = vi.spyOn(userApi, 'updateProfile')
      .mockResolvedValue({ data: { data: {} } } as never);
    render(<PersonalInfoModal open onClose={() => {}} />);
    await waitFor(() => expect(userApi.getProfile).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: '保存基本信息' }));

    await waitFor(() => expect(updateProfile).toHaveBeenCalled());
    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(1));
    expect(useToastStore.getState().toasts[0]).toMatchObject({
      title: '基本信息已保存',
      variant: 'success',
    });
  });

  it('confirms changing the password', async () => {
    const changePassword = vi.spyOn(userApi, 'changePassword')
      .mockResolvedValue({ data: { data: {} } } as never);
    render(<PersonalInfoModal open onClose={() => {}} />);
    await waitFor(() => expect(userApi.getProfile).toHaveBeenCalled());

    fireEvent.change(screen.getByPlaceholderText('旧密码'), { target: { value: 'old-pass' } });
    fireEvent.change(screen.getByPlaceholderText('新密码（至少 6 位）'), { target: { value: 'new-pass' } });
    fireEvent.change(screen.getByPlaceholderText('确认新密码'), { target: { value: 'new-pass' } });
    fireEvent.click(screen.getByRole('button', { name: '修改密码' }));

    await waitFor(() => expect(changePassword).toHaveBeenCalled());
    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(1));
    expect(useToastStore.getState().toasts[0]).toMatchObject({ title: '密码已修改' });
  });

  it('reports a failed save inline and through a notice', async () => {
    vi.spyOn(userApi, 'updateProfile').mockRejectedValue({
      response: { data: { message: '邮箱已被占用' } },
    } as never);
    render(<PersonalInfoModal open onClose={() => {}} />);
    await waitFor(() => expect(userApi.getProfile).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: '保存基本信息' }));

    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(1));
    expect(useToastStore.getState().toasts[0]).toMatchObject({
      title: '保存失败',
      description: '邮箱已被占用',
      variant: 'error',
    });
    expect(screen.getByText('邮箱已被占用')).toBeTruthy();
  });
});
