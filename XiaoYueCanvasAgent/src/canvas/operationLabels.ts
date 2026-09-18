import type { MediaOperation } from '../types';

// 操作类型 → 中文短名，用于连线标签和详情面板的简略展示。
export const OPERATION_LABELS: Record<MediaOperation['type'], string> = {
  import: '导入',
  crop: '裁剪',
  split: '切分',
  pixels: '像素',
  annotation: '标注',
  flatten: '合并历史',
  remote: 'AI 生成',
  videoFrame: '提取帧',
  videoFlip: '水平翻转',
  videoColor: '调色',
  videoSpeed: '变速',
  videoMerge: '视频合并',
};

export const operationLabel = (type?: MediaOperation['type']): string | undefined => (type ? OPERATION_LABELS[type] : undefined);
