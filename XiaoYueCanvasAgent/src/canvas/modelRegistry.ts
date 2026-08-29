import type { MediaKind } from '../types';

/**
 * 本地模型注册表（前端常量，仅供节点 UI 展示「通道|模型」下拉 + 把选择存到 node.data）。
 *
 * 诚实边界（ponytail）：当前画布生成走 jobRunner(agent.request)，后端不消费这里的模型名。
 * 这里只为「样子对齐参考节点 footer」——让 imageGen/videoGen 有真实的通道|模型|比例|尺寸选择器，
 * 选择结果存 node.data.model/size/aspectRatio，后续接后端生图/生视频时直接透传。
 * 不需要模型不存在也不要假装模型在跑；列表是参考节点同款常用模型。
 */

export interface ModelOption {
  id: string;
  label: string;
  /** 支持的比例 */
  ratios: string[];
  /** 支持的尺寸档（图片） / 分辨率（视频） */
  sizes: string[];
}

export interface ChannelDef {
  id: string;
  label: string;
  models: ModelOption[];
}

const IMAGE_RATIOS = ['1:1', '16:9', '9:16', '4:3', '3:4', '3:2', '2:3', '21:9'];
const IMAGE_SIZES = ['1K', '2K', '4K'];
const VIDEO_RATIOS = ['16:9', '9:16', '1:1', '4:3', '3:4', '21:9'];
const VIDEO_SIZES = ['480P', '720P', '1080P'];

const model = (id: string, label: string, ratios: string[], sizes: string[]): ModelOption => ({ id, label, ratios, sizes });

export const IMAGE_CHANNELS: ChannelDef[] = [
  {
    id: 'image-model',
    label: '图像通道',
    models: [
      model('image-model/gpt-image-2', 'GPT Image 2', IMAGE_RATIOS, IMAGE_SIZES),
      model('image-model/nano-banana', 'Nano Banana', IMAGE_RATIOS, IMAGE_SIZES),
      model('image-model/seedream-5.0', 'Seedream 5.0', IMAGE_RATIOS, ['2K', '4K']),
    ],
  },
];

export const VIDEO_CHANNELS: ChannelDef[] = [
  {
    id: 'video-model',
    label: '视频通道',
    models: [
      model('video-model/seedance-2.0', 'Seedance 2.0', VIDEO_RATIOS, VIDEO_SIZES),
      model('video-model/seedance-2.0-mini', 'Seedance 2.0 Mini', VIDEO_RATIOS, ['480P', '720P']),
      model('video-model/kling-2.1', 'Kling 2.1', VIDEO_RATIOS, VIDEO_SIZES),
    ],
  },
];

export function getChannelsForKind(kind: MediaKind): ChannelDef[] {
  return kind === 'videoGen' ? VIDEO_CHANNELS : kind === 'imageGen' ? IMAGE_CHANNELS : [];
}

export function getDefaultModel(kind: MediaKind, channelId = ''): ModelOption | undefined {
  const channels = getChannelsForKind(kind);
  const chosen = channels.find((c) => c.id === channelId) ?? channels[0];
  return chosen?.models[0];
}

/** 找出某模型所在通道 id（用于切换通道后回填模型默认值）。 */
export function findChannelForModel(kind: MediaKind, modelId: string): string {
  const channels = getChannelsForKind(kind);
  return channels.find((c) => c.models.some((m) => m.id === modelId))?.id ?? channels[0]?.id ?? '';
}
