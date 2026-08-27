import { unzipSync, zipSync } from 'fflate';

type ArchiveRequest =
  | { id: string; type: 'zip'; files: Record<string, Uint8Array>; level?: 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 }
  | { id: string; type: 'unzip'; bytes: Uint8Array };

self.addEventListener('message', (event: MessageEvent<ArchiveRequest>) => {
  const request = event.data;
  try {
    if (request.type === 'zip') {
      const bytes = zipSync(request.files, { level: request.level ?? 6 });
      self.postMessage({ id: request.id, ok: true, bytes }, { transfer: [bytes.buffer] });
      return;
    }
    const entries = unzipSync(request.bytes);
    const transfer = [...new Set(Object.values(entries).map((entry) => entry.buffer))];
    self.postMessage({ id: request.id, ok: true, entries }, { transfer });
  } catch (error) {
    self.postMessage({ id: request.id, ok: false, message: error instanceof Error ? error.message : '压缩文件处理失败' });
  }
});

export {};
