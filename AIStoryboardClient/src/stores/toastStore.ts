import { create } from 'zustand';

/** 通知语义：成功 / 失败 / 中性提示，决定燃线颜色与图标 */
export type ToastVariant = 'success' | 'error' | 'info';

export interface ToastInput {
  title: string;
  description?: string;
  variant?: ToastVariant;
  /** 覆盖默认时长（毫秒） */
  duration?: number;
  /** 相同的 key 会替换掉栈里已有的同 key 通知（供聚合类事件去重） */
  key?: string;
  id?: string;
}

export interface ToastItem extends ToastInput {
  id: string;
  variant: ToastVariant;
  duration: number;
  key: string;
}

/** 同屏最多展示的通知条数，超出的最旧一条滑出关闭（由 ToastHost 控制 open） */
export const MAX_VISIBLE_TOASTS = 3;

/** 兜底上限：正常情况下每条通知关闭后都会被回收，这里只防极端情况下无限堆积 */
export const MAX_STORED_TOASTS = 12;

const DEFAULT_DURATION: Record<ToastVariant, number> = {
  success: 2500,
  error: 4000,
  info: 1800,
};

interface ToastState {
  /** 旧的在前面、新的在末尾：渲染时最新的贴近右下角 */
  toasts: ToastItem[];
  notify: (input: ToastInput) => string;
  dismiss: (id: string) => void;
}

let toastSeq = 0;

export const useToastStore = create<ToastState>((set) => ({
  toasts: [],

  notify: (input) => {
    const variant = input.variant ?? 'success';
    const id = input.id ?? `toast-${++toastSeq}`;
    const item: ToastItem = {
      ...input,
      id,
      variant,
      duration: input.duration ?? DEFAULT_DURATION[variant],
      key: input.key ?? id,
    };
    set((state) => {
      const rest = state.toasts.filter((toast) => toast.key !== item.key);
      const next = [...rest, item];
      return {
        toasts: next.length > MAX_STORED_TOASTS ? next.slice(next.length - MAX_STORED_TOASTS) : next,
      };
    });
    return id;
  },

  dismiss: (id) => set((state) => ({ toasts: state.toasts.filter((toast) => toast.id !== id) })),
}));

/** 给 store / 非 React 模块直接调用，避免在非组件上下文里用 hook */
export function notify(input: ToastInput): string {
  return useToastStore.getState().notify(input);
}
