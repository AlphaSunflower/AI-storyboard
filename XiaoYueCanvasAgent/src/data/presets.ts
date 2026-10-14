import type { NodePreset } from '../types';

export const NODE_PRESETS: NodePreset[] = [
  { kind: 'text', title: '文字', description: '记录脚本、说明和设计判断', accent: '#cacaca' },
  { kind: 'image', title: '图片', description: '上传真实图片后进行非破坏编辑', accent: '#929292' },
  { kind: 'video', title: '视频', description: '导入真实视频，编排参考素材与分镜参数', accent: '#a0a0a0' },
  { kind: 'storyboard', title: '分镜', description: '连接剧本节点，展示分镜帧网格', accent: '#c2c2c2' },
  { kind: 'storyboardGen', title: '分镜生成', description: '按剧本生成分镜帧网格（AI）', accent: '#9d9d9d' },
  { kind: 'imageGen', title: 'AI 图片', description: '连接上游参考图，生成 AI 图片', accent: '#9d9d9d' },
  { kind: 'videoGen', title: '生视频', description: '连接上游参考图，生成 AI 视频', accent: '#a0a0a0' },
  { kind: 'note', title: '批注', description: '设计说明与批注', accent: '#e7e7e7' },
  { kind: 'prompt', title: '提示词', description: 'AI 生图/生视频的提示词容器', accent: '#bdbdbd' },
  { kind: 'sequence', title: '序列', description: '编排组合多个子节点', accent: '#bcbcbc' },
];

