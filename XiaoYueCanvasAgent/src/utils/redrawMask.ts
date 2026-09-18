import type { RedrawMaskDocument, RedrawMaskMark } from '../types';

export const REDRAW_REGION_COLORS = [
  '#ff5f5f',
  '#59d8ff',
  '#22c55e',
  '#f59e0b',
  '#a855f7',
  '#06b6d4',
  '#eab308',
  '#ec4899',
] as const;

const REDRAW_COLOR_NAMES: Record<string, string> = {
  '#ff5f5f': '红色',
  '#59d8ff': '蓝色',
  '#d7ff54': '黄绿色',
  '#22c55e': '绿色',
  '#f59e0b': '橙色',
  '#a855f7': '紫色',
  '#06b6d4': '青色',
  '#eab308': '黄色',
  '#ec4899': '粉色',
};

const CHINESE_NUMBERS = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十'];

export type RedrawPaintMark = RedrawMaskMark & {
  operation: 'paint';
  intent: string;
  color: string;
  createdAt: number;
};

const markHasArea = (mark: RedrawMaskMark) => {
  if (mark.type === 'brush') return mark.points.length > 0 && mark.width > 0;
  return Math.abs(mark.end.x - mark.start.x) > 0.002 && Math.abs(mark.end.y - mark.start.y) > 0.002;
};

export function redrawRegionColor(index: number) {
  return REDRAW_REGION_COLORS[index % REDRAW_REGION_COLORS.length];
}

export const redrawRegionColorName = (color?: string) =>
  REDRAW_COLOR_NAMES[(color ?? '').toLowerCase()] ?? '自定义颜色';

export function redrawRegionBounds(mark: RedrawPaintMark) {
  if (mark.type === 'rectangle') {
    return {
      x: Math.min(mark.start.x, mark.end.x),
      y: Math.min(mark.start.y, mark.end.y),
      width: Math.abs(mark.end.x - mark.start.x),
      height: Math.abs(mark.end.y - mark.start.y),
    };
  }
  const padding = Math.max(0, mark.width / 2);
  const xs = mark.points.map((point) => point.x);
  const ys = mark.points.map((point) => point.y);
  const left = Math.max(0, Math.min(...xs) - padding);
  const top = Math.max(0, Math.min(...ys) - padding);
  const right = Math.min(1, Math.max(...xs) + padding);
  const bottom = Math.min(1, Math.max(...ys) + padding);
  return {
    x: left,
    y: top,
    width: Math.max(0, right - left),
    height: Math.max(0, bottom - top),
  };
}

const drawMaskMark = (
  context: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D,
  mark: RedrawMaskMark,
  width: number,
  height: number,
) => {
  context.save();
  // OpenAI-compatible edit masks use transparent pixels for the editable region.
  context.globalCompositeOperation = mark.operation === 'paint' ? 'destination-out' : 'source-over';
  context.fillStyle = '#000';
  context.strokeStyle = '#000';

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
  context.fillStyle = '#000';
  context.fillRect(0, 0, width, height);
  mask.marks.forEach((mark) => drawMaskMark(context, mark, width, height));

  const validationWidth = Math.max(1, Math.min(256, width));
  const validationHeight = Math.max(1, Math.round(validationWidth * height / width));
  const validationCanvas = typeof OffscreenCanvas === 'undefined'
    ? Object.assign(document.createElement('canvas'), { width: validationWidth, height: validationHeight })
    : new OffscreenCanvas(validationWidth, validationHeight);
  const validationContext = validationCanvas.getContext('2d');
  if (!validationContext) throw new Error('浏览器无法验证重绘蒙版');
  validationContext.fillStyle = '#000';
  validationContext.fillRect(0, 0, validationWidth, validationHeight);
  mask.marks.forEach((mark) => drawMaskMark(validationContext, mark, validationWidth, validationHeight));
  const pixels = validationContext.getImageData(0, 0, validationWidth, validationHeight).data;
  let hasEditableMask = false;
  for (let index = 3; index < pixels.length; index += 4) {
    if (pixels[index] < 255) {
      hasEditableMask = true;
      break;
    }
  }
  if (!hasEditableMask) throw new Error('请先在图片上绘制蒙版');

  if (canvas instanceof OffscreenCanvas) return canvas.convertToBlob({ type: 'image/png' });
  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error('重绘蒙版编码失败')), 'image/png');
  });
}

export const hasDrawableRedrawMask = (document: RedrawMaskDocument) =>
  document.marks.some((mark) => mark.operation === 'paint' && markHasArea(mark));

export function redrawPaintMarks(document: RedrawMaskDocument): RedrawPaintMark[] {
  return document.marks
    .filter((mark): mark is RedrawPaintMark =>
      mark.operation === 'paint'
      && markHasArea(mark)
      && typeof mark.intent === 'string'
      && mark.intent.trim().length > 0)
    .sort((left, right) => (left.createdAt ?? 0) - (right.createdAt ?? 0));
}

export function buildRedrawPrompt(document: RedrawMaskDocument) {
  const regions = redrawPaintMarks(document);
  if (!regions.length) throw new Error('请先为每个选区填写修改意图');
  const clauses = regions.map((mark, index) => {
    const number = CHINESE_NUMBERS[index] ?? String(index + 1);
    const color = redrawRegionColorName(mark.color);
    const regionType = mark.type === 'brush' ? '涂抹位置' : '框选位置';
    const intent = mark.intent.trim().replace(/^(?:在这个位置|这个位置|此处|这里)[，,\s]*/, '');
    if (/^(?:放|放入|添加|增加|生成|制作|放置)/.test(intent)) {
      return `${color}${regionType}${number}${intent}`;
    }
    return `${color}${regionType}${number}按要求处理：${intent}`;
  });
  return `请处理这张图：房间内${clauses.join('；')}。不要改变其他布局，确保修改内容与整张图的风格一致。`;
}

export function validateRedrawDocument(document: RedrawMaskDocument) {
  const painted = document.marks.filter((mark) => mark.operation === 'paint' && markHasArea(mark));
  if (!painted.length) throw new Error('请先在图片上绘制蒙版');
  const missingIntent = painted.find((mark) => !mark.intent?.trim());
  if (missingIntent) throw new Error('请为每个选区填写修改意图');
  return {
    regions: redrawPaintMarks(document),
    prompt: buildRedrawPrompt(document),
  };
}
