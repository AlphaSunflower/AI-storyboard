import {
  Clapperboard, FileText, Film, Image as ImageIcon, LayoutGrid, Layers, ListVideo, MessageSquare,
  Sparkles, Type, Video, Wand2,
} from 'lucide-react';

/**
 * 节点 kind → 图标。节点库菜单与外置标题共用同一份映射，
 * 避免「同一个节点在两处显示不同图标」。
 */
const KIND_ICONS: Record<string, typeof Type> = {
  text: Type,
  script: FileText,
  note: MessageSquare,
  prompt: Wand2,
  sequence: Layers,
  storyboard: Clapperboard,
  storyboardGen: LayoutGrid,
  storyboardPlanner: LayoutGrid,
  image: ImageIcon,
  imageGen: Sparkles,
  textToImage: Sparkles,
  imageToImage: Sparkles,
  batchImageRefine: Sparkles,
  imageGeneration: Sparkles,
  video: Video,
  videoGen: Video,
  textToVideo: Video,
  imageToVideo: Video,
  firstLastFrameToVideo: Video,
  referenceToVideo: Video,
  // 左侧工具栏只有图标没有文字，撞图就等于认不出来：
  // 生成类走「AI 生成」语义（Sparkles / Wand2），与「图片」「视频」本体区分开。
  videoGeneration: Wand2,
  // shot 原先落到 ImageIcon 兜底，和「图片」是同一个图标。镜头用胶片格。
  shot: Film,
  // videoMerge 原先用 Merge——它渲染出来是一支朝上的箭头，看着像「上传」。
  // 合并多段视频用「片段列表」更直白。
  videoMerge: ListVideo,
};

export function nodeKindIcon(kind: string | undefined) {
  return (kind ? KIND_ICONS[kind] : undefined) ?? ImageIcon;
}
