import { describe, expect, it } from 'vitest';
import type { AgentAsset, AgentPage } from '../../api/agent';
import {
  documentAttachmentForAsset,
  getAgentAssetCounts,
  splitAgentAssets,
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

/** 单 URL 资产，用于分桶与计数断言。 */
function asset(overrides: Partial<AgentAsset> = {}): AgentAsset {
  return {
    id: 'asset-1',
    conversationId: 'conversation-1',
    type: 'image',
    url: '/api/files/images/scene-1.png',
    prompt: null,
    model: null,
    status: 'completed',
    taskId: null,
    error: null,
    createdAt: '2026-10-10T00:00:00.000Z',
    resultUrls: null,
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

  it('buckets assets by type and lets other catch the non media types', () => {
    const page: AgentPage<AgentAsset> = {
      records: [
        asset({ id: 'image-1', type: 'image' }),
        asset({ id: 'video-1', type: 'video', url: '/api/files/videos/scene-1.mp4' }),
        documentAsset(),
        asset({ id: 'reference-1', type: 'reference', url: '/api/files/images/upload.png' }),
      ],
      total: 4,
      page: 1,
      size: 20,
    };

    const buckets = splitAgentAssets(page);

    expect(buckets.image.map((item) => item.id)).toEqual(['image-1']);
    expect(buckets.video.map((item) => item.id)).toEqual(['video-1']);
    expect(buckets.document.map((item) => item.id)).toEqual(['document-1']);
    expect(buckets.other.map((item) => item.id)).toEqual(['reference-1']);
    expect(buckets.all).toHaveLength(4);
  });

  it('returns empty buckets for a missing page', () => {
    const buckets = splitAgentAssets(null);

    expect(buckets.all).toEqual([]);
    expect(buckets.other).toEqual([]);
  });

  it('counts all as the sum of every bucket rather than the pagination total', () => {
    const page: AgentPage<AgentAsset> = {
      records: [
        asset({ id: 'image-1', type: 'image' }),
        asset({ id: 'reference-1', type: 'reference' }),
      ],
      // 分页总数按记录行算，与按 URL 展开的分类计数不是同一单位，不能直接当作「全部」。
      total: 47,
      page: 1,
      size: 20,
    };

    const counts = getAgentAssetCounts(page);

    expect(counts.image).toBe(1);
    expect(counts.other).toBe(1);
    expect(counts.all).toBe(counts.image + counts.video + counts.document + counts.other);
  });

  it('counts other from result urls when the backend omits the total', () => {
    const page: AgentPage<AgentAsset> = {
      records: [
        asset({
          id: 'reference-1',
          type: 'reference',
          resultUrls: '["/api/files/images/a.png","/api/files/images/b.png"]',
        }),
      ],
      total: 1,
      page: 1,
      size: 20,
    };

    expect(getAgentAssetCounts(page).other).toBe(2);
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
