import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GenerationRecordsModal } from './GenerationRecordsModal';
import { agentApi, type GenerationRecord } from '../../api/agent';
import { useGenerationRecordsStore } from '../../stores/generationRecordsStore';

vi.mock('gsap', () => ({ default: { fromTo: vi.fn() } }));
vi.mock('@gsap/react', () => ({ useGSAP: vi.fn() }));
vi.mock('./ImagePreviewModal', () => ({
  ImagePreviewModal: () => null,
}));

const documentRecord: GenerationRecord = {
  id: 'asset-1',
  conversationId: 'conversation-1',
  type: 'document',
  url: '/api/files/files/uuid-manual.md?token=abc',
  originalName: 'Moon 手册.md',
  mimeType: 'text/markdown',
  prompt: '由 AgentScope 沙箱生成',
  model: null,
  status: 'completed',
  taskId: null,
  error: null,
  createdAt: new Date().toISOString(),
  genType: 'sandbox_generated',
  params: '{"sandboxPath":"output/Moon 手册.md"}',
  referenceUrls: null,
  resultUrls: '["/api/files/files/uuid-manual.md?token=abc"]',
  durationMs: null,
  sceneId: null,
};

describe('GenerationRecordsModal', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    useGenerationRecordsStore.setState({
      records: [], total: 0, loading: false, error: null,
      activeId: null, detail: null, detailLoading: false,
      page: 1, filters: { genType: '', status: 'all', from: '', to: '' },
    });
  });

  it('renders a document record as a file card with the real download name', async () => {
    vi.spyOn(agentApi, 'listGenerationRecords').mockResolvedValue({
      data: { data: { records: [documentRecord], total: 1, page: 1, size: 12 } },
    } as unknown as Awaited<ReturnType<typeof agentApi.listGenerationRecords>>);

    render(<GenerationRecordsModal open onClose={() => {}} />);

    await waitFor(() => expect(screen.getByText('沙箱文件')).toBeTruthy());

    // 文档结果不能再被渲染成 <img>（那会产生破图）
    expect(document.querySelector('img')).toBeNull();

    const link = screen.getAllByRole('link')[0] as HTMLAnchorElement;
    expect(link.getAttribute('download')).toBe('Moon 手册.md');
    expect(link.getAttribute('href')).toContain('download=1');
    expect(link.getAttribute('href')).toContain('/api/files/files/uuid-manual.md');
  });
});
