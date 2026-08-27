import type { AnnotationShape } from '../types';

type WorkerRequest =
  | { id: string; type: 'import'; source: Blob }
  | { id: string; type: 'crop'; source: Blob; crop: { x: number; y: number; width: number; height: number } }
  | { id: string; type: 'pixels'; source: Blob; params: { brightness: number; contrast: number; saturation: number } }
  | { id: string; type: 'annotation'; source: Blob; shapes: AnnotationShape[] }
  | { id: string; type: 'split'; source: Blob; rows: number; columns: number; stream?: boolean };

type WorkerControl = { id: string; type: 'ack' | 'cancel' };

interface WorkerImageResult {
  width: number;
  height: number;
  blob: Blob;
  thumbnail: Blob;
  preview: Blob;
  row?: number;
  column?: number;
}

const MAX_IMAGE_PIXELS = 64_000_000;
const SPLIT_BATCH_SIZE = 4;
const batchWaiters = new Map<string, (continueTask: boolean) => void>();

const waitForBatchAck = (id: string) => new Promise<boolean>((resolve) => batchWaiters.set(id, resolve));

const variant = async (source: CanvasImageSource, width: number, height: number, maxEdge: number, quality: number) => {
  const scale = Math.min(1, maxEdge / Math.max(width, height));
  const targetWidth = Math.max(1, Math.round(width * scale));
  const targetHeight = Math.max(1, Math.round(height * scale));
  const canvas = new OffscreenCanvas(targetWidth, targetHeight);
  const context = canvas.getContext('2d', { alpha: true });
  if (!context) throw new Error('浏览器不支持离屏 Canvas 2D');
  context.imageSmoothingEnabled = true;
  context.imageSmoothingQuality = 'high';
  context.drawImage(source, 0, 0, width, height, 0, 0, targetWidth, targetHeight);
  return canvas.convertToBlob({ type: 'image/webp', quality });
};

const encodeResult = async (canvas: OffscreenCanvas, meta: Pick<WorkerImageResult, 'row' | 'column'> = {}): Promise<WorkerImageResult> => {
  const [blob, thumbnail, preview] = await Promise.all([
    canvas.convertToBlob({ type: 'image/png' }),
    variant(canvas, canvas.width, canvas.height, 480, .8),
    variant(canvas, canvas.width, canvas.height, 1200, .86),
  ]);
  return { width: canvas.width, height: canvas.height, blob, thumbnail, preview, ...meta };
};

const drawShape = (context: OffscreenCanvasRenderingContext2D, shape: AnnotationShape, width: number, height: number) => {
  context.strokeStyle = shape.color; context.fillStyle = shape.color;
  context.lineWidth = shape.width * Math.min(width, height); context.lineCap = 'round'; context.lineJoin = 'round';
  if (shape.type === 'brush') {
    context.beginPath(); shape.points.forEach((point, index) => index ? context.lineTo(point.x * width, point.y * height) : context.moveTo(point.x * width, point.y * height)); context.stroke();
  } else if (shape.type === 'rectangle') {
    context.strokeRect(shape.start.x * width, shape.start.y * height, (shape.end.x - shape.start.x) * width, (shape.end.y - shape.start.y) * height);
  } else if (shape.type === 'arrow') {
    const sx = shape.start.x * width; const sy = shape.start.y * height; const ex = shape.end.x * width; const ey = shape.end.y * height;
    const angle = Math.atan2(ey - sy, ex - sx); const head = Math.max(12, context.lineWidth * 5);
    context.beginPath(); context.moveTo(sx, sy); context.lineTo(ex, ey); context.stroke();
    context.beginPath(); context.moveTo(ex, ey); context.lineTo(ex - head * Math.cos(angle - Math.PI / 6), ey - head * Math.sin(angle - Math.PI / 6)); context.lineTo(ex - head * Math.cos(angle + Math.PI / 6), ey - head * Math.sin(angle + Math.PI / 6)); context.closePath(); context.fill();
  } else {
    context.font = `600 ${shape.fontSize * height}px sans-serif`; context.fillText(shape.text, shape.point.x * width, shape.point.y * height);
  }
};

const processRequest = async (request: WorkerRequest) => {
  let bitmap: ImageBitmap | null = null;
  try {
    bitmap = await createImageBitmap(request.source, { imageOrientation: 'from-image' });
    if (bitmap.width * bitmap.height > MAX_IMAGE_PIXELS) throw new Error('图片像素超过 6400 万限制');
    if (request.type === 'import') {
      const [thumbnail, preview] = await Promise.all([variant(bitmap, bitmap.width, bitmap.height, 480, .8), variant(bitmap, bitmap.width, bitmap.height, 1200, .86)]);
      self.postMessage({ id: request.id, ok: true, results: [{ width: bitmap.width, height: bitmap.height, blob: request.source, thumbnail, preview }] });
      return;
    }
    if (request.type === 'split') {
      let batch: WorkerImageResult[] = [];
      const results: WorkerImageResult[] = [];
      const rows = Math.max(1, Math.min(8, Math.round(request.rows))); const columns = Math.max(1, Math.min(8, Math.round(request.columns)));
      for (let row = 0; row < rows; row += 1) {
        const y = Math.round(row * bitmap.height / rows); const bottom = Math.round((row + 1) * bitmap.height / rows);
        for (let column = 0; column < columns; column += 1) {
          const x = Math.round(column * bitmap.width / columns); const right = Math.round((column + 1) * bitmap.width / columns);
          const canvas = new OffscreenCanvas(Math.max(1, right - x), Math.max(1, bottom - y)); const context = canvas.getContext('2d', { alpha: true });
          if (!context) throw new Error('浏览器不支持离屏 Canvas 2D');
          context.drawImage(bitmap, x, y, canvas.width, canvas.height, 0, 0, canvas.width, canvas.height);
          const result = await encodeResult(canvas, { row: row + 1, column: column + 1 });
          if (request.stream) batch.push(result);
          else results.push(result);
          const isLast = row === rows - 1 && column === columns - 1;
          if (request.stream && (batch.length >= SPLIT_BATCH_SIZE || isLast)) {
            self.postMessage({ id: request.id, type: 'batch', ok: true, results: batch });
            batch = [];
            if (!await waitForBatchAck(request.id)) throw new Error('切分任务已取消');
          }
        }
      }
      self.postMessage({ id: request.id, ok: true, results }); return;
    }
    let canvas: OffscreenCanvas;
    if (request.type === 'crop') {
      const sourceX = Math.round(Math.max(0, request.crop.x) * bitmap.width); const sourceY = Math.round(Math.max(0, request.crop.y) * bitmap.height);
      const sourceWidth = Math.max(1, Math.round(Math.min(1 - request.crop.x, request.crop.width) * bitmap.width)); const sourceHeight = Math.max(1, Math.round(Math.min(1 - request.crop.y, request.crop.height) * bitmap.height));
      canvas = new OffscreenCanvas(sourceWidth, sourceHeight); const context = canvas.getContext('2d', { alpha: true }); if (!context) throw new Error('浏览器不支持离屏 Canvas 2D');
      context.drawImage(bitmap, sourceX, sourceY, sourceWidth, sourceHeight, 0, 0, sourceWidth, sourceHeight);
    } else {
      canvas = new OffscreenCanvas(bitmap.width, bitmap.height); const context = canvas.getContext('2d', { alpha: true }); if (!context) throw new Error('浏览器不支持离屏 Canvas 2D');
      if (request.type === 'pixels') context.filter = `brightness(${100 + request.params.brightness}%) contrast(${100 + request.params.contrast}%) saturate(${100 + request.params.saturation}%)`;
      context.drawImage(bitmap, 0, 0);
      if (request.type === 'annotation') request.shapes.forEach((shape) => drawShape(context, shape, bitmap!.width, bitmap!.height));
    }
    self.postMessage({ id: request.id, ok: true, results: [await encodeResult(canvas)] });
  } catch (error) {
    self.postMessage({ id: request.id, ok: false, message: error instanceof Error ? error.message : '图片处理失败' });
  } finally { bitmap?.close(); }
};

let taskQueue = Promise.resolve();
self.addEventListener('message', (event: MessageEvent<WorkerRequest | WorkerControl>) => {
  const request = event.data;
  if (!('source' in request)) {
    const resolve = batchWaiters.get(request.id);
    if (resolve) {
      batchWaiters.delete(request.id);
      resolve(request.type === 'ack');
    }
    return;
  }
  taskQueue = taskQueue.then(() => processRequest(request), () => processRequest(request));
});

export {};
