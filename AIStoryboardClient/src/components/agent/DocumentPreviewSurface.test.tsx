import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { rememberDocumentSource } from '../../lib/documentPreview';
import { DocumentPreviewSurface } from './DocumentPreviewSurface';

describe('DocumentPreviewSurface', () => {
  beforeEach(() => {
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: vi.fn(() => 'blob:svg-preview'),
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      value: vi.fn(),
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('renders SVG attachments as images', async () => {
    const attachment = {
      url: '/api/files/reports/chart.svg',
      name: 'chart.svg',
      mimeType: 'image/svg+xml',
      kind: 'image' as const,
    };
    rememberDocumentSource(
      attachment.url,
      new Blob(['<svg xmlns="http://www.w3.org/2000/svg"></svg>'], { type: attachment.mimeType }),
    );

    render(<DocumentPreviewSurface attachment={attachment} />);

    expect(await screen.findByRole('img', { name: 'chart.svg' })).toBeTruthy();
  });
});
