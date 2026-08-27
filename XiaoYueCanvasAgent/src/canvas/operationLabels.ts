import type { ImageOperation } from '../types';

// 操作类型 → 中文短名，用于连线标签和详情面板的简略展示。
export const OPERATION_LABELS: Record<ImageOperation['type'], string> = {
  import: '导入',
  crop: '裁剪',
  split: '切分',
  pixels: '像素',
  annotation: '标注',
  flatten: '合并历史',
  remote: 'AI 生成',
};

export const operationLabel = (type?: ImageOperation['type']): string | undefined => (type ? OPERATION_LABELS[type] : undefined);
