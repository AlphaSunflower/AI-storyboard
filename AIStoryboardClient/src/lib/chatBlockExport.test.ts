import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  blockOrdinal,
  codeBlockLanguage,
  codeBlockText,
  copyImageToClipboard,
  copyPlainText,
  downloadTextFile,
  extensionForLanguage,
  tableToCsv,
  tableToMarkdown,
  tableToRows,
  type HastLike,
} from './chatBlockExport';

const text = (value: string): HastLike => ({ type: 'text', value });
const element = (tagName: string, children: HastLike[] = [], properties?: unknown): HastLike => ({
  type: 'element',
  tagName,
  children,
  properties,
});

const createObjectURL = vi.fn(() => 'blob:mock');
const revokeObjectURL = vi.fn();
let originalCreateObjectURL: unknown;
let originalRevokeObjectURL: unknown;

beforeEach(() => {
  originalCreateObjectURL = URL.createObjectURL;
  originalRevokeObjectURL = URL.revokeObjectURL;
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: createObjectURL });
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: revokeObjectURL });
  createObjectURL.mockClear();
  revokeObjectURL.mockClear();
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    writable: true,
    value: originalCreateObjectURL,
  });
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    writable: true,
    value: originalRevokeObjectURL,
  });
});

describe('extensionForLanguage', () => {
  it('maps common fence languages to extensions', () => {
    expect(extensionForLanguage('ts')).toBe('ts');
    expect(extensionForLanguage('typescript')).toBe('ts');
    expect(extensionForLanguage('Python')).toBe('py');
    expect(extensionForLanguage('language-json')).toBe('json');
    expect(extensionForLanguage('markdown')).toBe('md');
    expect(extensionForLanguage('shell')).toBe('sh');
    expect(extensionForLanguage('c++')).toBe('cpp');
  });

  it('falls back to txt for text and unknown languages', () => {
    expect(extensionForLanguage('')).toBe('txt');
    expect(extensionForLanguage('   ')).toBe('txt');
    expect(extensionForLanguage('text')).toBe('txt');
    expect(extensionForLanguage('plaintext')).toBe('txt');
    expect(extensionForLanguage('brainfuck')).toBe('txt');
  });
});

describe('table export', () => {
  const table = element('table', [
    element('thead', [
      element('tr', [element('th', [text('项目')]), element('th', [text('设计')])]),
    ]),
    element('tbody', [
      element('tr', [element('td', [text('风格')]), element('td', [text('现代'), element('br'), text('轻奢')])]),
      element('tr', [element('td', [text('a,b')]), element('td', [text('x"y')])]),
    ]),
  ]);

  it('reads rows in document order and turns <br> into newlines', () => {
    expect(tableToRows(table)).toEqual([
      ['项目', '设计'],
      ['风格', '现代\n轻奢'],
      ['a,b', 'x"y'],
    ]);
  });

  it('returns nothing when there is no table node', () => {
    expect(tableToRows(element('div', [text('无表格')]))).toEqual([]);
    expect(tableToRows(undefined)).toEqual([]);
  });

  it('writes RFC4180 csv with CRLF rows', () => {
    expect(tableToCsv(tableToRows(table))).toBe('项目,设计\r\n风格,"现代\n轻奢"\r\n"a,b","x""y"');
  });

  it('rebuilds a GFM table and escapes pipe characters', () => {
    expect(tableToMarkdown([['项目', '设计'], ['风格 | 现代', '轻奢']]))
      .toBe('| 项目 | 设计 |\n| --- | --- |\n| 风格 \\| 现代 | 轻奢 |');
    expect(tableToMarkdown([])).toBe('');
  });
});

describe('code block helpers', () => {
  const pre = element('pre', [
    element('code', [text('const a = 1;\n')], { className: ['language-ts'] }),
  ]);

  it('reads the fence language from the code class name', () => {
    expect(codeBlockLanguage(pre)).toBe('ts');
    expect(codeBlockLanguage(element('pre', [element('code', [text('x')])]))).toBe('');
    expect(codeBlockLanguage(undefined)).toBe('');
  });

  it('returns the code text without the fence trailing newline', () => {
    expect(codeBlockText(pre)).toBe('const a = 1;');
  });
});

describe('blockOrdinal', () => {
  it('prefers the source start line and falls back otherwise', () => {
    expect(blockOrdinal({ position: { start: { line: 7 } } }, 3)).toBe(7);
    expect(blockOrdinal({ position: { start: {} } }, 3)).toBe(3);
    expect(blockOrdinal(undefined, 3)).toBe(3);
  });
});

describe('downloadTextFile', () => {
  it('downloads through a blob url and reminds the browser to revoke it', () => {
    const appendChild = vi.spyOn(document.body, 'appendChild');
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    vi.useFakeTimers();

    downloadTextFile('table-1.csv', 'a,b', 'text/csv;charset=utf-8');

    const link = appendChild.mock.calls.at(-1)?.[0] as HTMLAnchorElement;
    expect(link.download).toBe('table-1.csv');
    expect(link.href).toBe('blob:mock');
    expect(click).toHaveBeenCalledTimes(1);

    vi.runAllTimers();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock');
    vi.useRealTimers();
  });

  it('falls back to a data uri when blob urls are unavailable', () => {
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      writable: true,
      value: undefined,
    });
    const appendChild = vi.spyOn(document.body, 'appendChild');
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    downloadTextFile('note.txt', '你好', 'text/plain;charset=utf-8');

    const link = appendChild.mock.calls.at(-1)?.[0] as HTMLAnchorElement;
    expect(link.download).toBe('note.txt');
    expect(link.getAttribute('href')?.startsWith('data:text/plain;charset=utf-8,')).toBe(true);
  });
});

describe('clipboard helpers', () => {
  const writeText = vi.fn(async () => undefined);
  const write = vi.fn(async () => undefined);

  beforeEach(() => {
    writeText.mockClear();
    write.mockClear();
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText, write },
    });
  });

  afterEach(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
  });

  it('reports a failed copy instead of throwing when the clipboard is missing', async () => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
    await expect(copyPlainText('文本')).resolves.toBe(false);
  });

  it('copies the image url when the clipboard cannot take bitmaps', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline'); }));

    await expect(copyImageToClipboard('https://cdn.example.com/a.png')).resolves.toBe('url');
    expect(writeText).toHaveBeenCalledWith('https://cdn.example.com/a.png');
  });

  it('copies data uris as plain text without fetching', async () => {
    const fetchMock = vi.fn(async () => { throw new Error('should not fetch'); });
    vi.stubGlobal('fetch', fetchMock);

    await expect(copyImageToClipboard('data:image/png;base64,AAAA')).resolves.toBe('url');
    expect(fetchMock).not.toHaveBeenCalled();
    expect(writeText).toHaveBeenCalledWith('data:image/png;base64,AAAA');
  });

  it('writes a png bitmap when ClipboardItem is available', async () => {
    const blob = new Blob(['png-bytes'], { type: 'image/png' });
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, blob: async () => blob })));
    class FakeClipboardItem {
      items: Record<string, Blob>;
      constructor(items: Record<string, Blob>) {
        this.items = items;
      }
    }
    vi.stubGlobal('ClipboardItem', FakeClipboardItem);

    await expect(copyImageToClipboard('https://cdn.example.com/a.png')).resolves.toBe('bitmap');
    expect(write).toHaveBeenCalledTimes(1);
    const [items] = write.mock.calls[0] as unknown as [FakeClipboardItem[]];
    expect(items[0]).toBeInstanceOf(FakeClipboardItem);
    expect(items[0].items['image/png']).toBe(blob);
  });

  it('fails when neither the bitmap path nor the text clipboard works', async () => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline'); }));

    await expect(copyImageToClipboard('https://cdn.example.com/a.png')).resolves.toBe('failed');
  });
});
