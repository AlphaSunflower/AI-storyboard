import type { NodePreset } from '../types';

export const NODE_PRESETS: NodePreset[] = [
  { kind: 'text', title: '文字', description: '记录脚本、说明和设计判断', accent: '#f4c95d' },
  { kind: 'image', title: '图片', description: '上传真实图片后进行非破坏编辑', accent: '#ff775f' },
  { kind: 'video', title: '视频', description: '导入真实视频，编排参考素材与分镜参数', accent: '#8f9cff' },
  { kind: 'script', title: '剧本', description: '粘贴分镜剧本，后续派生', accent: '#a9c9f5' },
  { kind: 'storyboard', title: '分镜', description: '连接剧本节点，展示分镜帧网格', accent: '#ffb3a1' },
  { kind: 'storyboardGen', title: '分镜生成', description: '按剧本生成分镜帧网格（AI）', accent: '#ff8a3d' },
  { kind: 'note', title: '批注', description: '设计说明与批注', accent: '#d7ff54' },
  { kind: 'prompt', title: '提示词', description: 'AI 生图/生视频的提示词容器', accent: '#c4b5fd' },
  { kind: 'sequence', title: '序列', description: '编排组合多个子节点', accent: '#f9a8d4' },
];

export const CANVAS_TEMPLATES = [
  {
    id: 'image-review',
    title: '图片评审流程',
    description: '原图、修改版本与设计说明的空结构',
    colors: ['#ff775f', '#f4c95d'],
  },
  {
    id: 'variant-study',
    title: '视觉版本探索',
    description: '一张源图连接三个空白版本节点',
    colors: ['#ff775f', '#d7ff54', '#ff775f'],
  },
];
