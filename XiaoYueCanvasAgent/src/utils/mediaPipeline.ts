import type { AnnotationDocument, AnnotationShape, ImageOperation, MediaAsset, MediaOperation } from '../types';
import { createId } from './id';

const SUPPORTED_IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp']);
export const SUPPORTED_VIDEO_TYPES = new Set(['video/mp4', 'video/webm', 'video/quicktime']);

interface WorkerImageResult { width: number; height: number; blob: Blob; thumbnail: Blob; preview: Blob; row?: number; column?: number }
type WorkerAction =
  | { type: 'import'; source: Blob }
  | { type: 'crop'; source: Blob; crop: { x: number; y: number; width: number; height: number } }
  | { type: 'pixels'; source: Blob; params: { brightness: number; contrast: number; saturation: number } }
  | { type: 'annotation'; source: Blob; shapes: AnnotationShape[] }
  | { type: 'split'; source: Blob; rows: number; columns: number; stream?: boolean };

let worker: Worker | null = null;
interface PendingWorkerRequest {
  resolve: (results: WorkerImageResult[]) => void;
  reject: (error: Error) => void;
  onBatch?: (results: WorkerImageResult[]) => Promise<void>;
}

const pending = new Map<string, PendingWorkerRequest>();

const imageWorker = () => {
  if (worker) return worker;
  worker = new Worker(new URL('../workers/imageVariantWorker.ts', import.meta.url), { type: 'module' });
  worker.addEventListener('message', (event: MessageEvent<{ id: string; type?: 'batch'; ok: boolean; results?: WorkerImageResult[]; message?: string }>) => {
    const request = pending.get(event.data.id);
    if (!request) return;
    if (event.data.type === 'batch') {
      void (async () => {
        try {
          await request.onBatch?.(event.data.results ?? []);
          worker?.postMessage({ id: event.data.id, type: 'ack' });
        } catch (error) {
          pending.delete(event.data.id);
          worker?.postMessage({ id: event.data.id, type: 'cancel' });
          request.reject(error instanceof Error ? error : new Error('切片保存失败'));
        }
      })();
      return;
    }
    pending.delete(event.data.id);
    if (event.data.ok && event.data.results) request.resolve(event.data.results); else request.reject(new Error(event.data.message ?? '图片处理失败'));
  });
  worker.addEventListener('error', (event) => {
    const error = new Error(event.message || '图片 Worker 发生错误'); pending.forEach((request) => request.reject(error)); pending.clear(); worker?.terminate(); worker = null;
  });
  return worker;
};

const executeWorker = (action: WorkerAction, onBatch?: (results: WorkerImageResult[]) => Promise<void>) => new Promise<WorkerImageResult[]>((resolve, reject) => {
  const id = createId(); pending.set(id, { resolve, reject, onBatch }); imageWorker().postMessage({ id, ...action });
});

function runtimeAsset(record: MediaAsset): MediaAsset { return record; }

// Object URLs are owned by useObjectUrl and released when media leaves the viewport.
export function disposeRuntimeAsset(_asset: MediaAsset) {}

const resultAsset = (result: WorkerImageResult, name: string, derivedFrom: string | undefined, operation: MediaOperation, originalBlob = result.blob, mimeType = result.blob.type || 'image/png') => runtimeAsset({
  id: createId(), kind: 'image', name, mimeType, bytes: originalBlob.size, width: result.width, height: result.height,
  originalBlob, thumbnailBlob: result.thumbnail, previewBlob: result.preview, derivedFrom, operation, libraryState: 'project', createdAt: Date.now(),
});

export async function createImageAsset(file: File, id = createId(), metadata: { derivedFrom?: string; operation?: MediaOperation; libraryState?: 'project' | 'global' } = {}) {
  if (!SUPPORTED_IMAGE_TYPES.has(file.type)) throw new Error('仅支持 PNG、JPEG 或 WebP 图片');
  const [result] = await executeWorker({ type: 'import', source: file });
  const asset = resultAsset(result, file.name, metadata.derivedFrom, metadata.operation ?? { type: 'import', params: {} }, file, file.type);
  asset.id = id; asset.libraryState = metadata.libraryState ?? 'project'; return asset;
}

const derivedName = (source: MediaAsset, suffix: string) => `${source.name.replace(/\.[^.]+$/, '')}-${suffix}.png`;

export async function createCroppedImageAsset(source: MediaAsset, crop: { x: number; y: number; width: number; height: number }) {
  if (crop.width <= 0 || crop.height <= 0 || crop.x < 0 || crop.y < 0 || crop.x + crop.width > 1.000001 || crop.y + crop.height > 1.000001) throw new Error('裁剪范围无效');
  const [result] = await executeWorker({ type: 'crop', source: source.originalBlob, crop });
  return resultAsset(result, derivedName(source, 'crop'), source.id, { type: 'crop', params: crop });
}

export async function createSplitImageAssets(source: MediaAsset, rows: number, columns: number, onBatch?: (assets: MediaAsset[]) => Promise<void>) {
  const safeRows = Math.max(1, Math.min(8, Math.round(rows))); const safeColumns = Math.max(1, Math.min(8, Math.round(columns)));
  let resultIndex = 0;
  const toAssets = (results: WorkerImageResult[]) => results.map((result) => {
    const index = resultIndex++;
    const row = result.row ?? Math.floor(index / safeColumns) + 1;
    const column = result.column ?? index % safeColumns + 1;
    return resultAsset(result, derivedName(source, `${row}-${column}`), source.id, {
      type: 'split', params: { rows: safeRows, columns: safeColumns, row, column },
    });
  });
  const results = await executeWorker(
    { type: 'split', source: source.originalBlob, rows: safeRows, columns: safeColumns, stream: Boolean(onBatch) },
    onBatch ? async (batch) => onBatch(toAssets(batch)) : undefined,
  );
  return onBatch ? [] : toAssets(results);
}

export async function createPixelAdjustedAsset(source: MediaAsset, params: { brightness: number; contrast: number; saturation: number }) {
  const safe = { brightness: Math.max(-50, Math.min(50, params.brightness)), contrast: Math.max(-50, Math.min(50, params.contrast)), saturation: Math.max(-100, Math.min(100, params.saturation)) };
  const [result] = await executeWorker({ type: 'pixels', source: source.originalBlob, params: safe });
  return resultAsset(result, derivedName(source, 'pixels'), source.id, { type: 'pixels', params: safe });
}

export async function createAnnotatedImageAsset(source: MediaAsset, annotation: AnnotationDocument) {
  const [result] = await executeWorker({ type: 'annotation', source: source.originalBlob, shapes: annotation.shapes });
  return resultAsset(result, derivedName(source, 'annotated'), source.id, { type: 'annotation', params: { shapes: annotation.shapes.length } });
}

export function splitImageBounds(width: number, height: number, rows: number, columns: number) {
  const safeRows = Math.max(1, Math.min(8, Math.round(rows))); const safeColumns = Math.max(1, Math.min(8, Math.round(columns)));
  return Array.from({ length: safeRows * safeColumns }, (_, index) => {
    const row = Math.floor(index / safeColumns); const column = index % safeColumns;
    const x = Math.round(column * width / safeColumns); const y = Math.round(row * height / safeRows);
    return { row: row + 1, column: column + 1, x, y, width: Math.round((column + 1) * width / safeColumns) - x, height: Math.round((row + 1) * height / safeRows) - y };
  });
}

export function annotationToSvg(annotation: AnnotationDocument, width: number, height: number) {
  const escape = (value: string) => value.replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[char]!);
  const content = annotation.shapes.map((shape) => {
    const strokeWidth = shape.width * Math.min(width, height);
    if (shape.type === 'brush') return `<polyline points="${shape.points.map((point) => `${point.x * width},${point.y * height}`).join(' ')}" fill="none" stroke="${escape(shape.color)}" stroke-width="${strokeWidth}" stroke-linecap="round" stroke-linejoin="round"/>`;
    if (shape.type === 'rectangle') { const x = Math.min(shape.start.x, shape.end.x) * width; const y = Math.min(shape.start.y, shape.end.y) * height; return `<rect x="${x}" y="${y}" width="${Math.abs(shape.end.x - shape.start.x) * width}" height="${Math.abs(shape.end.y - shape.start.y) * height}" fill="none" stroke="${escape(shape.color)}" stroke-width="${strokeWidth}"/>`; }
    if (shape.type === 'text') return `<text x="${shape.point.x * width}" y="${shape.point.y * height}" fill="${escape(shape.color)}" font-size="${shape.fontSize * height}">${escape(shape.text)}</text>`;
    return `<line x1="${shape.start.x * width}" y1="${shape.start.y * height}" x2="${shape.end.x * width}" y2="${shape.end.y * height}" stroke="${escape(shape.color)}" stroke-width="${strokeWidth}" marker-end="url(#arrow)"/>`;
  }).join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}"><defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="context-stroke"/></marker></defs>${content}</svg>`;
}

export function getMediaNodeSize(width: number, height: number) {
  const scale = Math.min(380 / width, 340 / height, 1); let nextWidth = Math.max(1, Math.round(width * scale)); let nextHeight = Math.max(1, Math.round(height * scale));
  if (Math.min(nextWidth, nextHeight) < 180) { const grow = 180 / Math.min(nextWidth, nextHeight); nextWidth = Math.round(nextWidth * grow); nextHeight = Math.round(nextHeight * grow); }
  return { width: nextWidth, height: nextHeight };
}

const videoFrame = (video: HTMLVideoElement, maxDimension: number) => new Promise<Blob>((resolve, reject) => {
  const ratio = Math.min(1, maxDimension / Math.max(video.videoWidth, video.videoHeight));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(video.videoWidth * ratio));
  canvas.height = Math.max(1, Math.round(video.videoHeight * ratio));
  const context = canvas.getContext('2d');
  if (!context) { reject(new Error('浏览器无法创建视频封面')); return; }
  context.drawImage(video, 0, 0, canvas.width, canvas.height);
  canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error('视频封面编码失败')), 'image/webp', .82);
});

export async function createVideoAsset(
  file: File,
  id = createId(),
  metadata: { derivedFrom?: string; operation?: MediaOperation } = {},
) {
  if (!SUPPORTED_VIDEO_TYPES.has(file.type)) throw new Error('仅支持 MP4、WebM 或 MOV 视频');
  const url = URL.createObjectURL(file);
  const video = document.createElement('video');
  video.preload = 'metadata';
  video.muted = true;
  video.playsInline = true;
  video.src = url;
  try {
    await new Promise<void>((resolve, reject) => {
      video.onloadedmetadata = () => resolve();
      video.onerror = () => reject(new Error('视频元数据读取失败'));
    });
    if (!video.videoWidth || !video.videoHeight) throw new Error('视频没有有效画面');
    const frameTime = Math.min(1, Math.max(0, (Number.isFinite(video.duration) ? video.duration : 0) * .1));
    await new Promise<void>((resolve, reject) => {
      const ready = () => resolve();
      const failed = () => reject(new Error('视频封面解码失败'));
      video.onseeked = ready;
      video.onloadeddata = ready;
      video.onerror = failed;
      if (video.readyState >= 2 && frameTime === 0) resolve(); else video.currentTime = frameTime;
    });
    const thumbnailBlob = await videoFrame(video, 480);
    const previewBlob = await videoFrame(video, 1200);
    return {
      id,
      kind: 'video' as const,
      name: file.name,
      mimeType: file.type,
      bytes: file.size,
      width: video.videoWidth,
      height: video.videoHeight,
      durationSeconds: Number.isFinite(video.duration) ? video.duration : undefined,
      originalBlob: file,
      thumbnailBlob,
      previewBlob,
      derivedFrom: metadata.derivedFrom,
      operation: metadata.operation,
      libraryState: 'project' as const,
      createdAt: Date.now(),
    };
  } finally {
    video.pause();
    video.removeAttribute('src');
    video.load();
    URL.revokeObjectURL(url);
  }
}

export function getVideoNodeSize(width: number, height: number) {
  const ratio = Math.max(.56, Math.min(1.78, width / height));
  const nextWidth = 420;
  return { width: nextWidth, height: Math.max(236, Math.round(nextWidth / ratio)) };
}

export async function processWithConcurrency<T>(items: T[], limit: number, task: (item: T, index: number) => Promise<void>) {
  let cursor = 0;
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => { while (cursor < items.length) { const index = cursor++; await task(items[index], index); } }));
}
