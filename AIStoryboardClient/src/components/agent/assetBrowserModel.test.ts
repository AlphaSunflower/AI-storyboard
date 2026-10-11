import { describe, expect, it } from 'vitest';
import type { AgentAsset, AgentPage } from '../../api/agent';
import {
  documentAttachmentForAsset,
  getAgentAssetCounts,
} from './assetBrowserModel';

function documentAsset(overrides: Partial<AgentAsset> = {}): AgentAsset {
  return {
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
    ...overrides,
  };
}

describe('assetBrowserModel', () => {
  it('counts document assets from the page total and from result urls', () => {
    const explicitTotal: AgentPage<AgentAsset> = {
      records: [documentAsset()],
      total: 1,
      documentTotal: 3,
      page: 1,
      size: 20,
    };
    expect(getAgentAssetCounts(explicitTotal).document).toBe(3);

    const fallbackTotal: AgentPage<AgentAsset> = {
      records: [
        documentAsset(),
        documentAsset({
          id: 'document-2',
          url: '/api/files/reports/summary.csv',
          resultUrls: '["/api/files/reports/summary.csv","/api/files/reports/raw.csv"]',
        }),
      ],
      total: 2,
      page: 1,
      size: 20,
    };
    expect(getAgentAssetCounts(fallbackTotal).document).toBe(3);
  });

  it('builds a document attachment from an asset result url', () => {
    expect(documentAttachmentForAsset(documentAsset(), '/api/files/reports/quarterly.md')).toEqual({
      url: '/api/files/reports/quarterly.md',
      name: 'quarterly.md',
      mimeType: 'text/markdown',
      kind: 'file',
      assetId: 'document-1',
    });
  });

  it('prefers the stored asset mime type over the url extension', () => {
    const asset = documentAsset({
      url: '/api/files/files/uuid-report.html',
      mimeType: 'text/plain',
      resultUrls: '["/api/files/files/uuid-report.html"]',
    });

    expect(documentAttachmentForAsset(asset, asset.url)).toMatchObject({
      name: 'uuid-report.html',
      mimeType: 'text/plain',
      kind: 'file',
    });
  });
});
