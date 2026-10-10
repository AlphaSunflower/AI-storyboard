import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AgentAsset } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { useDocumentPreviewStore } from '../../stores/documentPreviewStore';
import { AgentAssetsModal } from './AgentAssetsPanel';

vi.mock('gsap', () => ({
  default: { fromTo: vi.fn() },
}));
vi.mock('@gsap/react', () => ({
  useGSAP: vi.fn(),
}));

const documentAsset: AgentAsset = {
  id: 'document-1',
  conversationId: 'conversation-1',
  type: 'document',
  url: '/api/files/reports/quarterly.md',
  prompt: null,
  model: null,
  status: 'completed',
  taskId: 'task-1',
  error: null,
  createdAt: '2026-10-10T00:00:00.000Z',
  resultUrls: '["/api/files/reports/quarterly.md"]',
};

describe('AgentAssetsModal', () => {
  afterEach(() => {
    cleanup();
    useAgentStore.setState({
      assets: null,
      activeConversationId: null,
      loadAssets: useAgentStore.getInitialState().loadAssets,
    });
    useDocumentPreviewStore.setState({ attachment: null });
    vi.restoreAllMocks();
  });

  it('shows a document tab and opens document assets with the document preview store', () => {
    const loadAssets = vi.fn().mockResolvedValue(undefined);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      assets: {
        records: [documentAsset],
        total: 1,
        documentTotal: 1,
        page: 1,
        size: 20,
      },
      loadAssets,
    });

    render(
      <MemoryRouter>
        <AgentAssetsModal open onClose={vi.fn()} />
      </MemoryRouter>,
    );

    const documentTab = screen.getByRole('button', { name: /文档/ });
    expect(documentTab.textContent).toContain('1');
    fireEvent.click(documentTab);
    fireEvent.click(screen.getByRole('button', { name: /打开 quarterly.md/ }));

    expect(useDocumentPreviewStore.getState().attachment).toEqual({
      url: '/api/files/reports/quarterly.md',
      name: 'quarterly.md',
      mimeType: 'text/markdown',
      kind: 'file',
      assetId: 'document-1',
    });
  });
});
