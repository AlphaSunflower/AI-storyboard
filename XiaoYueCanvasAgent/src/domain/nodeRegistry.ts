import type { MediaKind } from '../types';

/**
 * 节点定义（声明式注册，仿参考画布 nodeRegistry）。
 * 新增节点类型只需在 NODE_DEFS 加一条 + 在渲染层分发 kind：nodeTypes 已在 CanvasWorkspace 注册，
 * UI（节点库/菜单）由本 registry 驱动，零改动。
 *
 * 连通性语义（2026-08-29 对齐用户"组件有限连接"要求）：
 * - `connectivity.acceptTypes` = **该节点作为「源」能连到哪些目标 kind 的白名单**。
 *   - 非空数组：只能连到白名单内的目标，其余一律拒绝连线。
 *   - 空数组：该源**不允许连出任何目标**（终端/注释/容器节点，靠挂载而非连线）。
 * - 校验统一走 canvasStore 的 `canConnect(sourceKind, targetKind)`，所有连线路径
 *   （connect / createEdge / createConnectedNode / createNodeBefore）都复用，防漏网。
 */
export interface NodeDef {
  type: MediaKind;
  /** 注册表键（toolRegistry / store 里的 type 别名） */
  key: string;
  menuLabel: string;
  menuIcon: string;
  category: '基础' | '文本' | '媒体' | '编排';
  capabilities: { hasImage: boolean; hasPrompt: boolean; hasExport: boolean; hasTools: boolean };
  connectivity: { maxInputs: number; maxOutputs: number; acceptTypes: MediaKind[] };
  createDefaultData: () => Record<string, unknown>;
}

export const NODE_CATEGORIES = ['基础', '文本', '媒体', '编排'] as const;
export type NodeCategory = (typeof NODE_CATEGORIES)[number];

export const NODE_DEFS: Record<string, NodeDef> = {
  text: {
    type: 'text', key: 'text', menuLabel: '文字', menuIcon: 'text', category: '基础',
    capabilities: { hasImage: false, hasPrompt: false, hasExport: false, hasTools: false },
    // 文本可喂给生图/生视频（用户：剧本/文本可连生视频）
    connectivity: { maxInputs: 0, maxOutputs: 0, acceptTypes: ['imageGen', 'videoGen'] },
    createDefaultData: () => ({ kind: 'text', label: '文字', prompt: '', status: 'idle', accent: '#f4c95d' }),
  },
  image: {
    type: 'image', key: 'image', menuLabel: '图片', menuIcon: 'image', category: '媒体',
    capabilities: { hasImage: true, hasPrompt: true, hasExport: false, hasTools: true },
    // 用户明确：图片只能连「生成图片」「生视频」
    connectivity: { maxInputs: 0, maxOutputs: 0, acceptTypes: ['imageGen', 'videoGen'] },
    createDefaultData: () => ({ kind: 'image', label: '图片', prompt: '', status: 'idle', accent: '#ff775f' }),
  },
  videoGen: {
    type: 'videoGen', key: 'videoGen', menuLabel: '生视频', menuIcon: 'video', category: '媒体',
    capabilities: { hasImage: true, hasPrompt: true, hasExport: false, hasTools: false },
    // 生视频节点是生成器：源侧不再连出（产出的视频由结果节点承接，暂未引入）
    connectivity: { maxInputs: 0, maxOutputs: 0, acceptTypes: [] },
    createDefaultData: () => ({ kind: 'videoGen', label: '生视频', prompt: '', status: 'idle', accent: '#8f9cff' }),
  },
  video: {
    type: 'video', key: 'video', menuLabel: '视频', menuIcon: 'video', category: '媒体',
    capabilities: { hasImage: true, hasPrompt: true, hasExport: false, hasTools: true },
    // 视频帧可作生图/生视频参考
    connectivity: { maxInputs: 0, maxOutputs: 0, acceptTypes: ['imageGen', 'videoGen'] },
    createDefaultData: () => ({ kind: 'video', label: '视频', prompt: '', status: 'idle', accent: '#8f9cff', videoParams: { model: 'Seedance 2.0 Mini', mode: '全能参考', aspectRatio: '16:9', resolution: '1080p', durationSeconds: 12, variations: 1 } }),
  },
  script: {
    type: 'script', key: 'script', menuLabel: '剧本', menuIcon: 'script', category: '基础',
    capabilities: { hasImage: false, hasPrompt: true, hasExport: false, hasTools: false },
    // 剧本 → 分镜 / 分镜生成 / 生视频（用户：剧本可连生视频）
    connectivity: { maxInputs: 0, maxOutputs: 0, acceptTypes: ['storyboard', 'storyboardGen', 'videoGen'] },
    createDefaultData: () => ({ kind: 'script', label: '剧本', scriptText: '', prompt: '', status: 'idle', accent: '#a9c9f5' }),
  },
  storyboard: {
    type: 'storyboard', key: 'storyboard', menuLabel: '分镜', menuIcon: 'storyboard', category: '基础',
    capabilities: { hasImage: true, hasPrompt: false, hasExport: false, hasTools: false },
    // 分镜帧网格可作生图/生视频参考
    connectivity: { maxInputs: 0, maxOutputs: 0, acceptTypes: ['imageGen', 'videoGen'] },
    createDefaultData: () => ({ kind: 'storyboard', label: '分镜', frames: [], prompt: '', status: 'idle', accent: '#ffb3a1' }),
  },
  storyboardGen: {
    type: 'storyboardGen', key: 'storyboardGen', menuLabel: '分镜生成', menuIcon: 'storyboard', category: '基础',
    capabilities: { hasImage: false, hasPrompt: true, hasExport: false, hasTools: true },
    // 分镜生成本身是生成器，源侧连出由后续结果节点承接
    connectivity: { maxInputs: 0, maxOutputs: 0, acceptTypes: [] },
    createDefaultData: () => ({ kind: 'storyboardGen', label: '分镜生成', rows: 2, cols: 3, styleType: '2d-anime', model: '', frames: [], prompt: '', status: 'idle', isGenerating: false, accent: '#ff8a3d' }),
  },
  imageGen: {
    type: 'imageGen', key: 'imageGen', menuLabel: 'AI 图片', menuIcon: 'image', category: '媒体',
    capabilities: { hasImage: true, hasPrompt: true, hasExport: false, hasTools: false },
    // 生成器，源侧不再连出
    connectivity: { maxInputs: 0, maxOutputs: 0, acceptTypes: [] },
    createDefaultData: () => ({ kind: 'imageGen', label: 'AI 图片', prompt: '', status: 'idle', accent: '#b48cff' }),
  },
  note: {
    type: 'note', key: 'note', menuLabel: '批注', menuIcon: 'note', category: '文本',
    capabilities: { hasImage: false, hasPrompt: false, hasExport: false, hasTools: false },
    // 批注是终端注释，不允许连出
    connectivity: { maxInputs: 0, maxOutputs: 0, acceptTypes: [] },
    createDefaultData: () => ({ kind: 'note', label: '批注', richText: '', prompt: '', status: 'idle', accent: '#d7ff54' }),
  },
  prompt: {
    type: 'prompt', key: 'prompt', menuLabel: '提示词', menuIcon: 'prompt', category: '文本',
    capabilities: { hasImage: false, hasPrompt: true, hasExport: false, hasTools: false },
    // 提示词容器可喂给生图/生视频
    connectivity: { maxInputs: 0, maxOutputs: 0, acceptTypes: ['imageGen', 'videoGen'] },
    createDefaultData: () => ({ kind: 'prompt', label: '提示词', promptText: '', prompt: '', status: 'idle', accent: '#c4b5fd' }),
  },
  sequence: {
    type: 'sequence', key: 'sequence', menuLabel: '序列', menuIcon: 'sequence', category: '编排',
    capabilities: { hasImage: false, hasPrompt: false, hasExport: true, hasTools: false },
    // 序列是容器：子节点靠挂载（children），不走连线，故源侧不允许连出
    connectivity: { maxInputs: 0, maxOutputs: 0, acceptTypes: [] },
    createDefaultData: () => ({ kind: 'sequence', label: '序列', children: [], prompt: '', status: 'idle', accent: '#f9a8d4' }),
  },
};

export function getNodeDefByType(type: string | undefined): NodeDef | undefined {
  return type ? NODE_DEFS[type] : undefined;
}

/** 节点库可展示的节点（全部，含基础几种）。 */
export function listMenuNodes(): NodeDef[] {
  return Object.values(NODE_DEFS);
}
