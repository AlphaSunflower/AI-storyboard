import { beforeEach, describe, expect, it } from 'vitest';
import { MAX_STORED_TOASTS, MAX_VISIBLE_TOASTS, notify, useToastStore } from './toastStore';

beforeEach(() => {
  useToastStore.setState({ toasts: [] });
});

describe('toastStore', () => {
  it('appends notifications with the default variant and duration', () => {
    const id = notify({ title: '已归档', description: '「对话一」' });

    const [toast] = useToastStore.getState().toasts;
    expect(toast.id).toBe(id);
    expect(toast.title).toBe('已归档');
    expect(toast.variant).toBe('success');
    expect(toast.duration).toBe(2500);
  });

  it('uses longer fuses for failures and short ones for neutral notices', () => {
    notify({ title: '压缩失败', variant: 'error' });
    notify({ title: '已开始下载', variant: 'info' });

    const [error, info] = useToastStore.getState().toasts;
    expect(error.duration).toBe(4000);
    expect(info.duration).toBe(1800);
  });

  it('replaces an existing notification that shares the same key', () => {
    notify({ key: 'upload', title: '上传完成', description: '1 个文件已入库' });
    notify({ key: 'upload', title: '上传完成', description: '2 个文件已入库' });

    const toasts = useToastStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].description).toBe('2 个文件已入库');
  });

  it('keeps the newest notification last and caps the stored stack', () => {
    for (let index = 0; index < MAX_STORED_TOASTS + 3; index += 1) {
      notify({ title: `第 ${index} 条` });
    }

    const toasts = useToastStore.getState().toasts;
    expect(toasts).toHaveLength(MAX_STORED_TOASTS);
    expect(toasts[toasts.length - 1].title).toBe(`第 ${MAX_STORED_TOASTS + 2} 条`);
    expect(toasts[0].title).toBe('第 3 条');
  });

  it('dismisses a notification by id and exposes the on-screen cap', () => {
    const id = notify({ title: '已删除分镜' });
    useToastStore.getState().dismiss(id);

    expect(useToastStore.getState().toasts).toHaveLength(0);
    expect(MAX_VISIBLE_TOASTS).toBe(3);
  });
});
