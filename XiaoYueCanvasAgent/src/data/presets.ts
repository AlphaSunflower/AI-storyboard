import type { NodePreset } from '../types';

export const NODE_PRESETS: NodePreset[] = [
  { kind: 'text', title: '文字', description: '记录脚本、说明和设计判断', accent: '#f4c95d' },
  { kind: 'image', title: '图片', description: '上传真实图片后进行非破坏编辑', accent: '#ff775f' },
  { kind: 'video', title: '视频', description: '导入真实视频，编排参考素材与分镜参数', accent: '#8f9cff' },
  { kind: 'storyboard', title: '分镜', description: '连接剧本节点，展示分镜帧网格', accent: '#ffb3a1' },
  { kind: 'storyboardGen', title: '分镜生成', description: '按剧本生成分镜帧网格（AI）', accent: '#ff8a3d' },
  { kind: 'imageGen', title: 'AI 图片', description: '连接上游参考图，生成 AI 图片', accent: '#b48cff' },
  { kind: 'videoGen', title: '生视频', description: '连接上游参考图，生成 AI 视频', accent: '#8f9cff' },
  { kind: 'note', title: '批注', description: '设计说明与批注', accent: '#e5e7eb' },
  { kind: 'prompt', title: '提示词', description: 'AI 生图/生视频的提示词容器', accent: '#c4b5fd' },
  { kind: 'sequence', title: '序列', description: '编排组合多个子节点', accent: '#f9a8d4' },
];

