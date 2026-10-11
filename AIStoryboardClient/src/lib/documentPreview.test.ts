import { describe, expect, it } from 'vitest';
import {
  documentMimeType,
  documentPreviewKind,
  formatDocumentText,
  loadDocumentSource,
  rememberDocumentSource,
} from './documentPreview';

describe('documentPreview', () => {
  it('detects supported browser-side document formats', () => {
    expect(documentPreviewKind({
      name: '项目计划书.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })).toBe('docx');
    expect(documentPreviewKind({
      name: '方案.pptx',
      mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    })).toBe('pptx');
    expect(documentPreviewKind({ name: '旧版方案.ppt' })).toBe('ppt');
    expect(documentPreviewKind({ name: '说明.md' })).toBe('markdown');
    expect(documentPreviewKind({ name: '运行环境.txt' })).toBe('text');
    expect(documentPreviewKind({ name: 'data.json' })).toBe('json');
    expect(documentPreviewKind({ name: '原型.html' })).toBe('text');
    expect(documentPreviewKind({ name: '方案.pdf' })).toBe('pdf');
    expect(documentPreviewKind({ name: 'archive.zip' })).toBe('unsupported');
  });

  it('previews agent-written code and scripts as plain text', () => {
    expect(documentPreviewKind({ name: 'main.py' })).toBe('text');
    expect(documentPreviewKind({ name: 'index.ts', mimeType: 'application/octet-stream' }))
      .toBe('text');
    expect(documentPreviewKind({ name: 'deploy.sh' })).toBe('text');
    expect(documentPreviewKind({ name: 'Dockerfile' })).toBe('unsupported');
  });

  it('only renders html as html when the MIME type says so', () => {
    expect(documentPreviewKind({ name: 'report.html' })).toBe('text');
    expect(documentPreviewKind({ name: 'report.html', mimeType: 'text/plain' })).toBe('text');
    expect(documentPreviewKind({ name: 'report.html', mimeType: 'text/html' })).toBe('html');
    expect(documentMimeType({ name: 'report.html' })).toBe('text/plain');
    expect(documentMimeType({ name: 'report.html', mimeType: 'text/html' })).toBe('text/html');
  });

  it('detects document and image MIME types when the file name has no extension', () => {
    expect(documentPreviewKind({
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })).toBe('docx');
    expect(documentPreviewKind({ mimeType: 'text/markdown; charset=utf-8' })).toBe('markdown');
    expect(documentPreviewKind({ mimeType: 'text/plain; charset=utf-8' })).toBe('text');
    expect(documentPreviewKind({ mimeType: 'text/csv; charset=utf-8' })).toBe('text');
    expect(documentPreviewKind({ mimeType: 'image/svg+xml' })).toBe('image');
  });

  it('pretty-prints JSON without changing plain text', () => {
    expect(formatDocumentText('{"name":"Moon","items":[1,2]}', 'json')).toBe(
      '{\n  "name": "Moon",\n  "items": [\n    1,\n    2\n  ]\n}',
    );
    expect(formatDocumentText('第一行\r\n第二行', 'text')).toBe('第一行\n第二行');
  });

  it('reads a newly uploaded file from the in-memory source cache', async () => {
    const url = '/api/files/files/new-brief.md';
    rememberDocumentSource(url, new Blob(['# 标题'], { type: 'text/markdown' }));

    await expect((await loadDocumentSource(url)).text()).resolves.toBe('# 标题');
  });
});
