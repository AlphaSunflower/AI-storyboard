import { createId } from './id';

interface ArchiveResponse {
  id: string;
  ok: boolean;
  bytes?: Uint8Array;
  entries?: Record<string, Uint8Array>;
  message?: string;
}

let archiveWorker: Worker | null = null;
const pending = new Map<string, { resolve: (value: unknown) => void; reject: (error: Error) => void }>();

const getArchiveWorker = () => {
  if (archiveWorker) return archiveWorker;
  archiveWorker = new Worker(new URL('../workers/archiveWorker.ts', import.meta.url), { type: 'module' });
  archiveWorker.addEventListener('message', (event: MessageEvent<ArchiveResponse>) => {
    const request = pending.get(event.data.id);
    if (!request) return;
    pending.delete(event.data.id);
    if (!event.data.ok) request.reject(new Error(event.data.message ?? '压缩文件处理失败'));
    else request.resolve(event.data.bytes ?? event.data.entries ?? {});
  });
  archiveWorker.addEventListener('error', (event) => {
    const error = new Error(event.message || '压缩 Worker 发生错误');
    pending.forEach((request) => request.reject(error));
    pending.clear();
    archiveWorker?.terminate();
    archiveWorker = null;
  });
  return archiveWorker;
};

export function zipInWorker(files: Record<string, Uint8Array>, level: 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 = 6) {
  if (typeof Worker === 'undefined') return import('fflate').then(({ zipSync }) => zipSync(files, { level }));
  return new Promise<Uint8Array>((resolve, reject) => {
    const id = createId();
    pending.set(id, { resolve: (value) => resolve(value as Uint8Array), reject });
    const transfer = [...new Set(Object.values(files).map((entry) => entry.buffer))];
    getArchiveWorker().postMessage({ id, type: 'zip', files, level }, transfer);
  });
}

export function unzipInWorker(bytes: Uint8Array) {
  if (typeof Worker === 'undefined') return import('fflate').then(({ unzipSync }) => unzipSync(bytes));
  return new Promise<Record<string, Uint8Array>>((resolve, reject) => {
    const id = createId();
    pending.set(id, { resolve: (value) => resolve(value as Record<string, Uint8Array>), reject });
    getArchiveWorker().postMessage({ id, type: 'unzip', bytes }, [bytes.buffer]);
  });
}
