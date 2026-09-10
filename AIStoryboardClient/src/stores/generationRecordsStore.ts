import { create } from 'zustand';
import { agentApi, type GenerationRecord } from '../api/agent';
export { parseResultUrls } from '../lib/generationRecordParsing';

export interface GenerationFilters {
  genType: string;
  status: 'all' | 'success' | 'failed';
  from: string;
  to: string;
}

interface GenerationRecordsState {
  filters: GenerationFilters;
  page: number;
  size: number;
  records: GenerationRecord[];
  total: number;
  loading: boolean;
  error: string | null;
  activeId: string | null;
  detail: GenerationRecord | null;
  detailLoading: boolean;
  detailAction: 'idle' | 'refetch' | 'retry';
  setFilters: (next: Partial<GenerationFilters>) => void;
  resetFilters: () => void;
  goTo: (page: number) => Promise<void>;
  loadRecords: () => Promise<void>;
  openDetail: (id: string) => Promise<void>;
  setActiveId: (id: string | null) => void;
  closeDetail: () => void;
  refetch: (id: string) => Promise<void>;
  retry: (id: string) => Promise<void>;
}

const DEFAULT_FILTERS: GenerationFilters = {
  genType: '',
  status: 'all',
  from: '',
  to: '',
};

export const useGenerationRecordsStore = create<GenerationRecordsState>((set, get) => ({
  filters: DEFAULT_FILTERS,
  page: 1,
  size: 12,
  records: [],
  total: 0,
  loading: false,
  error: null,
  activeId: null,
  detail: null,
  detailLoading: false,
  detailAction: 'idle',

  setFilters: (next) =>
    set((s) => ({
      filters: { ...s.filters, ...next },
      page: 1,
      activeId: null,
      detail: null,
    })),

  resetFilters: () =>
    set({ filters: DEFAULT_FILTERS, page: 1, activeId: null, detail: null }),

  goTo: async (page) => {
    set({ page, activeId: null, detail: null });
  },

  loadRecords: async () => {
    const { filters, page, size } = get();
    set({ loading: true, error: null });
    try {
      const res = await agentApi.listGenerationRecords({
        genType: filters.genType || undefined,
        status: filters.status === 'all' ? undefined : filters.status,
        from: filters.from || undefined,
        to: filters.to || undefined,
        page,
        size,
      });
      set({
        records: res.data.data.records,
        total: res.data.data.total,
        page: res.data.data.page,
        loading: false,
      });
    } catch (e) {
      set({ loading: false, error: e instanceof Error ? e.message : '加载生成记录失败' });
    }
  },

  openDetail: async (id) => {
    set({ activeId: id, detailLoading: true, detailAction: 'idle' });
    try {
      const res = await agentApi.getGenerationRecord(id);
      set({ detail: res.data.data, detailLoading: false });
      const found = get().records.find((r) => r.id === id);
      if (found) {
        // 同步刷新列表项状态（详情可能触发重拉/重试）
        set((s) => ({
          records: s.records.map((r) => (r.id === id ? res.data.data : r)),
        }));
      }
    } catch (e) {
      set({ detailLoading: false, error: e instanceof Error ? e.message : '加载详情失败' });
    }
  },

  setActiveId: (id) => set({ activeId: id }),

  closeDetail: () => set({ activeId: null, detail: null, detailAction: 'idle' }),

  refetch: async (id) => {
    set({ detailAction: 'refetch', error: null });
    try {
      const res = await agentApi.refetchGenerationRecord(id);
      set((s) => ({
        detail: res.data.data,
        detailAction: 'idle',
        records: s.records.map((r) => (r.id === id ? res.data.data : r)),
      }));
    } catch (e) {
      set({ detailAction: 'idle', error: e instanceof Error ? e.message : '重新拉取失败' });
    }
  },

  retry: async (id) => {
    set({ detailAction: 'retry', error: null });
    try {
      const res = await agentApi.retryGenerationRecord(id);
      set({ detailAction: 'idle' });
      const newId = res.data.data.id;
      if (newId) {
        await get().openDetail(newId);
        await get().loadRecords();
      } else {
        throw new Error('未生成新记录');
      }
    } catch (e) {
      set({ detailAction: 'idle', error: e instanceof Error ? e.message : '重试失败' });
    }
  },
}));

// ── JSON 字符串解析辅助 ──────────────────────────────
export function parseJsonArray(value: string | null): { type?: string; url?: string | null; role?: string }[] {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.filter((x) => x && typeof x === 'object') : [];
  } catch {
    return [];
  }
}

export function parseReferenceUrls(value: string | null): { type: string; url: string | null; role?: string }[] {
  return parseJsonArray(value)
    .map((x) => ({
      type: (x.type ?? 'image') as string,
      url: (x.url ?? null) as string | null,
      role: (x.role ?? undefined) as string | undefined,
    }))
    .filter((x) => x.url !== null && x.url !== '');
}

export function parseParams(value: string | null): Record<string, unknown> {
  if (!value) return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}
