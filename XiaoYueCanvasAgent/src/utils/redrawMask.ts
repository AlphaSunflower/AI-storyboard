import type { JobRequest, RedrawMaskDocument, RedrawMaskMark } from '../types';

export interface RedrawOutputOptions {
  count: 1 | 2 | 3 | 4;
  modelTier: 'normal' | 'pro';
  resolution: '1K' | '2K' | '4K';
}

export type RedrawReference =
  | { type: 'asset'; assetId: string; label: string }
  | { type: 'file'; file: File };

type RedrawJobContext = Pick<JobRequest, 'clientRequestId' | 'conversationId' | 'context'>;

const markHasArea = (mark: RedrawMaskMark) => {
  if (mark.type === 'brush') return mark.points.length > 0 && mark.width > 0;
  return Math.abs(mark.end.x - mark.start.x) > 0.002 && Math.abs(mark.end.y - mark.start.y) > 0.002;
};

const drawMaskMark = (
  context: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D,
  mark: RedrawMaskMark,
  width: number,
  height: number,
) => {
  context.save();
  context.globalCompositeOperation = mark.operation === 'paint' ? 'source-over' : 'destination-out';
  context.fillStyle = '#fff';
  context.strokeStyle = '#fff';

  if (mark.type === 'rectangle') {
    const left = Math.min(mark.start.x, mark.end.x) * width;
    const top = Math.min(mark.start.y, mark.end.y) * height;
    context.fillRect(left, top, Math.abs(mark.end.x - mark.start.x) * width, Math.abs(mark.end.y - mark.start.y) * height);
    context.restore();
    return;
  }

  const lineWidth = Math.max(1, mark.width * Math.min(width, height));
  context.lineWidth = lineWidth;
  context.lineCap = 'round';
  context.lineJoin = 'round';
  if (mark.points.length === 1) {
    context.beginPath();
    context.arc(mark.points[0].x * width, mark.points[0].y * height, lineWidth / 2, 0, Math.PI * 2);
    context.fill();
  } else if (mark.points.length > 1) {
    context.beginPath();
    context.moveTo(mark.points[0].x * width, mark.points[0].y * height);
    mark.points.slice(1).forEach((point) => context.lineTo(point.x * width, point.y * height));
    context.stroke();
  }
  context.restore();
};

export async function rasterizeRedrawMask(mask: RedrawMaskDocument, width: number, height: number) {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width <= 0 || height <= 0) {
    throw new Error('原图尺寸无效，无法生成重绘蒙版');
  }
  if (width * height > 80_000_000) throw new Error('原图尺寸过大，无法在浏览器中生成重绘蒙版');

  const canvas = typeof OffscreenCanvas === 'undefined'
    ? Object.assign(document.createElement('canvas'), { width, height })
    : new OffscreenCanvas(width, height);
  const context = canvas.getContext('2d');
  if (!context) throw new Error('浏览器无法创建重绘蒙版');
  mask.marks.forEach((mark) => drawMaskMark(context, mark, width, height));

  const validationWidth = Math.max(1, Math.min(256, width));
  const validationHeight = Math.max(1, Math.round(validationWidth * height / width));
  const validationCanvas = typeof OffscreenCanvas === 'undefined'
    ? Object.assign(document.createElement('canvas'), { width: validationWidth, height: validationHeight })
    : new OffscreenCanvas(validationWidth, validationHeight);
  const validationContext = validationCanvas.getContext('2d');
  if (!validationContext) throw new Error('浏览器无法验证重绘蒙版');
  mask.marks.forEach((mark) => drawMaskMark(validationContext, mark, validationWidth, validationHeight));
  const pixels = validationContext.getImageData(0, 0, validationWidth, validationHeight).data;
  let hasVisibleMask = false;
  for (let index = 3; index < pixels.length; index += 4) {
    if (pixels[index] > 0) { hasVisibleMask = true; break; }
  }
  if (!hasVisibleMask) throw new Error('请先在图片上绘制蒙版');

  if (canvas instanceof OffscreenCanvas) return canvas.convertToBlob({ type: 'image/png' });
  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error('重绘蒙版编码失败')), 'image/png');
  });
}

export const hasDrawableRedrawMask = (document: RedrawMaskDocument) => {
  // An eraser cannot create a selection without an earlier paint mark.
  let hasSelection = false;
  for (const mark of document.marks) {
    if (!markHasArea(mark)) continue;
    if (mark.operation === 'paint') hasSelection = true;
  }
  return hasSelection;
};

export function createRedrawJobRequest(
  sourceAssetId: string,
  prompt: string,
  mask: RedrawMaskDocument,
  output: RedrawOutputOptions,
  reference: RedrawReference | null,
  jobContext: RedrawJobContext,
): JobRequest {
  const normalizedPrompt = prompt.trim();
  if (!normalizedPrompt) throw new Error('请输入重绘要求');
  if (!hasDrawableRedrawMask(mask)) throw new Error('请先在图片上绘制蒙版');

  return {
    ...jobContext,
    capabilityId: 'image.redraw',
    sourceAssetIds: [sourceAssetId, ...(reference?.type === 'asset' ? [reference.assetId] : [])],
    params: {
      mode: 'image-to-image',
      prompt: normalizedPrompt,
      mask,
      count: output.count,
      modelTier: output.modelTier,
      resolution: output.modelTier === 'pro' ? output.resolution : '1K',
      aspectRatio: 'source',
      reference: reference?.type === 'file'
        ? { type: 'file', file: reference.file, name: reference.file.name, mimeType: reference.file.type, bytes: reference.file.size }
        : reference,
    },
  };
}
