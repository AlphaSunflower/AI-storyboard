// 图片裁剪几何：单一真源 = 原图归一化坐标系（0-1，左上为原点）下的裁剪矩形。
// 「参数裁剪」（比例/缩放/水平/垂直）与「在图像上直接拖拽」两条路径互为反函数，共用同一矩形。
export interface CropRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

// 裁剪框最小尺寸（占原图宽/高的比例），防止拖成一条线或 0 面积。
export const MIN_CROP_SIZE = 0.08;
const EPSILON = 1e-6;
const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));

// 由参数推导裁剪矩形：ratio 为像素宽高比（null = 原图比例），sourceRatio = 原图宽 / 高。
export const cropRectFromParams = (
  ratio: number | null,
  zoom: number,
  panX: number,
  panY: number,
  sourceRatio: number,
): CropRect => {
  let width = 1;
  let height = 1;
  if (ratio !== null) {
    if (ratio > sourceRatio) height = sourceRatio / ratio;
    else width = ratio / sourceRatio;
  }
  const safeZoom = Math.max(zoom, 1);
  width = Math.min(width / safeZoom, 1);
  height = Math.min(height / safeZoom, 1);
  const travelX = 1 - width;
  const travelY = 1 - height;
  return {
    x: travelX / 2 + (clamp(panX, -100, 100) / 100) * travelX / 2,
    y: travelY / 2 + (clamp(panY, -100, 100) / 100) * travelY / 2,
    width,
    height,
  };
};


// 平移裁剪框（尺寸不变），限制在原图范围内。
export const moveCropRect = (rect: CropRect, dx: number, dy: number): CropRect => ({
  x: clamp(rect.x + dx, 0, Math.max(0, 1 - rect.width)),
  y: clamp(rect.y + dy, 0, Math.max(0, 1 - rect.height)),
  width: rect.width,
  height: rect.height,
});

// 以中心等比缩放裁剪框（滚轮），并拉回原图范围内。
export const scaleCropRect = (rect: CropRect, factor: number): CropRect => {
  const centerX = rect.x + rect.width / 2;
  const centerY = rect.y + rect.height / 2;
  const width = clamp(rect.width * factor, MIN_CROP_SIZE, 1);
  const height = clamp(rect.height * factor, MIN_CROP_SIZE, 1);
  return {
    x: clamp(centerX - width / 2, 0, 1 - width),
    y: clamp(centerY - height / 2, 0, 1 - height),
    width,
    height,
  };
};

// 拖角缩放：corner 0=左上 1=右上 2=左下 3=右下，对角为锚点固定不动。
// targetRatio 非空时锁定像素比例（imageAspect = 原图宽 / 高），为空则自由缩放。
export const resizeCropRect = (
  start: CropRect,
  corner: number,
  dx: number,
  dy: number,
  imageAspect: number,
  targetRatio: number | null,
): CropRect => {
  const right = start.x + start.width;
  const bottom = start.y + start.height;
  const movingRight = corner === 1 || corner === 3;
  const movingBottom = corner === 2 || corner === 3;
  const wanted = targetRatio === null ? null : targetRatio / imageAspect; // 归一化宽高比
  const maxWidth = movingRight ? 1 - start.x : right;
  const maxHeight = movingBottom ? 1 - start.y : bottom;

  let width = movingRight ? start.width + dx : start.width - dx;
  let height = movingBottom ? start.height + dy : start.height - dy;
  if (wanted !== null) {
    if (Math.abs(dx) >= Math.abs(dy)) height = width / wanted;
    else width = height * wanted;
  }

  const shrink = Math.min(1, maxWidth / Math.max(width, EPSILON), maxHeight / Math.max(height, EPSILON));
  if (shrink < 1) {
    width *= shrink;
    height *= shrink;
  }
  width = clamp(width, MIN_CROP_SIZE, maxWidth);
  height = clamp(height, MIN_CROP_SIZE, maxHeight);
  if (wanted !== null) {
    // 夹紧后回推一次，尽量保住比例；若高度被空间限制则按高度定宽。
    height = width / wanted;
    if (height > maxHeight) {
      height = maxHeight;
      width = height * wanted;
    }
    if (width > maxWidth) {
      width = maxWidth;
      height = width / wanted;
    }
  }

  return {
    x: movingRight ? start.x : Math.max(0, right - width),
    y: movingBottom ? start.y : Math.max(0, bottom - height),
    width,
    height,
  };
};
